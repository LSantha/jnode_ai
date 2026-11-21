const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const WORKFLOW_DIR = path.join(__dirname, "..", "..", "workflows");
const WORKFLOWS = ["opencode.yml", "triage.yml", "wiki-update.yml"];
const ACTION_USES = "anomalyco/opencode/github@";

function readWorkflow(name) {
  return fs.readFileSync(path.join(WORKFLOW_DIR, name), "utf8");
}

function isStepStart(line) {
  return /^\s*-\s+\S/.test(line);
}

function stepContaining(text, needle) {
  const lines = text.split(/\r?\n/);
  const at = lines.findIndex((line) => line.includes(needle));
  assert.notStrictEqual(at, -1, "no workflow step contains " + needle);
  let start = at;
  while (start > 0 && !isStepStart(lines[start])) start -= 1;
  let end = at + 1;
  while (end < lines.length && !isStepStart(lines[end])) end += 1;
  return { start, end, text: lines.slice(start, end).join("\n") };
}

function pinOf(text) {
  const m = text.match(/anomalyco\/opencode\/github@(\S+)/);
  assert.ok(m, "workflow must reference anomalyco/opencode/github");
  return m[1];
}

test("no workflow uses @latest for the vendored opencode action", () => {
  for (const name of WORKFLOWS) {
    const text = readWorkflow(name);
    assert.doesNotMatch(text, /anomalyco\/opencode\/github@latest/, name + " must not use @latest");
  }
  // broader: no @latest anywhere in workflows
  for (const f of fs.readdirSync(WORKFLOW_DIR).filter((x) => x.endsWith(".yml"))) {
    const text = readWorkflow(f);
    assert.doesNotMatch(text, /@latest/, f + " must not reference @latest");
  }
});

test("all three workflows pin anomalyco/opencode/github to the same ref", () => {
  const pins = WORKFLOWS.map((name) => ({ name, pin: pinOf(readWorkflow(name)) }));
  const first = pins[0].pin;
  for (const { name, pin } of pins) {
    assert.strictEqual(pin, first, name + " pin (" + pin + ") must match " + pins[0].name + " (" + first + ")");
  }
  assert.notStrictEqual(first, "latest", "pin must not be 'latest'");
});

test("the pinned ref is an explicit tag or commit SHA", () => {
  const pin = pinOf(readWorkflow("opencode.yml"));
  assert.ok(
    /^v\d+\.\d+\.\d+$/.test(pin) || /^[0-9a-f]{7,40}$/.test(pin),
    "pin must be a version tag or commit SHA, got: " + pin
  );
});

test("run_agent step has a step-level timeout-minutes", () => {
  for (const name of WORKFLOWS) {
    const text = readWorkflow(name);
    const step = stepContaining(text, ACTION_USES);
    assert.match(step.text, /^\s*timeout-minutes:\s*\d+\s*$/m, name + ": run_agent step must set timeout-minutes");
  }
});

test("run_agent step timeout is below its job timeout and above a legitimate run", () => {
  // A step cap must leave room for a real agent run (issue #870: 68m26s
  // spent inside the Run opencode step) while staying under the job cap.
  const jobTimeoutOf = (text) => {
    const m = text.match(/^\s*timeout-minutes:\s*(\d+)\s*$/m);
    assert.ok(m, "workflow must declare a job-level timeout-minutes");
    return Number(m[1]);
  };
  for (const name of WORKFLOWS) {
    const text = readWorkflow(name);
    const step = stepContaining(text, ACTION_USES);
    const m = step.text.match(/^\s*timeout-minutes:\s*(\d+)\s*$/m);
    const stepTimeout = Number(m[1]);
    const jobTimeout = jobTimeoutOf(text);
    assert.ok(stepTimeout < jobTimeout, name + ": step timeout must be below job timeout");
    assert.ok(stepTimeout > 0, name + ": step timeout must be positive");
  }
  // opencode.yml's cap must cover the observed 68m26s successful run.
  const opencodeStep = stepContaining(readWorkflow("opencode.yml"), ACTION_USES);
  const opencodeTimeout = Number(opencodeStep.text.match(/^\s*timeout-minutes:\s*(\d+)\s*$/m)[1]);
  assert.ok(opencodeTimeout >= 69, "opencode step timeout must exceed the 68m26s legitimate run");
});

test("preflight step exists in all three workflows", () => {
  for (const name of WORKFLOWS) {
    const text = readWorkflow(name);
    const step = stepContaining(text, "Preflight: action release endpoint");
    assert.match(step.text, /gh api repos\/anomalyco\/opencode\/releases\/latest/, name + ": preflight must hit release endpoint");
    assert.match(step.text, /^\s*timeout-minutes:\s*2\s*$/m, name + ": preflight timeout must be 2");
    assert.match(step.text, /^\s*id:\s*preflight\s*$/m, name + ": preflight id must be preflight");
  }
});

test("post-step receives PREFLIGHT_OUTCOME from preflight", () => {
  for (const name of ["opencode.yml", "triage.yml"]) {
    const text = readWorkflow(name);
    assert.match(text, /PREFLIGHT_OUTCOME:\s*\$\{\{\s*steps\.preflight\.outcome\s*\}\}/, name + ": post-step must receive PREFLIGHT_OUTCOME");
  }
});

test("run_agent step is gated on preflight success", () => {
  for (const name of ["opencode.yml", "triage.yml"]) {
    const text = readWorkflow(name);
    const step = stepContaining(text, ACTION_USES);
    assert.match(step.text, /steps\.preflight\.outcome == 'success'/, name + ": run_agent must be gated on preflight success");
  }
});

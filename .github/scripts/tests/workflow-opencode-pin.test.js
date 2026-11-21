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

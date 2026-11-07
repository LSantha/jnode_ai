const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const WORKFLOW_DIR = path.join(__dirname, "..", "..", "workflows");
const SELECTION_REQUIRE = "require('./.github/scripts/model-config.js')";
const ACTION_USES = "anomalyco/opencode/github@";
const MODEL_ENV = /^\$\{\{\s*env\.SELECTED_MODEL\s*\}\}$/;
const VARIANT_ENV = /^\$\{\{\s*env\.SELECTED_VARIANT\s*\}\}$/;

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

function withBlock(stepText) {
  const lines = stepText.split(/\r?\n/);
  const at = lines.findIndex((line) => /^\s*with:\s*$/.test(line));
  assert.notStrictEqual(at, -1, "step has no with: block");
  const indent = lines[at].match(/^\s*/)[0].length;
  const block = [];
  for (let i = at + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === "") continue;
    if (line.match(/^\s*/)[0].length <= indent) break;
    block.push(line);
  }
  return block.join("\n");
}

function withValue(withText, key) {
  const match = withText.match(new RegExp("^\\s*" + key + ":\\s*(.*)$", "m"));
  assert.ok(match, "with: block has no " + key);
  return match[1].trim();
}

function assertActionWiring(workflowName) {
  const text = readWorkflow(workflowName);
  const selection = stepContaining(text, SELECTION_REQUIRE);
  const action = stepContaining(text, ACTION_USES);
  const withText = withBlock(action.text);

  assert.ok(
    selection.start < action.start,
    workflowName + ": the model selection step must run before the action step"
  );
  assert.match(
    selection.text,
    /require\(['"]\.\/\.github\/scripts\/model-config\.js['"]\)/,
    workflowName + ": the selection step must require model-config.js"
  );

  assert.match(withValue(withText, "model"), MODEL_ENV, workflowName + ": model must come from env.SELECTED_MODEL");
  assert.match(withValue(withText, "variant"), VARIANT_ENV, workflowName + ": variant must come from env.SELECTED_VARIANT");

  assert.doesNotMatch(
    withText,
    /^\s*model:\s*opencode\//m,
    workflowName + ": the action step must not pin a literal model id"
  );
  assert.doesNotMatch(
    withText,
    /^\s*model:\s*opencode\/deepseek-v4\.1-flash-free/m,
    workflowName + ": the action step must not pin the current model id"
  );
  assert.doesNotMatch(
    withText,
    /^\s*variant:\s*(?!\$\{\{\s*env\.SELECTED_VARIANT\s*\}\})\S/m,
    workflowName + ": the action step must not pin a literal variant value"
  );

  return { selection, action, withText };
}

test("opencode.yml wires the action step to the selected model and variant", () => {
  const { selection } = assertActionWiring("opencode.yml");
  assert.ok(selection.text.includes("resolveModel"), "opencode.yml must resolve the role from the trigger verb");
});

test("opencode.yml selection step exports the selected model, variant and attribution model", () => {
  const { selection } = assertActionWiring("opencode.yml");
  for (const name of ["SELECTED_MODEL", "SELECTED_VARIANT", "OPENCODE_MODEL"]) {
    assert.match(
      selection.text,
      new RegExp("core\\.exportVariable\\(\\s*['\"]" + name + "['\"]"),
      "opencode.yml must export " + name
    );
  }
});

test("triage.yml wires the action step to the selected model and variant", () => {
  const { selection } = assertActionWiring("triage.yml");
  assert.ok(selection.text.includes("modelFor('triage')"), "triage.yml must ask for the triage role by name");
});

test("triage.yml selection step exports the selected model and variant", () => {
  const { selection } = assertActionWiring("triage.yml");
  for (const name of ["SELECTED_MODEL", "SELECTED_VARIANT"]) {
    assert.match(
      selection.text,
      new RegExp("core\\.exportVariable\\(\\s*['\"]" + name + "['\"]"),
      "triage.yml must export " + name
    );
  }
});

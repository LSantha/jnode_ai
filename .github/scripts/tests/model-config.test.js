const test = require("node:test");
const assert = require("node:assert");

const { MODEL_VARIANTS, DEFAULT_VARIANT, ROLE_MODELS, variantFor, modelFor, resolveModel } = require("../model-config.js");

test("review verb resolves to the review role", () => {
  const r = resolveModel("/oc review\n\nYou are reviewing a pull request...");
  assert.strictEqual(r.role, "review");
  assert.strictEqual(r.model, ROLE_MODELS.review);
  assert.strictEqual(r.variant, variantFor(ROLE_MODELS.review));
});

test("coding verbs resolve to the coding role", () => {
  for (const body of [
    "/oc fix Address the review feedback.",
    "/oc investigate why the build fails",
    "/oc Please proceed with this task.",
    "/oc"
  ]) {
    assert.strictEqual(resolveModel(body).role, "coding", body.slice(0, 30));
  }
});

test("a body that merely mentions /oc review still codes", () => {
  // The exact misroute this module guards against: a fix prompt quoting a
  // review request must not flip the model.
  const body = "/oc fix The review asked for \\\"Verdict: request-changes\\\" on /oc review output; address it.";
  assert.strictEqual(resolveModel(body).role, "coding");
});

test("leading whitespace still matches the review verb", () => {
  assert.strictEqual(resolveModel("  /oc review\n\n...").role, "review");
});

test("an empty or missing body defaults to coding", () => {
  assert.strictEqual(resolveModel("").role, "coding");
  assert.strictEqual(resolveModel(undefined).role, "coding");
});

test("every role in the map resolves to a model and a variant", () => {
  for (const role of Object.keys(ROLE_MODELS)) {
    const r = modelFor(role);
    assert.ok(r.model, role + " has no model");
    assert.ok(r.variant, role + " has no variant");
  }
});

test("an unknown role throws rather than defaulting silently", () => {
  assert.throws(() => modelFor("does-not-exist"), /Unknown agent role/);
});

test("an unlisted model falls back to the default variant", () => {
  assert.strictEqual(variantFor("opencode/some-other-model"), DEFAULT_VARIANT);
  assert.strictEqual(variantFor(undefined), DEFAULT_VARIANT);
});

test("a listed model gets its own declared effort", () => {
  const known = Object.keys(MODEL_VARIANTS)[0];
  assert.strictEqual(variantFor(known), MODEL_VARIANTS[known]);
});

test("a MODEL override marker selects that model (second-opinion reviews)", () => {
  const r = resolveModel("/oc review\n\n<!-- MODEL: opencode/deepseek-v4.1-flash-free --> second opinion");
  assert.strictEqual(r.role, "override");
  assert.strictEqual(r.model, "opencode/deepseek-v4.1-flash-free");
  assert.strictEqual(r.variant, "max", "deepseek runs at its strongest effort");
});

test("a MODEL override for a coding trigger still overrides", () => {
  const r = resolveModel("/oc fix\n\n<!-- MODEL: opencode/inkling-2-small-free --> try this one");
  assert.strictEqual(r.model, "opencode/inkling-2-small-free");
  assert.strictEqual(r.variant, "max");
});

test("an unknown MODEL id is ignored, not honoured", () => {
  const r = resolveModel("/oc review\n\n<!-- MODEL: opencode/something-expensive -->");
  assert.strictEqual(r.role, "review", "falls back to the role default");
  assert.strictEqual(r.model, ROLE_MODELS.review);
});

test("no marker means the role default", () => {
  const r = resolveModel("/oc review\n\nordinary review");
  assert.strictEqual(r.model, ROLE_MODELS.review);
  assert.strictEqual(r.role, "review");
});

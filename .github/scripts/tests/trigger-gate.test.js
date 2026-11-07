const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const { parseTrigger, isTrigger, isDuplicateDispatch, dedupeCheck } = require("../trigger-gate.js");
const { resolveModel } = require("../model-config.js");

test("a body-leading /oc command triggers with its verb", () => {
  for (const [body, verb] of [
    ["/oc review\n\nYou are reviewing...", "review"],
    ["/oc fix Address the review feedback.", "fix"],
    ["/oc Please proceed with this task.", "Please"],
    ["/oc investigate why the build fails", "investigate"]
  ]) {
    const t = parseTrigger(body);
    assert.strictEqual(t.triggered, true, body.slice(0, 30));
    assert.strictEqual(t.verb, verb, body.slice(0, 30));
    assert.strictEqual(t.line, 0, body.slice(0, 30));
  }
});

test("a mid-line quoted /oc does not trigger (PR #849 self-retrigger)", () => {
  // The exact agent comment that fired the duplicate run: its own trigger
  // quoted mid-line in the re-review heading.
  const body = "## Re-review \u2014 #848 / PR #849 (after /oc fix)\n\nVerified the fix...";
  assert.strictEqual(isTrigger(body), false);
  assert.strictEqual(parseTrigger(body).triggered, false);
});

test("a mid-line mention of /oc does not trigger", () => {
  assert.strictEqual(isTrigger("Please run /oc review on this when ready"), false);
  assert.strictEqual(isTrigger("quoted \"/oc fix\" in the previous comment"), false);
  assert.strictEqual(isTrigger("see the /oc triage output above"), false);
});

test("a line-leading /oc after a preamble still triggers", () => {
  const t = parseTrigger("Context first.\n\n/oc review\n\nMore text.");
  assert.strictEqual(t.triggered, true);
  assert.strictEqual(t.verb, "review");
  assert.strictEqual(t.line, 2);
});

test("indented line-leading /oc triggers, blockquoted /oc does not", () => {
  assert.strictEqual(isTrigger("  /oc fix do it"), true);
  assert.strictEqual(isTrigger("> /oc fix do it"), false);
});

test("triage and approve verbs are excluded from opencode", () => {
  for (const body of ["/oc triage issue #42", "/oc triage", "/oc approve", "/oc approve --force"]) {
    assert.strictEqual(isTrigger(body), false, body);
  }
  assert.strictEqual(parseTrigger("/oc triage issue #42").verb, "triage");
  assert.strictEqual(parseTrigger("/oc approve").verb, "approve");
});

test("a bare /oc and an empty body do not trigger", () => {
  assert.strictEqual(isTrigger("/oc"), false);
  assert.strictEqual(isTrigger(""), false);
  assert.strictEqual(isTrigger(undefined), false);
  assert.strictEqual(isTrigger("/ocreview"), false);
});

test("the first line-leading command decides", () => {
  const t = parseTrigger("/oc fix see /oc review for details");
  assert.strictEqual(t.triggered, true);
  assert.strictEqual(t.verb, "fix");
  assert.strictEqual(resolveModel("/oc fix see /oc review for details").role, "coding");
});

test("two dispatches of the same payload: the second no-ops, both select the override", () => {
  const body = "/oc review <!-- MODEL: opencode/deepseek-v4.1-flash-free --> second opinion";
  const first = resolveModel(body);
  const second = resolveModel(body);
  assert.strictEqual(first.role, "override");
  assert.strictEqual(second.role, "override");
  assert.strictEqual(second.model, "opencode/deepseek-v4.1-flash-free");

  const runs = [
    { runId: 100, createdAt: "2026-11-07T12:10:51Z", displayTitle: "Issue #849 - t" },
    { runId: 101, createdAt: "2026-11-07T12:11:25Z", displayTitle: "Issue #849 - t" }
  ];
  const comment = { createdAt: "2026-11-07T12:10:48Z", issueNumber: 849 };
  assert.strictEqual(isDuplicateDispatch({ runId: 100, ...comment }, runs), false, "earliest dispatch acts");
  assert.strictEqual(isDuplicateDispatch({ runId: 101, ...comment }, runs), true, "later dispatch of same comment no-ops");
});

test("a run for a distinct, later comment is not a duplicate", () => {
  const runs = [{ runId: 100, createdAt: "2026-11-07T12:10:51Z", displayTitle: "Issue #849 - t" }];
  const later = { runId: 101, createdAt: "2026-11-07T12:20:00Z", issueNumber: 849 };
  assert.strictEqual(isDuplicateDispatch(later, runs), false);
});

test("a run for a different issue is not a duplicate", () => {
  const runs = [{ runId: 100, createdAt: "2026-11-07T12:10:51Z", displayTitle: "Issue #850 - t" }];
  const cur = { runId: 101, createdAt: "2026-11-07T12:11:25Z", issueNumber: 849 };
  assert.strictEqual(isDuplicateDispatch(cur, runs), false);
});

test("dedupeCheck fails open when the runs API errors", async () => {
  const github = { rest: { actions: { listWorkflowRuns: async () => { throw new Error("down"); } } } };
  const context = {
    runId: 101,
    repo: { owner: "o", repo: "r" },
    payload: { comment: { id: 1, created_at: "2026-11-07T12:10:48Z" }, issue: { number: 849 } }
  };
  const core = { info: () => {}, warning: () => {} };
  assert.strictEqual(await dedupeCheck({ github, context, core }), true);
});

test("opencode.yml gates on a body-leading /oc and serializes per issue", () => {
  const text = fs.readFileSync(path.join(__dirname, "..", "..", "workflows", "opencode.yml"), "utf8");
  const jobIf = text.slice(text.indexOf("  opencode:"), text.indexOf("    runs-on:"));
  assert.match(jobIf, /startsWith\(github\.event\.comment\.body, '\/oc '\)/);
  assert.doesNotMatch(jobIf, /contains\(github\.event\.comment\.body, ' \/oc '\)/);
  assert.match(text, /concurrency:\s*\n\s*group: opencode-\$\{\{ github\.event\.issue\.number/);
  assert.match(text, /cancel-in-progress: false/);
});

test("opencode.yml dedupe step runs before the agent and gates every later step", () => {
  const text = fs.readFileSync(path.join(__dirname, "..", "..", "workflows", "opencode.yml"), "utf8");
  const dedupeAt = text.indexOf("Dedupe duplicate dispatches");
  const agentAt = text.indexOf("Run opencode");
  assert.ok(dedupeAt !== -1 && agentAt !== -1 && dedupeAt < agentAt, "dedupe step must precede the agent step");
  assert.match(text, /require\(['"]\.\/\.github\/scripts\/trigger-gate\.js['"]\)/);
  assert.match(text, /steps\.dedupe\.outputs\.act == 'true'/);
});

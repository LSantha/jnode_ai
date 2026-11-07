const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const {
  isAnchoredTrigger,
  isReportShaped,
  parseCommentIdFromTitle,
  decideShouldRun,
  opencodeDedupe
} = require("../opencode-dedupe.js");
const { resolveModel } = require("../model-config.js");

const OVERRIDE_BODY = "/oc review <!-- MODEL: opencode/deepseek-v4.1-flash-free -->\n\nIndependent second-opinion review.";
const TITLE_COMMENT_1 = "Issue #849 - title (comment 6706766822)";

const AGENT_REPORT_BODY = [
  "## Re-review - #848 / PR #849 (after /oc fix)",
  "",
  "Verdict: approve",
  "Assisted-by: opencode/crouton-alpha-free",
  "",
  "[github run](/LSantha/jnode_ai/actions/runs/42370921813)"
].join("\n");

test("two dispatches of the same payload: the first runs, the second no-ops", () => {
  const first = decideShouldRun({
    commentId: 6706766822,
    body: OVERRIDE_BODY,
    currentRunId: 42371327663,
    runs: []
  });
  assert.strictEqual(first.shouldRun, true, "earliest dispatch runs");

  const second = decideShouldRun({
    commentId: 6706766822,
    body: OVERRIDE_BODY,
    currentRunId: 42371366017,
    runs: [{ id: 42371327663, display_title: TITLE_COMMENT_1 }]
  });
  assert.strictEqual(second.shouldRun, false, "redelivered duplicate no-ops");
  assert.strictEqual(second.reason, "duplicate-of-earlier-run");
});

test("every dispatch of an override comment selects the override model", () => {
  for (let i = 0; i < 2; i += 1) {
    const sel = resolveModel(OVERRIDE_BODY);
    assert.strictEqual(sel.role, "override");
    assert.strictEqual(sel.model, "opencode/deepseek-v4.1-flash-free");
  }
});

test("the PR #849 agent report quoting /oc fix does not re-trigger", () => {
  assert.strictEqual(isReportShaped(AGENT_REPORT_BODY), true);
  assert.strictEqual(isAnchoredTrigger(AGENT_REPORT_BODY), false);
  const d = decideShouldRun({
    commentId: 6706772252,
    body: AGENT_REPORT_BODY,
    currentRunId: 42371366017,
    runs: []
  });
  assert.strictEqual(d.shouldRun, false);
  assert.strictEqual(d.reason, "agent-report-quote");
});

test("a verdict-instructing human prompt still runs (it starts with /oc)", () => {
  const body = "/oc review\n\nYour final line must be exactly one of:\nVerdict: approve\nVerdict: request-changes\nAssisted-by: <model id>";
  const d = decideShouldRun({ commentId: 1, body: body, currentRunId: 10, runs: [] });
  assert.strictEqual(d.shouldRun, true);
});

test("an embedded human trigger still runs", () => {
  const d = decideShouldRun({
    commentId: 2,
    body: "Please look at this /oc fix the failing test",
    currentRunId: 10,
    runs: []
  });
  assert.strictEqual(d.shouldRun, true);
});

test("a plain comment with no /oc mention does not run", () => {
  const d = decideShouldRun({ commentId: 3, body: "just a comment", currentRunId: 10, runs: [] });
  assert.strictEqual(d.shouldRun, false);
  assert.strictEqual(d.reason, "no-/oc-mention");
});

test("a different comment's earlier run does not block this one", () => {
  const d = decideShouldRun({
    commentId: 999,
    body: OVERRIDE_BODY,
    currentRunId: 20,
    runs: [{ id: 10, display_title: TITLE_COMMENT_1 }]
  });
  assert.strictEqual(d.shouldRun, true);
});

test("parseCommentIdFromTitle reads the run-name marker", () => {
  assert.strictEqual(parseCommentIdFromTitle(TITLE_COMMENT_1), 6706766822);
  assert.strictEqual(parseCommentIdFromTitle("Issue #849 - title"), null);
});

test("wrapper: no-op run exports SHOULD_RUN=false and sets the output", async () => {
  const exported = {};
  const outputs = {};
  const github = {
    rest: {
      actions: {
        listWorkflowRuns: async () => ({
          data: { workflow_runs: [{ id: 1, display_title: TITLE_COMMENT_1 }] }
        })
      }
    }
  };
  const context = {
    payload: { comment: { id: 6706766822, body: OVERRIDE_BODY } },
    repo: { owner: "LSantha", repo: "jnode_ai" },
    runId: 2
  };
  const core = {
    info: () => {},
    exportVariable: (k, v) => { exported[k] = v; },
    setOutput: (k, v) => { outputs[k] = v; }
  };
  const decision = await opencodeDedupe({ github, context, core });
  assert.strictEqual(decision.shouldRun, false);
  assert.strictEqual(exported.SHOULD_RUN, "false");
  assert.strictEqual(outputs.should_run, "false");
});

test("wrapper: a runs-API error fails closed", async () => {
  const exported = {};
  const github = {
    rest: {
      actions: {
        listWorkflowRuns: async () => { throw new Error("api down"); }
      }
    }
  };
  const context = {
    payload: { comment: { id: 6706766822, body: OVERRIDE_BODY } },
    repo: { owner: "LSantha", repo: "jnode_ai" },
    runId: 2
  };
  const core = {
    info: () => {},
    exportVariable: (k, v) => { exported[k] = v; },
    setOutput: () => {}
  };
  const decision = await opencodeDedupe({ github, context, core });
  assert.strictEqual(decision.shouldRun, false);
  assert.strictEqual(decision.reason, "runs-api-error");
  assert.strictEqual(exported.SHOULD_RUN, "false");
});

test("opencode.yml wires concurrency, run-name, dedupe step and payload-only selection", () => {
  const text = fs.readFileSync(path.join(__dirname, "..", "..", "workflows", "opencode.yml"), "utf8");
  assert.match(text, /concurrency:\n\s+group: opencode-comment-\$\{\{[^}]*github\.event\.comment\.id/);
  assert.match(text, /\(comment \$\{\{ github\.event\.comment\.id \}\}\)/);
  assert.match(text, /actions: read/);
  assert.match(text, /require\(['"]\.\/\.github\/scripts\/opencode-dedupe\.js['"]\)/);
  assert.match(text, /steps\.dedupe\.outputs\.should_run != 'false'/);
  assert.match(text, /resolveModel\(\(context\.payload\.comment \|\| \{\}\)\.body\)/);
  assert.doesNotMatch(text, /listComments|getComment|issues\.get/, "selection must not re-read a comment from the API");
});

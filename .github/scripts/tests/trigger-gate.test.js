const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const {
  isOcTrigger,
  isTrustedAssociation,
  runNameMarker,
  markerForRunName,
  isDuplicateRun,
  evaluate
} = require("../trigger-gate.js");

const WORKFLOW = path.join(__dirname, "..", "..", "workflows", "opencode.yml");
const COMMENT_ID = 6706766822;

function contextFor(body, extra) {
  return Object.assign({
    eventName: "issue_comment",
    runId: 42371366017,
    repo: { owner: "LSantha", repo: "jnode_ai" },
    payload: {
      comment: {
        id: COMMENT_ID,
        body: body,
        author_association: "OWNER"
      }
    }
  }, extra || {});
}

function mockGithub(runs) {
  return {
    rest: {
      actions: {
        listWorkflowRuns: async () => ({ data: { workflow_runs: runs } })
      }
    }
  };
}

function mockCore() {
  const logs = { info: [], warning: [] };
  return {
    logs: logs,
    info: (msg) => logs.info.push(msg),
    warning: (msg) => logs.warning.push(msg)
  };
}

test("/oc is a line-leading command", () => {
  assert.strictEqual(isOcTrigger("/oc review\n\nReview this PR."), true);
  assert.strictEqual(isOcTrigger("/oc fix Address the feedback."), true);
  assert.strictEqual(isOcTrigger("/oc Please proceed with this task."), true);
  assert.strictEqual(isOcTrigger("  /oc review"), false, "leading whitespace is not the command shape the workflow accepts");
});

test("a quoted or mid-line /oc does not trigger", () => {
  // The exact self-retrigger from PR #849: an agent review comment quoting the
  // verb that produced it used to match contains(' /oc ').
  assert.strictEqual(isOcTrigger("## Re-review - #848 / PR #849 (after /oc fix)"), false);
  assert.strictEqual(isOcTrigger("nothing to do here; run /oc fix later"), false);
  assert.strictEqual(isOcTrigger("prose\n/oc fix"), false, "only position 0 is the command; the old contains() never matched a newline either");
  assert.strictEqual(isOcTrigger("```\n/oc fix\n```"), false);
});

test("bare /oc and the excluded verbs do not trigger the agent", () => {
  assert.strictEqual(isOcTrigger("/oc"), false);
  assert.strictEqual(isOcTrigger("/oc "), false);
  assert.strictEqual(isOcTrigger("/oc triage issue #850"), false);
  assert.strictEqual(isOcTrigger("/oc  triage issue #850"), false, "token match, not prefix match");
  assert.strictEqual(isOcTrigger("/oc approve"), false);
  assert.strictEqual(isOcTrigger("/oc approve --force"), false);
});

test("an agent session link blocks a self-trigger", () => {
  assert.strictEqual(isOcTrigger("/oc fix see https://opencode.ai/s/abc123"), false);
});

test("only collaborator, member or owner associations are trusted", () => {
  for (const association of ["COLLABORATOR", "MEMBER", "OWNER"]) {
    assert.strictEqual(isTrustedAssociation(association), true, association);
  }
  for (const association of ["CONTRIBUTOR", "FIRST_TIME_CONTRIBUTOR", "NONE", undefined]) {
    assert.strictEqual(isTrustedAssociation(association), false, String(association));
  }
});

test("the run-name marker is first and round-trips", () => {
  const marker = runNameMarker(COMMENT_ID);
  assert.strictEqual(marker, "[oc:6706766822]");
  assert.strictEqual(markerForRunName(marker + " Issue #850 - title"), marker);
  assert.strictEqual(markerForRunName("Issue #850 - title"), null);
});

test("the earliest run for a comment owns the action", () => {
  const earlier = { id: 42371327663, display_title: runNameMarker(COMMENT_ID) + " Issue #849 - title" };
  const later = { id: 42371366017, display_title: runNameMarker(COMMENT_ID) + " Issue #849 - title" };

  assert.strictEqual(isDuplicateRun(later.id, COMMENT_ID, [earlier, later]), true, "second delivery no-ops");
  assert.strictEqual(isDuplicateRun(earlier.id, COMMENT_ID, [earlier, later]), false, "first delivery runs");
});

test("different comments are not duplicates of each other", () => {
  const other = { id: 100, display_title: runNameMarker(COMMENT_ID + 1) + " Issue #849 - title" };
  assert.strictEqual(isDuplicateRun(200, COMMENT_ID, [other]), false);
});

test("runs without a marker, or with a malformed id, never count as duplicates", () => {
  assert.strictEqual(isDuplicateRun(200, COMMENT_ID, [{ id: 100, display_title: "Issue #849 - title" }]), false);
  assert.strictEqual(isDuplicateRun(200, COMMENT_ID, [{ id: "nope", display_title: runNameMarker(COMMENT_ID) }]), false);
  assert.strictEqual(isDuplicateRun(200, COMMENT_ID, null), false);
});

test("eval: a normal first delivery runs and exports the decision", async () => {
  const core = mockCore();
  const github = mockGithub([{ id: 42371366017, display_title: runNameMarker(COMMENT_ID) + " Issue #849 - title" }]);
  const verdict = await evaluate({ github, context: contextFor("/oc review\n\nReview this PR."), core });
  assert.strictEqual(verdict.run, true);
  assert.strictEqual(verdict.reason, "ok");
});

test("eval: two dispatches of the same payload produce one run and one no-op", async () => {
  const body = "/oc review\n\n<!-- MODEL: opencode/deepseek-v4.1-flash-free --> second opinion";
  const first = 42371327663;
  const second = 42371366017;
  const runs = [{ id: first, display_title: runNameMarker(COMMENT_ID) + " Issue #849 - title" }];

  const firstVerdict = await evaluate({
    github: mockGithub(runs),
    context: contextFor(body, { runId: first }),
    core: mockCore()
  });
  const secondVerdict = await evaluate({
    github: mockGithub(runs.concat([{ id: second, display_title: runNameMarker(COMMENT_ID) + " Issue #849 - title" }])),
    context: contextFor(body, { runId: second }),
    core: mockCore()
  });

  assert.strictEqual(firstVerdict.run, true, "the first dispatch runs");
  assert.strictEqual(secondVerdict.run, false, "the redelivery no-ops");
  assert.strictEqual(secondVerdict.reason, "duplicate-delivery");
});

test("eval: a quoted /oc self-retrigger never reaches the runs API", async () => {
  let called = false;
  const github = { rest: { actions: { listWorkflowRuns: async () => { called = true; return { data: { workflow_runs: [] } }; } } } };
  const verdict = await evaluate({
    github,
    context: contextFor("## Re-review - #848 / PR #849 (after /oc fix)"),
    core: mockCore()
  });
  assert.strictEqual(verdict.run, false);
  assert.strictEqual(verdict.reason, "not-a-command");
  assert.strictEqual(called, false, "a non-command must not cost an API call");
});

test("eval: an untrusted author is rejected", async () => {
  const context = contextFor("/oc fix");
  context.payload.comment.author_association = "CONTRIBUTOR";
  const verdict = await evaluate({ github: mockGithub([]), context, core: mockCore() });
  assert.strictEqual(verdict.run, false);
  assert.strictEqual(verdict.reason, "untrusted-author");
});

test("eval: a runs API failure fails open (runs the agent)", async () => {
  const core = mockCore();
  const github = { rest: { actions: { listWorkflowRuns: async () => { throw new Error("boom"); } } } };
  const verdict = await evaluate({ github, context: contextFor("/oc fix"), core });
  assert.strictEqual(verdict.run, true);
  assert.strictEqual(core.logs.warning.length, 1);
});

test("workflow wiring: the gate is anchored, deduped and concurrency-guarded", () => {
  const text = fs.readFileSync(WORKFLOW, "utf8");

  assert.match(text, /^on:/m, "the workflow still has a trigger block");
  assert.match(text, /issue_comment:\s*\n\s*types: \[created\]/, "scope: issues and PR comments only");
  assert.match(text, /pull_request_review_comment:\s*\n\s*types: \[created\]/, "review comments still trigger");

  assert.match(text, /startsWith\(github\.event\.comment\.body, '\/oc '\)/, "if-gate anchors /oc at the start");
  assert.doesNotMatch(text, /contains\(github\.event\.comment\.body, ' \/oc '\)/, "the mid-line /oc match must be gone");
  assert.doesNotMatch(text, /contains\(github\.event\.comment\.body, ' \/oc (triage|approve)'\)/, "exclusions are anchored too");

  assert.match(text, /^concurrency:/m, "workflow has a concurrency group");
  assert.match(text, /cancel-in-progress: false/, "queued work is not cancelled mid-flight");
  assert.match(text, /github\.event\.comment\.id/, "the group separates comments");

  assert.match(text, /run-name:.*\[oc:\$\{\{\s*github\.event\.comment\.id\s*\}\}\]/, "run-name carries the comment id");

  assert.match(text, /require\('\.\/\.github\/scripts\/trigger-gate\.js'\)/, "the gate step uses the tested helper");
  assert.match(text, /needs: gate/, "the agent job waits for the gate");
  assert.match(text, /if: needs\.gate\.outputs\.run == 'true'/, "the agent job runs only on an explicit gate pass");
});

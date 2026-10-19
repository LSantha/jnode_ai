const test = require("node:test");
const assert = require("node:assert");

const watchdog = require("../watchdog.js");
const { _refsNumber, _lastActivity } = watchdog;

const ENV = {
  WATCHDOG_DRY_RUN: "0",
  WATCHDOG_DEV_TIMEOUT_MIN: "40",
  WATCHDOG_REVIEW_TIMEOUT_MIN: "45",
  WATCHDOG_ORCH_TIMEOUT_MIN: "45",
  WATCHDOG_CI_SETTLE_MIN: "10",
  WATCHDOG_AGENT_SETTLE_MIN: "10",
  WATCHDOG_COOLDOWN_MIN: "30",
  WATCHDOG_MAX_ATTEMPTS: "3",
  WATCHDOG_MAX_ACTIONS: "20",
  WATCHDOG_LOCK_MIN: "25"
};

const SHA = "abcdef1234567";
const SHORT = SHA.slice(0, 7);

function iso(minsAgo) {
  return new Date(Date.now() - minsAgo * 60000).toISOString();
}

function ticketBody(state) {
  return "Issue body\n\n<!-- TICKET_RUNNER_STATE:\n" + JSON.stringify(state, null, 2) + "\n-->";
}

function orchBody(state) {
  return "Master\n\n<!-- ORCHESTRATOR_STATE:\n" + JSON.stringify(state, null, 2) + "\n-->";
}

function state(over) {
  return Object.assign({
    phase: "DEV", pr: null, turn: 0, max_turns: 3, retries: 0,
    review_in_progress: false, review_pending_ci: false,
    started: iso(120), history: [{ event: "start", timestamp: iso(120) }]
  }, over || {});
}

function issue(number, body, over) {
  return Object.assign({
    number: number, body: body, labels: [], state: "open",
    user: { login: "LSantha", type: "User" }, updated_at: iso(60)
  }, over || {});
}

function mock({ issues = [], runs = {}, comments = {}, prSha = SHA, prState = "open" } = {}) {
  const created = [];
  const removed = [];
  const github = {
    paginate: async (fn, params) => (await fn(params)).data,
    rest: {
      issues: {
        listForRepo: async ({ page }) => ({ data: page === 1 ? issues : [] }),
        listComments: async ({ issue_number }) => ({ data: comments[issue_number] || [] }),
        createComment: async ({ issue_number, body }) => {
          created.push({ issue_number: issue_number, body: body });
          (comments[issue_number] = comments[issue_number] || [])
            .push({ body: body, created_at: new Date().toISOString() });
        },
        removeLabel: async ({ issue_number, name }) => {
          removed.push({ issue_number: issue_number, name: name });
        }
      },
      pulls: {
        get: async ({ pull_number }) => ({
          data: {
            number: pull_number, state: prState, merged: prState === "closed",
            head: { sha: prSha }
          }
        })
      },
      actions: {
        listWorkflowRuns: async ({ workflow_id }) => ({
          data: { workflow_runs: runs[workflow_id] || [] }
        })
      }
    }
  };
  const context = { repo: { owner: "o", repo: "r" } };
  const core = { info: () => {}, warning: () => {}, error: () => {} };
  return { github: github, context: context, core: core, created: created, removed: removed };
}

function ci(status, conclusion, over) {
  return Object.assign({
    status: status, conclusion: conclusion, head_sha: SHA,
    updated_at: iso(20), html_url: "https://example/run"
  }, over || {});
}

test("refsNumber matches a standalone issue reference only", () => {
  assert.equal(_refsNumber("Issue #42 - fix", 42), true);
  assert.equal(_refsNumber("PR #42 - fix", 42), true);
  assert.equal(_refsNumber("Issue #420 - fix", 42), false);
  assert.equal(_refsNumber("", 42), false);
});

test("lastActivity uses the newest history timestamp", () => {
  const t = _lastActivity({
    started: iso(200),
    history: [{ timestamp: iso(100) }, { timestamp: iso(10) }]
  });
  assert.ok(Date.now() - t < 11 * 60000);
});

test("skips a ticket with an agent run in flight", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state()))],
    runs: { "opencode.yml": [{ status: "in_progress", display_title: "Issue #1 - fix" }] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("skips a ticket whose agent run just finished", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state()))],
    runs: {
      "opencode.yml": [{ status: "completed", display_title: "Issue #1 - fix", updated_at: iso(2) }]
    }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("nudges a DEV ticket idle beyond the timeout", async () => {
  const m = mock({ issues: [issue(1, ticketBody(state()))] });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 1);
  assert.match(m.created[0].body, /^\/run\b/);
  assert.equal(m.created[0].issue_number, 1);
});

test("leaves HUMAN_REVIEW alone", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "HUMAN_REVIEW", pr: 99 })))]
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("leaves MERGE alone (the /run escape hatch would post an agent prompt)", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "MERGE", pr: 99 })))]
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("respects no-auto and blocking agent labels", async () => {
  for (const label of ["no-auto", "agent/failed", "agent/needs-info", "agent/blocked"]) {
    const m = mock({
      issues: [issue(1, ticketBody(state()), { labels: [{ name: label }] })]
    });
    await watchdog(Object.assign(m, { env: ENV }));
    assert.equal(m.created.length, 0, "label " + label);
  }
});

test("ignores untrusted authors", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state()), { user: { login: "stranger", type: "User" } })]
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("green CI with a pending review posts /run on the ticket", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("completed", "success")] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 1);
  assert.equal(m.created[0].issue_number, 1);
  assert.match(m.created[0].body, /^\/run\b/);
});

test("in-flight CI blocks a REVIEW ticket", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("in_progress", null)] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("recently completed CI is left to the normal wakeup", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("completed", "failure", { updated_at: iso(2) })] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("red CI with no heal posts /oc fix on the PR with the CI-HEAL marker", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("completed", "failure")] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 1);
  assert.equal(m.created[0].issue_number, 99);
  assert.match(m.created[0].body, /^\/oc fix\b/);
  assert.match(m.created[0].body, new RegExp("<!-- CI-HEAL:" + SHORT + " -->"));
});

test("red CI with an existing heal does not double-post", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("completed", "failure")] },
    comments: { 99: [{ body: "<!-- CI-HEAL:" + SHORT + " -->" }] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("dedupes a second sweep for the same unchanged state", async () => {
  const m = mock({ issues: [issue(1, ticketBody(state()))] });
  await watchdog(Object.assign(m, { env: ENV }));
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 1);
});

test("stops after the attempt cap for the same state", async () => {
  const prefix = "<!-- WATCHDOG:ticket:1:DEV:0:";
  const comments = {
    1: [
      { body: prefix + "a -->", created_at: iso(120) },
      { body: prefix + "b -->", created_at: iso(110) },
      { body: prefix + "c -->", created_at: iso(100) }
    ]
  };
  const m = mock({ issues: [issue(1, ticketBody(state()))], comments: comments });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("attempt cap is per state, not per issue lifetime", async () => {
  const comments = {
    1: [
      { body: "<!-- WATCHDOG:ticket:1:REVIEW:0:a -->", created_at: iso(400) },
      { body: "<!-- WATCHDOG:ticket:1:FEEDBACK:0:a -->", created_at: iso(300) },
      { body: "<!-- WATCHDOG:ticket:1:REVIEW:0:b -->", created_at: iso(200) }
    ]
  };
  const m = mock({ issues: [issue(1, ticketBody(state()))], comments: comments });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 1);
});

test("gate reads the full comment history via paginate, not just page one", async () => {
  const m = mock({ issues: [issue(1, ticketBody(state()))], comments: { 1: [] } });
  m.github.paginate = async () =>
    [{ body: "<!-- WATCHDOG:ticket:1:DEV:0:old -->", created_at: iso(5) }];
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("heals on failure but not on other non-success conclusions", async () => {
  for (const conclusion of ["cancelled", "skipped", "neutral", "stale"]) {
    const m = mock({
      issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
      runs: { "ant.yml": [ci("completed", conclusion)] }
    });
    await watchdog(Object.assign(m, { env: ENV }));
    assert.equal(m.created.length, 0, "conclusion " + conclusion);
  }
});

test("master attempt cap is per child task and phase", async () => {
  const prefix = "<!-- WATCHDOG:master:1:5:DEV:";
  const comments = {
    1: [
      { body: prefix + "a -->", created_at: iso(120) },
      { body: prefix + "b -->", created_at: iso(110) },
      { body: prefix + "c -->", created_at: iso(100) }
    ]
  };
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV", turn: 0, max_turns: 3, retries: 0, review_in_progress: false },
    started: iso(120), history: [{ timestamp: iso(120) }]
  }), { labels: [{ name: "kind/orchestrator" }] });
  const m = mock({ issues: [master], comments: comments });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("non-numeric timeout env falls back instead of disabling the check", () => {
  const cfg = watchdog._config({ WATCHDOG_DEV_TIMEOUT_MIN: "abc", WATCHDOG_REVIEW_TIMEOUT_MIN: "" });
  assert.equal(cfg.devTimeout, 40);
  assert.equal(cfg.reviewTimeout, 45);
});

test("dry-run posts nothing", async () => {
  const m = mock({ issues: [issue(1, ticketBody(state()))] });
  await watchdog(Object.assign(m, { env: Object.assign({}, ENV, { WATCHDOG_DRY_RUN: "1" }) }));
  assert.equal(m.created.length, 0);
});

test("removes a leaked orchestrator lock when nothing is running", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV", turn: 0, max_turns: 3, retries: 0, review_in_progress: false },
    started: iso(120), history: [{ timestamp: iso(120) }]
  }), {
    labels: [{ name: "kind/orchestrator" }, { name: "orchestrator/locked" }],
    updated_at: iso(60)
  });
  const m = mock({ issues: [master] });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.deepEqual(m.removed, [{ issue_number: 1, name: "orchestrator/locked" }]);
});

test("leaves a recent lock alone", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV" }
  }), {
    labels: [{ name: "kind/orchestrator" }, { name: "orchestrator/locked" }],
    updated_at: iso(5)
  });
  const m = mock({ issues: [master] });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.removed.length, 0);
});

test("leaves a lock alone while the orchestrator is running", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV" }
  }), {
    labels: [{ name: "kind/orchestrator" }, { name: "orchestrator/locked" }],
    updated_at: iso(60)
  });
  const m = mock({
    issues: [master],
    runs: { "orchestrator.yml": [{ status: "in_progress" }] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.removed.length, 0);
});

test("nudges an idle orchestrator task with /orchestrate", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV", turn: 0, max_turns: 3, retries: 0, review_in_progress: false },
    started: iso(120), history: [{ timestamp: iso(120) }]
  }), { labels: [{ name: "kind/orchestrator" }] });
  const m = mock({ issues: [master] });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 1);
  assert.match(m.created[0].body, /^\/orchestrate\b/);
});

test("an in-flight orchestrator run blocks the master path", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV" },
    started: iso(120), history: [{ timestamp: iso(120) }]
  }), { labels: [{ name: "kind/orchestrator" }] });
  const m = mock({
    issues: [master],
    runs: { "orchestrator.yml": [{ status: "queued" }] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("master path respects no-auto and blocking agent labels", async () => {
  for (const label of ["no-auto", "watchdog/paused", "agent/failed", "agent/blocked"]) {
    const master = issue(1, orchBody({
      status: "IN_PROGRESS",
      current_task: { issue: 5, phase: "DEV", turn: 0, max_turns: 3, retries: 0, review_in_progress: false },
      started: iso(120), history: [{ timestamp: iso(120) }]
    }), { labels: [{ name: "kind/orchestrator" }, { name: label }] });
    const m = mock({ issues: [master] });
    await watchdog(Object.assign(m, { env: ENV }));
    assert.equal(m.created.length, 0, "label " + label);
  }
});

test("an in-flight child agent run blocks the master path", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, phase: "DEV", turn: 0, max_turns: 3, retries: 0, review_in_progress: false },
    started: iso(120), history: [{ timestamp: iso(120) }]
  }), { labels: [{ name: "kind/orchestrator" }] });
  const m = mock({
    issues: [master],
    runs: { "opencode.yml": [{ status: "in_progress", display_title: "Issue #5 - fix" }] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("in-flight child PR CI blocks the master path", async () => {
  const master = issue(1, orchBody({
    status: "IN_PROGRESS",
    current_task: { issue: 5, pr: 99, phase: "REVIEW", turn: 0, max_turns: 3, retries: 0, review_in_progress: false },
    started: iso(120), history: [{ timestamp: iso(120) }]
  }), { labels: [{ name: "kind/orchestrator" }] });
  const m = mock({
    issues: [master],
    runs: { "ant.yml": [ci("in_progress", null)] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("heal cooldown reads the PR's markers", async () => {
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("completed", "failure")] },
    comments: { 99: [{ body: "<!-- WATCHDOG:ticket:1:REVIEW:99:old -->", created_at: iso(5) }] }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

test("heal attempt cap reads the PR's markers", async () => {
  const prefix = "<!-- WATCHDOG:ticket:1:REVIEW:99:";
  const m = mock({
    issues: [issue(1, ticketBody(state({ phase: "REVIEW", pr: 99, review_pending_ci: true })))],
    runs: { "ant.yml": [ci("completed", "failure")] },
    comments: {
      99: [
        { body: prefix + "a -->", created_at: iso(120) },
        { body: prefix + "b -->", created_at: iso(110) },
        { body: prefix + "c -->", created_at: iso(100) }
      ]
    }
  });
  await watchdog(Object.assign(m, { env: ENV }));
  assert.equal(m.created.length, 0);
});

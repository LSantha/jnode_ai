const test = require("node:test");
const assert = require("node:assert");

const runTicketRunner = require("../ticket-runner.js");
const createHelpers = require("../orchestrator-helpers.js");
const {
  _parseState,
  _initState,
  _serializeState,
  _replaceOrAppendState,
  _replaceOrAppendStatus,
  _renderStatusSection
} = runTicketRunner;

function createMocks(eventName, {
  commentBody = "/run",
  authorAssociation = "COLLABORATOR",
  isPR = false,
  issueNumber = 42,
  issueBody = "Original issue body description",
  issueLabels = [{ name: "kind/bug" }],
  runDisplayTitle = "Issue #42 - Fix bug",
  runConclusion = "success",
  prNumber = 99,
  prBody = "Closes #42",
  prLabels = [],
  reviewUser = { login: "LSantha", type: "User" },
  reviewAssociation = "OWNER",
  reviewState = "approved",
  orchestratorMasters = []
} = {}) {
  const logs = { info: [], error: [], warn: [] };
  const calls = {
    getIssue: [],
    updateIssue: [],
    createComment: [],
    addLabels: [],
    mergePR: [],
    listComments: []
  };
  const updateIssueDetails = [];

  let currentIssueBody = issueBody;
  let currentIssueLabels = [...issueLabels];
  let currentIssueState = "open";

  let currentPRBody = prBody;
  let currentPRLabels = [...prLabels];
  let currentPRHead = { ref: "opencode/issue42-fix" };

  let commentsOnPR = [];
  let masterIssues = [...orchestratorMasters];

  const core = {
    info: (msg) => logs.info.push(msg),
    error: (msg) => logs.error.push(msg),
    warning: (msg) => logs.warn.push(msg)
  };

  const github = {
    rest: {
      issues: {
        get: async ({ issue_number }) => {
          calls.getIssue.push(issue_number);
          if (issue_number === issueNumber) {
            return {
              data: {
                number: issueNumber,
                body: currentIssueBody,
                labels: currentIssueLabels,
                state: currentIssueState
              }
            };
          }
          if (issue_number === prNumber) {
            return {
              data: {
                number: prNumber,
                body: currentPRBody,
                labels: currentPRLabels,
                state: "open",
                pull_request: {}
              }
            };
          }
          return { data: { number: issue_number, body: "", labels: [], state: "open" } };
        },
        update: async ({ issue_number, body, state }) => {
          calls.updateIssue.push(issue_number);
          updateIssueDetails.push({ issue_number, body, state });
          if (issue_number === issueNumber) {
            if (body !== undefined) currentIssueBody = body;
            if (state !== undefined) currentIssueState = state;
          }
        },
        createComment: async ({ issue_number, body }) => {
          calls.createComment.push({ issue_number, body });
          if (issue_number === prNumber) {
            commentsOnPR.push({ body });
          }
        },
        listComments: async ({ issue_number }) => {
          calls.listComments.push(issue_number);
          if (issue_number === prNumber) {
            return { data: commentsOnPR };
          }
          return { data: [] };
        },
        addLabels: async ({ issue_number, labels }) => {
          calls.addLabels.push({ issue_number, labels });
          if (issue_number === issueNumber) {
            for (const l of labels) currentIssueLabels.push({ name: l });
          }
        },
        listForRepo: async (params) => {
          if (params && params.labels === "kind/orchestrator") {
            return { data: masterIssues };
          }
          return {
            data: [
              {
                number: issueNumber,
                body: currentIssueBody,
                labels: currentIssueLabels,
                state: currentIssueState
              }
            ]
          };
        }
      },
      pulls: {
        list: async () => {
          return {
            data: [
              {
                number: prNumber,
                head: currentPRHead,
                body: currentPRBody,
                state: "open"
              }
            ]
          };
        },
        get: async ({ pull_number }) => {
          return {
            data: {
              number: pull_number,
              head: currentPRHead,
              body: currentPRBody
            }
          };
        },
        merge: async ({ pull_number }) => {
          calls.mergePR.push(pull_number);
        }
      },
      git: {
        deleteRef: async () => {}
      }
    }
  };

  const context = {
    eventName,
    repo: { owner: "test", repo: "test" },
    payload: {}
  };

  if (eventName === "issue_comment") {
    context.payload = {
      issue: {
        number: issueNumber,
        pull_request: isPR ? {} : undefined
      },
      comment: {
        body: commentBody,
        author_association: authorAssociation
      }
    };
  } else if (eventName === "workflow_run") {
    context.payload = {
      workflow_run: {
        id: 1001,
        display_title: runDisplayTitle,
        conclusion: runConclusion
      }
    };
  } else if (eventName === "pull_request_review") {
    context.payload = {
      pull_request: {
        number: prNumber
      },
      review: {
        user: reviewUser,
        author_association: reviewAssociation,
        state: reviewState
      }
    };
  }

  return {
    core,
    github,
    context,
    logs,
    calls,
    updateIssueDetails,
    setIssueBody: (b) => { currentIssueBody = b; },
    setIssueLabels: (l) => { currentIssueLabels = l; },
    setCommentsOnPR: (c) => { commentsOnPR = c; },
    getIssueBody: () => currentIssueBody
  };
}

test("ticket-runner.js internal utilities", async (t) => {
  await t.test("_initState defaults and custom max_turns", () => {
    const s1 = _initState();
    assert.strictEqual(s1.phase, "DEV");
    assert.strictEqual(s1.turn, 0);
    assert.strictEqual(s1.max_turns, 3);
    assert.strictEqual(s1.retries, 0);
    assert.strictEqual(s1.pr, null);
    assert.deepStrictEqual(s1.history, []);

    const s2 = _initState(5);
    assert.strictEqual(s2.max_turns, 5);
  });

  await t.test("_parseState and _serializeState", () => {
    const state = { phase: "REVIEW", pr: 12, turn: 1, max_turns: 3, retries: 0 };
    const serialized = _serializeState(state);
    assert.ok(serialized.startsWith("<!-- TICKET_RUNNER_STATE:\n"));
    assert.ok(serialized.endsWith("\n-->"));

    const parsed = _parseState(serialized);
    assert.deepStrictEqual(parsed, state);

    assert.strictEqual(_parseState("No state here"), null);
    assert.strictEqual(_parseState(null), null);
    assert.strictEqual(_parseState("<!-- TICKET_RUNNER_STATE:\ninvalid json\n-->"), null);
  });

  await t.test("_replaceOrAppendState appends and updates state", () => {
    const s1 = { phase: "DEV" };
    const b1 = _replaceOrAppendState("Description", s1);
    assert.ok(b1.includes("Description"));
    assert.deepStrictEqual(_parseState(b1), s1);

    const s2 = { phase: "REVIEW" };
    const b2 = _replaceOrAppendState(b1, s2);
    assert.deepStrictEqual(_parseState(b2), s2);
  });

  await t.test("_replaceOrAppendStatus updates both status section and state block (LF and CRLF)", () => {
    const origBody = "My issue description";
    const s1 = _initState();
    const body1 = _replaceOrAppendStatus(origBody, s1, 42);

    assert.ok(body1.includes("My issue description"));
    assert.ok(body1.includes("### 🔨 Ticket Runner Status"));
    assert.ok(body1.includes("**Phase** | DEV"));
    assert.strictEqual(_parseState(body1).phase, "DEV");

    // Transition state
    s1.phase = "REVIEW";
    s1.pr = 99;
    s1.turn = 1;
    const body2 = _replaceOrAppendStatus(body1, s1, 42);

    assert.ok(body2.includes("My issue description"));
    assert.ok(body2.includes("### 🔍 Ticket Runner Status"));
    assert.ok(body2.includes("**Phase** | REVIEW"));
    assert.ok(body2.includes("**PR** | #99"));
    assert.strictEqual(_parseState(body2).phase, "REVIEW");
    assert.strictEqual(_parseState(body2).pr, 99);

    // Make sure we did not duplicate sections
    const matches = body2.match(/### [^\n]* Ticket Runner Status/g);
    assert.strictEqual(matches.length, 1);

    // CRLF verification
    const crlfBody = body2.replace(/\n/g, "\r\n");
    s1.phase = "FEEDBACK";
    const body3 = _replaceOrAppendStatus(crlfBody, s1, 42);
    assert.strictEqual(_parseState(body3).phase, "FEEDBACK");
    assert.ok(body3.includes("FEEDBACK"));
    assert.ok(!body3.includes("REVIEW"));
  });
});

test("orchestrator-helpers utilities", async (t) => {
  const h = createHelpers({
    github: {
      rest: {
        issues: {
          listComments: async () => ({
            data: [
              { body: "First comment" },
              { body: "**Verdict:** approve" }
            ]
          }),
          get: async ({ issue_number }) => ({
            data: { labels: issue_number === 99 ? [{ name: "auto-merge" }] : [] }
          })
        }
      }
    },
    context: { repo: { owner: "test", repo: "test" } },
    core: { info: () => {} }
  });

  await t.test("getAgentReviewVerdict handles markdown bold and capitalization", async () => {
    const verdict = await h.getAgentReviewVerdict(99);
    assert.strictEqual(verdict, "approve");
  });

  await t.test("needsHumanReview checks both issue and PR labels", async () => {
    // PR 99 has auto-merge, issue 42 does not
    const requiresReview = await h.needsHumanReview(42, 99);
    assert.strictEqual(requiresReview, false, "PR auto-merge label should be recognized");
  });

  await t.test("isBotUser behavior", () => {
    assert.strictEqual(h.isBotUser(null), false, "null user is not a bot");
    assert.strictEqual(h.isBotUser({ login: "user1", type: "User" }), false);
    assert.strictEqual(h.isBotUser({ login: "app[bot]", type: "Bot" }), true);
    assert.strictEqual(h.isBotUser({ login: "opencode-agent[bot]" }), true);
  });
});

test("ticket-runner.js event handling suite", async (t) => {
  await t.test("Initialization on /run creates state and triggers /oc", async () => {
    const mocks = createMocks("issue_comment", { commentBody: "/run" });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 1);
    assert.strictEqual(mocks.calls.updateIssue[0], 42);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.strictEqual(mocks.calls.createComment[0].issue_number, 42);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc Please proceed"));

    const updatedBody = mocks.getIssueBody();
    const state = _parseState(updatedBody);
    assert.strictEqual(state.phase, "DEV");
    assert.strictEqual(state.max_turns, 3);
  });

  await t.test("Respects custom max-turns with /run --turns 5", async () => {
    const mocks = createMocks("issue_comment", { commentBody: "/run --turns 5" });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.max_turns, 5);
  });

  await t.test("Ignores comments that start with /running or /runaway", async () => {
    const mocks = createMocks("issue_comment", { commentBody: "/running tests now" });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 0);
    assert.strictEqual(mocks.calls.createComment.length, 0);
  });

  await t.test("Ignores /run from non-collaborator", async () => {
    const mocks = createMocks("issue_comment", {
      commentBody: "/run",
      authorAssociation: "NONE"
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 0);
    assert.strictEqual(mocks.calls.createComment.length, 0);
  });

  await t.test("Rejects /run on PR with explanatory comment", async () => {
    const mocks = createMocks("issue_comment", {
      commentBody: "/run",
      isPR: true
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 0);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("/run` is designed for issues only"));
  });

  await t.test("Refuses /run if issue is the orchestrator master issue", async () => {
    const mocks = createMocks("issue_comment", {
      commentBody: "/run",
      issueBody: "<!-- ORCHESTRATOR_STATE:\n{}\n-->"
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 0);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("orchestrator master issue"));
  });

  await t.test("Refuses /run if issue is a child in an active orchestrator queue", async () => {
    const masterIssue = {
      number: 100,
      labels: [{ name: "kind/orchestrator" }],
      body: "<!-- ORCHESTRATOR_STATE:\n{\"status\":\"IN_PROGRESS\",\"current_task\":42,\"queue\":[43]}\n-->"
    };

    const mocks = createMocks("issue_comment", {
      commentBody: "/run",
      issueNumber: 42,
      orchestratorMasters: [masterIssue]
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 0);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("managed by the batch orchestrator (master issue #100)"));
  });

  await t.test("Re-triggering when in HUMAN_REVIEW informs user instead of /oc", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "HUMAN_REVIEW",
      pr: 99,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("issue_comment", {
      commentBody: "/run",
      issueBody: initialBody
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("waiting for human approval on PR #99"));
  });

  await t.test("/run --reset restarts even if in progress", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "FEEDBACK",
      pr: 99,
      turn: 1,
      max_turns: 3,
      retries: 1,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("issue_comment", {
      commentBody: "/run --reset",
      issueBody: initialBody
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DEV");
    assert.strictEqual(state.turn, 0);
    assert.strictEqual(state.pr, null);
  });

  await t.test("Workflow run advances DEV -> REVIEW when agent/done and PR exists", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "DEV",
      pr: null,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      issueLabels: [{ name: "agent/done" }, { name: "kind/bug" }]
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "REVIEW");
    assert.strictEqual(state.pr, 99);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.strictEqual(mocks.calls.createComment[0].issue_number, 99);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc review"));
  });

  await t.test("Workflow run retries on DEV failure", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "DEV",
      pr: null,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runConclusion: "failure"
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DEV");
    assert.strictEqual(state.retries, 1);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.strictEqual(mocks.calls.createComment[0].issue_number, 42);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc Please proceed"));
  });

  await t.test("DEV fails permanently after 3 retries", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "DEV",
      pr: null,
      turn: 0,
      max_turns: 3,
      retries: 2,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runConclusion: "failure"
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "FAILED");
    assert.ok(mocks.calls.addLabels.some(l => l.labels.includes("agent/failed")));
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("failed after 3 retries")));
  });

  await t.test("Short-circuit label completes the ticket", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "DEV",
      pr: null,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      issueLabels: [{ name: "agent/skip" }]
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DONE");
  });

  await t.test("REVIEW phase approve -> HUMAN_REVIEW when no auto-merge", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "REVIEW",
      pr: 99,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runDisplayTitle: "Issue #99 - PR",
      issueLabels: [{ name: "kind/bug" }]
    });
    mocks.setCommentsOnPR([{ body: "LGTM! Verdict: approve" }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "HUMAN_REVIEW");
    assert.ok(mocks.calls.createComment.some(c => c.issue_number === 99 && c.body.includes("Awaiting human approval")));
  });

  await t.test("REVIEW phase approve -> MERGE when auto-merge label present", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "REVIEW",
      pr: 99,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runDisplayTitle: "Issue #99 - PR",
      issueLabels: [{ name: "kind/bug" }, { name: "auto-merge" }]
    });
    mocks.setCommentsOnPR([{ body: "Verdict: approve" }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DONE");
    assert.deepStrictEqual(mocks.calls.mergePR, [99]);
    assert.ok(mocks.calls.addLabels.some(l => l.labels.includes("agent/done")));
  });

  await t.test("REVIEW phase request-changes -> FEEDBACK", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "REVIEW",
      pr: 99,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runDisplayTitle: "Issue #99 - PR"
    });
    mocks.setCommentsOnPR([{ body: "Needs fixes.\nVerdict: request-changes" }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "FEEDBACK");
    assert.strictEqual(state.turn, 1);
    assert.ok(mocks.calls.createComment.some(c => c.issue_number === 99 && c.body.includes("/oc fix Address review feedback.")));
  });

  await t.test("REVIEW phase request-changes exceeding max_turns -> FAILED", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "REVIEW",
      pr: 99,
      turn: 3,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runDisplayTitle: "Issue #99 - PR"
    });
    mocks.setCommentsOnPR([{ body: "Still buggy.\nVerdict: request-changes" }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "FAILED");
    assert.ok(mocks.calls.addLabels.some(l => l.labels.includes("agent/failed")));
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("exceeded max turns")));
  });

  await t.test("FEEDBACK completion advances back to REVIEW", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "FEEDBACK",
      pr: 99,
      turn: 1,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runDisplayTitle: "Issue #99 - PR"
    });

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "REVIEW");
    assert.ok(mocks.calls.createComment.some(c => c.issue_number === 99 && c.body.includes("/oc review")));
  });

  await t.test("Human PR review approval merges PR and closes issue", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "HUMAN_REVIEW",
      pr: 99,
      turn: 1,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("pull_request_review", {
      issueBody: initialBody,
      reviewState: "approved"
    });

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DONE");
    assert.deepStrictEqual(mocks.calls.mergePR, [99]);
    assert.ok(mocks.calls.addLabels.some(l => l.labels.includes("agent/done")));
  });

  await t.test("Human PR review changes_requested moves to FEEDBACK", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "HUMAN_REVIEW",
      pr: 99,
      turn: 1,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("pull_request_review", {
      issueBody: initialBody,
      reviewState: "changes_requested"
    });

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "FEEDBACK");
    assert.strictEqual(state.turn, 2);
    assert.ok(mocks.calls.createComment.some(c => c.issue_number === 99 && c.body.includes("/oc fix Address human review feedback.")));
  });

  await t.test("Bot PR review is ignored", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "HUMAN_REVIEW",
      pr: 99,
      turn: 1,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("pull_request_review", {
      issueBody: initialBody,
      reviewUser: { login: "opencode-agent[bot]", type: "Bot" },
      reviewState: "approved"
    });

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "HUMAN_REVIEW");
    assert.strictEqual(mocks.calls.mergePR.length, 0);
  });

  await t.test("Non-collaborator PR review is ignored", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "HUMAN_REVIEW",
      pr: 99,
      turn: 1,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: []
    }, 42);

    const mocks = createMocks("pull_request_review", {
      issueBody: initialBody,
      reviewUser: { login: "random-user", type: "User" },
      reviewAssociation: "NONE",
      reviewState: "approved"
    });

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "HUMAN_REVIEW");
    assert.strictEqual(mocks.calls.mergePR.length, 0);
  });
});

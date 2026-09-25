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
  runName = "opencode",
  runHeadSha = "abc123",
  labelName = "kind/bug",
  prNumber = 99,
  prBody = "Closes #42",
  prLabels = [],
  prHeadRef = "opencode/issue42-fix",
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
  let currentPRHead = { ref: prHeadRef, sha: "abc123" };

  let commentsOnPR = [];
  let commentsOnIssue = [];
  let prFiles = [{ filename: "fs/src/fs/org/jnode/fs/jfat/FatChain.java", additions: 10 }];
  let checkRuns = [{ name: "test", status: "completed", conclusion: "success" }];
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
          return { data: commentsOnIssue };
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
        },
        listFiles: async () => {
          return { data: prFiles };
        }
      },
      checks: {
        listForRef: async () => {
          return { data: { check_runs: checkRuns } };
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
        name: runName,
        head_sha: runHeadSha,
        display_title: runDisplayTitle,
        conclusion: runConclusion
      }
    };
  } else if (eventName === "issues") {
    context.payload = {
      issue: {
        number: issueNumber
      },
      label: {
        name: labelName
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
    setIssueComments: (c) => { commentsOnIssue = c; },
    setPRFiles: (f) => { prFiles = f; },
    setCheckRuns: (r) => { checkRuns = r; },
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
    assert.strictEqual(s1.review_in_progress, false);
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

test("merge safety gate helpers", async (t) => {
  function makeHelpers({ issueLabels = [], prLabels = [], files = [], runs = [], sha = "abc", prs = [] } = {}) {
    const gh = {
      rest: {
        issues: {
          get: async ({ issue_number }) => ({
            data: { labels: issue_number === 99 ? prLabels : issueLabels }
          }),
          listComments: async () => ({ data: [] })
        },
      pulls: {
        get: async () => ({ data: { head: { sha } } }),
        list: async () => ({ data: prs }),
        listFiles: async () => ({ data: files })
      },
        checks: {
          listForRef: async () => ({ data: { check_runs: runs } })
        }
      }
    };
    return createHelpers({ github: gh, context: { repo: { owner: "t", repo: "t" } }, core: { info: () => {}, warning: () => {} } });
  }
  const ok = [{ name: "build", status: "completed", conclusion: "success" }];

  await t.test("isAutoMergeEligible: explicit, implicit, negative", async () => {
    assert.strictEqual(await makeHelpers({ issueLabels: [{ name: "kind/bug" }, { name: "auto-merge" }] }).isAutoMergeEligible(42, 99), true);
    assert.strictEqual(await makeHelpers({ issueLabels: [{ name: "kind/chore" }] }).isAutoMergeEligible(42, 99), true);
    assert.strictEqual(await makeHelpers({ issueLabels: [{ name: "kind/test" }] }).isAutoMergeEligible(42, 99), true);
    assert.strictEqual(await makeHelpers({ issueLabels: [{ name: "kind/bug" }] }).isAutoMergeEligible(42, 99), false);
  });

  await t.test("isDiffSafe: small ok, big/asm/config rejected", async () => {
    const hSmall = makeHelpers({ files: [{ filename: "fs/a.java", additions: 10 }] });
    assert.strictEqual(await hSmall.isDiffSafe(99), true);
    const hBig = makeHelpers({ files: [{ filename: "fs/a.java", additions: 101 }] });
    assert.strictEqual(await hBig.isDiffSafe(99), false);
    const hMany = makeHelpers({ files: [1, 2, 3, 4, 5, 6].map(i => ({ filename: "f" + i + ".java", additions: 1 })) });
    assert.strictEqual(await hMany.isDiffSafe(99), false);
    const hAsm = makeHelpers({ files: [{ filename: "core/src/native/x86/kernel.asm", additions: 1 }] });
    assert.strictEqual(await hAsm.isDiffSafe(99), false);
    const hCfg = makeHelpers({ files: [{ filename: "jnode.properties", additions: 1 }] });
    assert.strictEqual(await hCfg.isDiffSafe(99), false);
    const hEmpty = makeHelpers({ files: [] });
    assert.strictEqual(await hEmpty.isDiffSafe(99), false);
  });

  await t.test("isCIGreen: success, failure, none", async () => {
    assert.strictEqual(await makeHelpers({ runs: ok }).isCIGreen(99), true);
    assert.strictEqual(await makeHelpers({ runs: ok.concat([{ name: "boot", status: "completed", conclusion: "failure" }]) }).isCIGreen(99), false);
    assert.strictEqual(await makeHelpers({ runs: [] }).isCIGreen(99), false);
    assert.strictEqual(await makeHelpers({ runs: [{ name: "build", status: "in_progress", conclusion: null }] }).isCIGreen(99), false);
  });

  await t.test("findPRsForSHA matches head SHA", async () => {
    const h = makeHelpers({ prs: [{ number: 7, head: { sha: "abc" } }, { number: 8, head: { sha: "zzz" } }] });
    assert.deepStrictEqual(await h.findPRsForSHA("abc"), [7]);
    assert.deepStrictEqual(await h.findPRsForSHA("none"), []);
  });

  await t.test("getAgentReviewVerdict ignores trigger quoting verdicts", async () => {
    const gh = {
      rest: {
        issues: {
          listComments: async () => ({ data: [{ body: "/oc review\n\nFinal line must be exactly one of:\nVerdict: approve\nVerdict: request-changes" }] })
        }
      }
    };
    const h = createHelpers({ github: gh, context: { repo: { owner: "t", repo: "t" } }, core: { info: () => {}, warning: () => {} } });
    assert.strictEqual(await h.getAgentReviewVerdict(99), null);
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
    assert.strictEqual(state.review_in_progress, true);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.strictEqual(mocks.calls.createComment[0].issue_number, 99);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc review"));
  });

  await t.test("Workflow run advances DEV -> REVIEW from a PR comment when the pulls API is delayed", async () => {
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
      issueLabels: [{ name: "kind/chore" }],
      prHeadRef: "unrelated-branch",
      prBody: "unrelated"
    });
    mocks.setIssueComments([{ body: "Created PR #99" }]);
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "REVIEW");
    assert.strictEqual(state.pr, 99);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.strictEqual(mocks.calls.createComment[0].issue_number, 99);
  });

  await t.test("Workflow run does not short-circuit a DEV run with an existing PR and needs-info", async () => {
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
      issueLabels: [{ name: "kind/bug" }, { name: "agent/needs-info" }]
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "REVIEW");
    assert.strictEqual(state.pr, 99);
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
      runConclusion: "failure",
      prHeadRef: "unrelated-branch",
      prBody: "unrelated"
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
      runConclusion: "failure",
      prHeadRef: "unrelated-branch",
      prBody: "unrelated"
    });
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "FAILED");
    assert.ok(mocks.calls.addLabels.some(l => l.labels.includes("agent/failed")));
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("failed after 3 retries")));
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("phase DEV failed")));
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
      issueLabels: [{ name: "agent/skip" }],
      prHeadRef: "unrelated-branch",
      prBody: "unrelated"
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

  await t.test("issues:labeled actionable kind + clear triage auto-starts DEV", async () => {
    const mocks = createMocks("issues", {
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/bug" }, { name: "area/fs" }]
    });
    mocks.setIssueComments([
      { body: "## Triage\n\n- [x] **Repro:** 1. boot 2. mkdir\n- [x] **Suggested next:** fix" }
    ]);
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DEV");
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc Please proceed"));
  });

  await t.test("issues:labeled without triage requests triage first", async () => {
    const mocks = createMocks("issues", {
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/bug" }]
    });
    await runTicketRunner(mocks);

    assert.strictEqual(_parseState(mocks.getIssueBody()), null);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc triage"));
  });

  await t.test("issues:labeled skips vague triage waiter", async () => {
    const mocks = createMocks("issues", {
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/bug" }, { name: "agent/needs-info" }]
    });
    mocks.setIssueComments([
      { body: "## Triage\n\n- [ ] **Repro:** needs more info from reporter" }
    ]);
    await runTicketRunner(mocks);

    assert.strictEqual(_parseState(mocks.getIssueBody()), null);
    assert.strictEqual(mocks.calls.createComment.length, 0);
  });

  await t.test("issues:labeled ignores trigger quoting triage", async () => {
    const mocks = createMocks("issues", {
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/chore" }]
    });
    mocks.setIssueComments([
      { body: "/oc triage issue #42\n\nTriage run ONLY. Output is exactly one ## Triage comment plus kind/area label edits." }
    ]);
    await runTicketRunner(mocks);

    assert.strictEqual(_parseState(mocks.getIssueBody()), null);
    assert.strictEqual(mocks.calls.createComment.length, 1);
    assert.ok(mocks.calls.createComment[0].body.includes("/oc triage"));
    assert.ok(!mocks.calls.createComment[0].body.includes("Please proceed"));
  });

  await t.test("issues:labeled ignores non-actionable kind", async () => {
    const mocks = createMocks("issues", { labelName: "kind/question" });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.updateIssue.length, 0);
    assert.strictEqual(mocks.calls.createComment.length, 0);
  });

  await t.test("workflow_run with no state + clear triage auto-starts DEV", async () => {
    const mocks = createMocks("workflow_run", {
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/bug" }]
    });
    mocks.setIssueComments([
      { body: "## Triage\n\n- [x] **Repro:** 1. boot\n- [x] **Suggested next:** fix" }
    ]);
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DEV");
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("/oc Please proceed")));
  });

  await t.test("Triage workflow completion auto-starts DEV after the report lands", async () => {
    const mocks = createMocks("workflow_run", {
      runName: "Triage #42 - Bug description",
      runDisplayTitle: "Triage #42 - Bug description",
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/chore" }]
    });
    mocks.setIssueComments([
      { body: "## Triage\n\n- [x] **Suggested next:** fix" }
    ]);
    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DEV");
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("/oc Please proceed")));
  });

  await t.test("workflow_run with no state and no triage waits", async () => {
    const mocks = createMocks("workflow_run", {
      issueBody: "Bug description",
      issueLabels: [{ name: "kind/bug" }]
    });
    await runTicketRunner(mocks);

    assert.strictEqual(_parseState(mocks.getIssueBody()), null);
  });

  await t.test("REVIEW approve + auto-merge defers when CI red", async () => {
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
    mocks.setCheckRuns([{ name: "test", status: "completed", conclusion: "failure" }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "REVIEW");
    assert.strictEqual(mocks.calls.mergePR.length, 0);
    assert.ok(mocks.calls.createComment.some(c => c.body.includes("deferred")));
  });

  await t.test("REVIEW approve + implicit safe kind merges when green", async () => {
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
      issueLabels: [{ name: "kind/chore" }]
    });
    mocks.setCommentsOnPR([{ body: "Verdict: approve" }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "DONE");
    assert.deepStrictEqual(mocks.calls.mergePR, [99]);
  });

  await t.test("REVIEW approve defers when diff touches ASM", async () => {
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
    mocks.setPRFiles([{ filename: "core/src/native/x86/kernel.asm", additions: 2 }]);

    await runTicketRunner(mocks);

    const state = _parseState(mocks.getIssueBody());
    assert.strictEqual(state.phase, "REVIEW");
    assert.strictEqual(mocks.calls.mergePR.length, 0);
  });

  await t.test("Java CI success re-reviews deferred REVIEW", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "REVIEW",
      pr: 99,
      turn: 0,
      max_turns: 3,
      retries: 0,
      started: new Date().toISOString(),
      history: [{ event: "merge_deferred" }]
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runName: "Java CI",
      runConclusion: "success",
      issueLabels: [{ name: "kind/bug" }, { name: "auto-merge" }]
    });
    await runTicketRunner(mocks);

    assert.ok(mocks.calls.createComment.some(c => c.issue_number === 99 && c.body.includes("/oc review")));
  });

  await t.test("Java CI success does not re-review while a review is in progress", async () => {
    const initialBody = _replaceOrAppendStatus("Task", {
      phase: "REVIEW",
      pr: 99,
      turn: 0,
      max_turns: 3,
      retries: 0,
      review_in_progress: true,
      started: new Date().toISOString(),
      history: [{ event: "dev_done", pr: 99 }]
    }, 42);

    const mocks = createMocks("workflow_run", {
      issueBody: initialBody,
      runName: "Java CI",
      runConclusion: "success",
      issueLabels: [{ name: "kind/chore" }]
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.createComment.length, 0);
  });

  await t.test("Java CI failure posts one /oc fix per SHA", async () => {
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
      runName: "Java CI",
      runConclusion: "failure",
      issueLabels: [{ name: "kind/bug" }]
    });
    await runTicketRunner(mocks);
    await runTicketRunner(mocks);

    const fixes = mocks.calls.createComment.filter(c => c.issue_number === 99 && c.body.includes("/oc fix CI failed"));
    assert.strictEqual(fixes.length, 1, "marker dedupes the second identical run");
    assert.ok(fixes[0].body.includes("CI-HEAL:abc123"));
  });

  await t.test("Java CI success ignores FEEDBACK phase", async () => {
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
      runName: "Java CI",
      runConclusion: "success",
      issueLabels: [{ name: "kind/bug" }]
    });
    await runTicketRunner(mocks);

    assert.strictEqual(mocks.calls.createComment.length, 0);
  });
});

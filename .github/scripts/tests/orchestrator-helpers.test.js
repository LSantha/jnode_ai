const test = require("node:test");
const assert = require("node:assert");

const createHelpers = require("../orchestrator-helpers.js");

function createCore() {
  return { info: () => {}, warning: () => {}, error: () => {} };
}

function createGithub({ reviews = [], comments = [], commits = [], prBody = null } = {}) {
  const calls = { merge: [], listComments: [], listReviews: [], listCommits: [], deleteRef: [] };
  let getCount = 0;
  const github = {
    rest: {
      pulls: {
        get: async () => {
          getCount += 1;
          return {
            data: {
              number: 99,
              title: "#42 - Subject",
              head: { ref: "opencode/issue42-fix" },
              body: prBody,
              merged: getCount > 1
            }
          };
        },
        listReviews: async (args) => {
          calls.listReviews.push(args);
          if (reviews instanceof Error) throw reviews;
          return { data: reviews };
        },
        listCommits: async (args) => {
          calls.listCommits.push(args);
          if (commits instanceof Error) throw commits;
          return { data: commits };
        },
        merge: async (args) => {
          calls.merge.push(args);
          return { data: { sha: "deadbeef" } };
        }
      },
      issues: {
        listComments: async (args) => {
          calls.listComments.push(args);
          if (comments instanceof Error) throw comments;
          return { data: comments };
        }
      },
      git: {
        deleteRef: async (args) => {
          calls.deleteRef.push(args);
          return { data: {} };
        }
      }
    }
  };
  return { github, calls };
}

function helpersFor(opts) {
  const { github, calls } = createGithub(opts);
  const context = { repo: { owner: "LSantha", repo: "jnode_ai" } };
  return { h: createHelpers({ github, context, core: createCore() }), calls };
}

function commit(message) {
  return { commit: { message } };
}

test("collectReviewerModels gathers distinct models from review and comment bodies", async () => {
  const { h } = helpersFor({
    reviews: [{ body: "Looks good.\n\nAssisted-by: reviewer-a" }],
    comments: [
      { body: "Verdict: approve\nAssisted-by: reviewer-a" },
      { body: "Verdict: request-changes\nAssisted-by: reviewer-b" },
      { body: "No trailer here." }
    ]
  });
  assert.deepStrictEqual(await h.collectReviewerModels(99), ["reviewer-a", "reviewer-b"]);
});

test("collectReviewerModels reads the verdict comment even though it is not a review object", async () => {
  // The agent posts its summary with `gh pr comment`, and `/oc approve` is a
  // plain comment too, so neither ever appears in pulls.listReviews.
  const { h } = helpersFor({
    reviews: [],
    comments: [{ body: "Verdict: approve\nAssisted-by: only-in-a-comment" }]
  });
  assert.deepStrictEqual(await h.collectReviewerModels(99), ["only-in-a-comment"]);
});

test("collectReviewerModels skips trigger comments that quote the trailer in prose", async () => {
  const { h } = helpersFor({
    comments: [
      { body: "/oc review\n\nEnd the summary with `Assisted-by: <model>`" },
      { body: "/run\n\nAssisted-by: not-a-real-model" },
      { body: "Verdict: approve\nAssisted-by: real-model" }
    ]
  });
  assert.deepStrictEqual(await h.collectReviewerModels(99), ["real-model"]);
});

test("collectReviewerModels tolerates a bullet-prefixed or emphasised trailer", async () => {
  const { h } = helpersFor({
    comments: [
      { body: "- **Assisted-by:** bullet-model" },
      { body: "**Assisted-by:** **bold-model**" },
      { body: "  Assisted-by: indented-model" }
    ]
  });
  assert.deepStrictEqual(
    await h.collectReviewerModels(99),
    ["bullet-model", "bold-model", "indented-model"]
  );
});

test("collectReviewerModels survives a failing read and keeps the other source", async () => {
  const { h } = helpersFor({
    reviews: new Error("boom"),
    comments: [{ body: "Assisted-by: from-comments" }]
  });
  assert.deepStrictEqual(await h.collectReviewerModels(99), ["from-comments"]);
});

test("collectReviewerModels only accepts a model id, not trailing prose", async () => {
  // The value reaches a commit message verbatim, so the capture is restricted to
  // the model-id charset; a sentence after the id is dropped, not truncated into
  // something that looks like a model.
  const { h } = helpersFor({
    comments: [{ body: "Assisted-by: real-model, and here is a long explanation." }]
  });
  assert.deepStrictEqual(await h.collectReviewerModels(99), ["real-model"]);
});

test("collectReviewerModels returns an empty list when nothing advertises a model", async () => {
  const { h } = helpersFor({ reviews: [], comments: [{ body: "LGTM" }] });
  assert.deepStrictEqual(await h.collectReviewerModels(99), []);
});

test("buildMergeMessage returns null when there is no model to attribute", async () => {
  const { h } = helpersFor();
  assert.strictEqual(h.buildMergeMessage([commit("subject")], []), null);
});

test("buildMergeMessage reproduces the single-commit squash body and appends the trailer", () => {
  const { h } = helpersFor();
  const msg = h.buildMergeMessage(
    ["#42 - Subject\n\nWhy it changed.\n\nAssisted-by: resolver-a"],
    ["reviewer-b"]
  );
  assert.strictEqual(
    msg,
    "Why it changed.\n\nAssisted-by: resolver-a\n\nReviewed-by: reviewer-b"
  );
});

test("buildMergeMessage never repeats the first commit subject", () => {
  // commit_message replaces the message body only. The subject stays under the
  // repo's squash_merge_commit_title setting, so emitting it here would leave
  // the merged commit with the subject twice.
  const { h } = helpersFor();
  assert.strictEqual(
    h.buildMergeMessage(["#42 - Subject\n\nBody."], ["reviewer-a"]).includes("#42 - Subject"),
    false
  );
});

test("buildMergeMessage falls back to the PR description when the commits carry only trailers", () => {
  // The agent's commits are subject-plus-Assisted-by by construction; without
  // the fallback the merged message says nothing about the change (#840).
  const { h } = helpersFor();
  const msg = h.buildMergeMessage(
    ["Fixed FatChain beyond-EOF writes\n\nAssisted-by: resolver-a",
     "Added LGPL header, tests 177/0\n\nAssisted-by: resolver-a"],
    ["reviewer-b"],
    "Fixed FatChain: deterministic getCluster, gap-zeroing.\n\nCloses #840\n\n[github run](https://github.com/o/r/actions/runs/1)"
  );
  assert.ok(
    msg.includes("Fixed FatChain: deterministic getCluster, gap-zeroing."),
    "the PR description carries the substance"
  );
  assert.ok(msg.includes("Closes #840"), "useful PR-body lines are kept");
  assert.ok(!msg.includes("github run"), "the run link is stripped");
  assert.ok(msg.includes("Reviewed-by: reviewer-b"), "attribution still appended");
});

test("buildMergeMessage prefers the commit body over the PR description when it has substance", () => {
  const { h } = helpersFor();
  const msg = h.buildMergeMessage(
    ["#42 - Subject\n\nReal explanation here."],
    ["reviewer-a"],
    "PR description that should be ignored."
  );
  assert.ok(msg.includes("Real explanation here."));
  assert.ok(!msg.includes("should be ignored"));
});

test("mergePR falls back to the PR description when the commits carry only trailers", async () => {
  // The production shape: pulls.get returns { data: { body } }, and the first
  // commit is subject-plus-Assisted-by. mergePR must read pr.data.body, not
  // pr.body - the latter is undefined and silently disables the fallback,
  // which is exactly what #842's merge message did before this test.
  const { h, calls } = helpersFor({
    reviews: [{ body: "LGTM\n\nAssisted-by: reviewer-a" }],
    commits: [
      { commit: { message: "Fixed the thing\n\nAssisted-by: resolver-a" } },
      { commit: { message: "#42 - Add tests\n\nReal test description.\n\nAssisted-by: resolver-a" } }
    ],
    prBody: "## Summary\n\nFixed the thing properly.\n\nCloses #42\n\n[github run](https://github.com/o/r/actions/runs/1)"
  });
  await h.mergePR(99);
  const message = calls.merge[0].commit_message;
  assert.ok(message, "an attributed merge carries an explicit message");
  assert.ok(
    message.includes("Fixed the thing properly."),
    "the PR description is the fallback prose source"
  );
  assert.ok(
    message.includes("Assisted-by: resolver-a"),
    "the writer's trailer survives the description fallback (#845's merge lost it)"
  );
  assert.ok(!message.includes("github run"), "run links are stripped");
  assert.ok(message.includes("Reviewed-by: reviewer-a"), "attribution survives");
  assert.ok(message.includes("Real test description."), "real commit bodies still land");
});

test("mergePR sets a subject carrying the issue number (single-commit squash rule)", async () => {
  // GitHub uses the single commit's SUBJECT for a one-commit squash, and the
  // agent's commit subjects lack the "#<issue> - " prefix, so the merged commit
  // loses the issue link (8bb54c6a4b has no #844). mergePR must pass an
  // explicit commit_title.
  const { h, calls } = helpersFor({
    reviews: [{ body: "LGTM\n\nAssisted-by: reviewer-a" }],
    commits: [
      { commit: { message: "Fixed the thing\n\nAssisted-by: resolver-a" } }
    ],
    prBody: "## Summary\n\nFixed the thing properly.\n\nCloses #42"
  });
  await h.mergePR(99);
  assert.strictEqual(
    calls.merge[0].commit_title,
    "#42 - Subject (#99)"
  );
});

test("buildMergeMessage reproduces a multi-commit squash body", () => {
  const { h } = helpersFor();
  const msg = h.buildMergeMessage(
    ["#42 - Subject\n\nFirst body.", "#42 - Second\n\nSecond body."],
    ["reviewer-a", "reviewer-b"]
  );
  assert.strictEqual(
    msg,
    "First body.\n\n#42 - Second\n\nSecond body.\n\nReviewed-by: reviewer-a, reviewer-b"
  );
});

test("buildMergeMessage is just the trailer when no commit has a body", () => {
  const { h } = helpersFor();
  assert.strictEqual(h.buildMergeMessage([], ["reviewer-a"]), "Reviewed-by: reviewer-a");
});

test("mergePR passes the composed commit_message when a reviewer model was advertised", async () => {
  const { h, calls } = helpersFor({
    commits: [commit("#42 - Subject\n\nBody.")],
    comments: [{ body: "Verdict: approve\nAssisted-by: reviewer-a" }]
  });
  await h.mergePR(99);
  assert.strictEqual(calls.merge.length, 1);
  assert.strictEqual(calls.merge[0].commit_title, "#42 - Subject (#99)");
  assert.strictEqual(calls.merge[0].commit_message, "Body.\n\nReviewed-by: reviewer-a");
});

test("mergePR omits commit_message when no reviewer model is recorded", async () => {
  // Overriding commit_message would replace the repository's COMMIT_MESSAGES
  // template for no benefit, so an unattributed PR must merge untouched.
  const { h, calls } = helpersFor({ commits: [commit("#42 - Subject")] });
  await h.mergePR(99);
  assert.strictEqual("commit_message" in calls.merge[0], false);
});

test("mergePR still merges when the commit list cannot be read", async () => {
  const { h, calls } = helpersFor({
    commits: new Error("rate limited"),
    comments: [{ body: "Assisted-by: reviewer-a" }]
  });
  await h.mergePR(99);
  assert.strictEqual(calls.merge.length, 1);
  assert.strictEqual("commit_message" in calls.merge[0], false);
  assert.strictEqual(calls.deleteRef.length, 1);
});

test("parkForCI owns the flag pair and the history entry", () => {
  const { h } = helpersFor();
  const task = { review_in_progress: true, review_pending_ci: false, history: [] };
  h.parkForCI(task, 99, {
    event: "merge_deferred",
    reason: "ci_not_green",
    extra: { task: 42 }
  });
  assert.strictEqual(task.review_in_progress, false);
  assert.strictEqual(task.review_pending_ci, true);
  assert.strictEqual(task.history.length, 1);
  assert.strictEqual(task.history[0].event, "merge_deferred");
  assert.strictEqual(task.history[0].reason, "ci_not_green");
  assert.strictEqual(task.history[0].pr, 99);
  assert.strictEqual(task.history[0].task, 42);
});

test("parkForCI appends to a separate history array for the orchestrator shape", () => {
  const { h } = helpersFor();
  const task = { review_in_progress: true, review_pending_ci: false };
  const history = [];
  h.parkForCI(task, 99, { history });
  assert.strictEqual(task.review_in_progress, false);
  assert.strictEqual(task.review_pending_ci, true);
  assert.strictEqual(task.history, undefined, "the flags object is not given a history field");
  assert.strictEqual(history.length, 1);
  assert.strictEqual(history[0].event, "review_pending_ci");
  assert.strictEqual(history[0].pr, 99);
});

// ---------------------------------------------------------------------------
// #872: a PR branch cut before a later master merge must not be squash-merged,
// because applying the branch's whole tree reverts what master gained.
// ---------------------------------------------------------------------------

function githubWithBase({ tip, mergeBase, lagCommits = [], failOn = null, head = "headsha", total } = {}) {
  const { github, calls } = createGithub({});
  github.rest.pulls.get = async () => ({
    data: { number: 99, head: { ref: "opencode/issue99-x", sha: head }, base: { ref: "master" } }
  });
  github.rest.repos = {
    getBranch: async (args) => {
      calls.getBranch = args;
      if (failOn === "getBranch") throw new Error("getBranch unavailable");
      return { data: { commit: { sha: tip || "tipsha" } } };
    },
    compare: async (args) => {
      calls.compare = args;
      if (failOn === "compare") throw new Error("compare unavailable");
      return {
        data: {
          merge_base_commit: { sha: mergeBase || "tipsha" },
          total_commits: typeof total === "number" ? total : lagCommits.length,
          commits: lagCommits.map((c, i) => ({ sha: c.sha, commit: { message: c.subject } }))
        }
      };
    }
  };
  const context = { repo: { owner: "LSantha", repo: "jnode_ai" } };
  return { h: createHelpers({ github, context, core: createCore() }), calls };
}

test("checkBaseCurrency: a branch containing the base tip is current", async () => {
  const { h, calls } = githubWithBase({ tip: "tipsha", mergeBase: "tipsha", lagCommits: [] });
  const info = await h.checkBaseCurrency(99);
  assert.strictEqual(info.current, true, "the branch contains the base tip");
  assert.strictEqual(info.base, "master");
  assert.strictEqual(info.lag.length, 0, "nothing would be reverted");
  assert.ok(calls.compare, "the merge-base is read from the compare API");
});

test("checkBaseCurrency: a lagging branch is not current and names what it reverts", async () => {
  const { h } = githubWithBase({
    tip: "tipsha",
    mergeBase: "oldsha",
    lagCommits: [
      { sha: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", subject: "Add JNodeOceanTheme" },
      { sha: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", subject: "Fix FAT32 floor" }
    ]
  });
  const info = await h.checkBaseCurrency(99);
  assert.strictEqual(info.current, false, "the branch is behind master");
  assert.strictEqual(info.lag.length, 2, "both commits would be reverted");
  assert.strictEqual(info.total, 2, "total is the exact commit count");
  assert.strictEqual(info.lag[0].sha, "aaaaaaaa", "the sha is shortened for the comment");
  assert.strictEqual(info.lag[0].subject, "Add JNodeOceanTheme", "the subject is the first line");
  const comment = h.baseCurrencyComment(99, info, "review");
  assert.ok(comment.includes("Add JNodeOceanTheme"),
    "the refusal names the commits that would be reverted");
  assert.ok(comment.includes("Merge master into the branch") && comment.includes("re-request the review"),
    "the review-entry remedy says to update and re-review");
  const human = h.baseCurrencyComment(99, info, "human");
  assert.ok(human.includes("approve again") && !human.includes("re-request the review"),
    "the human-entry remedy says to approve again, not re-review");
});

test("checkBaseCurrency: fail-closed on an unreadable base", async () => {
  const { h } = githubWithBase({ failOn: "getBranch" });
  const info = await h.checkBaseCurrency(99);
  assert.strictEqual(info.current, false, "an unreadable base must block the merge");
  assert.ok(h.baseCurrencyComment(99, info, "review").includes("could not be read"),
    "the refusal says why rather than naming nothing");
});

test("checkBaseCurrency: fail-closed on an unreadable PR", async () => {
  const { github } = createGithub({});
  github.rest.pulls.get = async () => { throw new Error("no PR"); };
  const context = { repo: { owner: "LSantha", repo: "jnode_ai" } };
  const h = createHelpers({ github, context, core: createCore() });
  const info = await h.checkBaseCurrency(99);
  assert.strictEqual(info.current, false);
  assert.strictEqual(info.lag.length, 0);
});

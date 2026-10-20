const test = require("node:test");
const assert = require("node:assert");

const createHelpers = require("../orchestrator-helpers.js");

function createCore() {
  return { info: () => {}, warning: () => {}, error: () => {} };
}

function createGithub({ reviews = [], comments = [], commits = [] } = {}) {
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
  assert.strictEqual("commit_title" in calls.merge[0], false);
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

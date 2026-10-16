/*
 * Shared helpers for the orchestrator and ticket-runner scripts.
 *
 * Each function is a factory that takes { github, context, core } and returns
 * the concrete helper bound to those parameters.  This keeps the call-sites
 * identical to the original inline helpers.
 *
 * Usage (inside actions/github-script):
 *   const helpers = require("./orchestrator-helpers.js")({ github, context, core });
 *   const pr = await helpers.findPRForIssue(42);
 */

'use strict';

/**
 * Both runners persist their state into an issue body, which GitHub caps at
 * 65536 characters, so history cannot grow without bound. A suppressed CI
 * retry pushes one entry, and the CI-heal loop produces one completion per
 * SHA indefinitely: at ~127 bytes per entry a persistently red PR would make
 * the body unwritable and the runner would lose its own state. Keep the most
 * recent entries; history has no functional readers, it is diagnostic only.
 */
var MAX_HISTORY_ENTRIES = 200;

module.exports = function createHelpers({ github, context, core }) {
  const owner = context.repo.owner;
  const repo  = context.repo.repo;

  /** Post a /oc comment on an issue or PR to trigger the opencode workflow. */
  async function triggerTask(issueNumber, message) {
    if (message === undefined) message = "/oc Please proceed with this task.";
    core.info("Triggering task #" + issueNumber + " via comment...");
    await github.rest.issues.createComment({
      owner, repo, issue_number: issueNumber, body: message
    });
  }

  /** Find an open PR whose branch or body references the given issue number. */
  async function findPRForIssue(issueNumber) {
    var pulls = await github.rest.pulls.list({
      owner, repo, state: "open", per_page: 100
    });
    var issueRefRe = new RegExp("(?:Closes|Fixes|Resolves)\\s+#" + issueNumber + "\\b", "i");
    for (var i = 0; i < pulls.data.length; i++) {
      var pr = pulls.data[i];
      var headRef = (pr.head && pr.head.ref) || "";
      var body = pr.body || "";
      if (headRef.startsWith("opencode/issue" + issueNumber + "-") || issueRefRe.test(body)) {
        return pr.number;
      }
    }
    return null;
  }

  /** Build the standard review prompt that the runner/orchestrator posts on PRs. */
  function getReviewPrompt() {
    return "/oc review\n\nYou are reviewing a pull request for the automated pipeline. Your final line must be exactly one of:\nVerdict: approve\nVerdict: request-changes\n\nUse \"Verdict: request-changes\" if the PR needs code changes. Use \"Verdict: approve\" only if the PR is correct and ready for the next phase.";
  }

  /** Scan comments on a PR for the latest Verdict line. Returns 'approve', 'request-changes', or null. */
  async function getAgentReviewVerdict(prNumber) {
    var comments = await github.rest.issues.listComments({
      owner, repo, issue_number: prNumber, per_page: 100
    });
    var approveRe = /(?:\*{0,2}Verdict\*{0,2}:?\*{0,2}\s*:?\s*)approve\b/i;
    var requestChangesRe = /(?:\*{0,2}Verdict\*{0,2}:?\*{0,2}\s*:?\s*)request[-_]changes\b/i;

    for (var i = comments.data.length - 1; i >= 0; i--) {
      var body = comments.data[i].body || "";
      // The review prompt itself quotes both verdicts; never read triggers as verdicts.
      if (/(^|\s)\/(oc|run|orchestrate)(\s|$)/.test(body)) continue;
      if (requestChangesRe.test(body)) return "request-changes";
      if (approveRe.test(body)) return "approve";
    }
    return null;
  }

  /** Kinds that may auto-merge without an explicit 'auto-merge' label. */
  var SAFE_AUTO_MERGE_KINDS = ["kind/chore", "kind/wiki", "kind/test"];

  /** Label-only check: explicit 'auto-merge' or implicit safe kind. No API beyond label reads. */
  async function isAutoMergeEligible(issueNumber, prNumber) {
    var issue = await github.rest.issues.get({
      owner, repo, issue_number: issueNumber
    });
    var labels = extractLabels(issue.data);
    if (labels.includes("auto-merge")) return true;
    var implicit = SAFE_AUTO_MERGE_KINDS.some(function (k) { return labels.includes(k); });
    if (!implicit) return false;

    if (prNumber) {
      try {
        var pr = await github.rest.issues.get({
          owner, repo, issue_number: prNumber
        });
        if (extractLabels(pr.data).includes("auto-merge")) return true;
      } catch (_) {}
    }
    return implicit;
  }

  /** Forbidden diff paths: ASM, build config, plugin lists. Mirrors the resolver skill self-check. */
  var FORBIDDEN_DIFF_RE = /(^|\/)(core\/src\/native\/x86\/|jnode\.properties$|all\/build\.xml$|all\/conf\/)/;
  var TEST_PATH_RE = /(^|\/)(src\/test\/|tests\/)/;

  /**
   * True when the PR diff touches a forbidden path. Distinct from a size-budget
   * failure because a forbidden path is a hard wall no human can approve past by
   * being patient: the change is out of scope for auto-merge regardless of size.
   */
  async function touchesForbiddenPath(prNumber) {
    var files;
    try {
      files = await github.rest.pulls.listFiles({
        owner, repo, pull_number: prNumber, per_page: 100
      });
    } catch (e) {
      core.warning("touchesForbiddenPath: listFiles failed for PR #" + prNumber + ": " + e.message);
      return false;
    }
    // Same guard shape as isDiffSafe, for symmetry only: an unknown response
    // makes this return false either way, since an unreadable file list holds
    // no matching path. No test can distinguish the two formulations, which is
    // why the isDiffSafe twin has shape cases and this one does not.
    var list = files && files.data;
    if (!Array.isArray(list)) return false;
    for (var i = 0; i < list.length; i++) {
      if (FORBIDDEN_DIFF_RE.test(list[i].filename || "")) return true;
    }
    return false;
  }

  // Diff-size budgets. Production additions stay tightly capped because they
  // are what can change runtime behaviour. Test additions get a much larger
  // budget: a regression test for a small fix routinely exceeds the
  // production budget on its own, and counting the test against the
  // production cap rejected 7-line fixes for carrying their own tests.
  var MAX_PROD_ADDITIONS = 100;
  var MAX_TEST_ADDITIONS = 2000;
  var MAX_PROD_FILES = 5;
  var MAX_TEST_FILES = 20;

  /** True when the PR diff is small and touches no forbidden path. */
  async function isDiffSafe(prNumber) {
    var files;
    try {
      files = await github.rest.pulls.listFiles({
        owner, repo, pull_number: prNumber, per_page: 100
      });
    } catch (e) {
      core.warning("isDiffSafe: listFiles failed for PR #" + prNumber + ": " + e.message);
      return false;
    }
    // Fail closed on an unknown shape, exactly like the >= 100 guard below: a
    // response that resolved without a `data` array is not the same as an empty
    // diff, and `files.data || []` would collapse the two and read a malformed
    // partial response as "no changed files, therefore within budget".
    var list = files && files.data;
    if (!Array.isArray(list)) return false;
    // 100 is the per_page floor, so a full page means the diff may be bigger
    // than what was read: unknown, not safe. An empty array is different - it
    // means no changed files at all, which cannot exceed any budget, and
    // reporting it as "over budget" sent an empty diff to HUMAN_REVIEW on a lie.
    if (list.length >= 100) return false;

    var prodAdditions = 0;
    var testAdditions = 0;
    var prodFiles = 0;
    var testFiles = 0;
    for (var i = 0; i < list.length; i++) {
      var filename = list[i].filename || "";
      if (FORBIDDEN_DIFF_RE.test(filename)) return false;
      var additions = list[i].additions || 0;
      if (TEST_PATH_RE.test(filename)) {
        testAdditions += additions;
        testFiles += 1;
      } else {
        prodAdditions += additions;
        prodFiles += 1;
      }
    }
    return prodAdditions <= MAX_PROD_ADDITIONS &&
           testAdditions <= MAX_TEST_ADDITIONS &&
           prodFiles <= MAX_PROD_FILES &&
           testFiles <= MAX_TEST_FILES;
  }

  /** True when CI check runs on the PR head SHA show success and no failure. */
  async function isCIGreen(prNumber) {
    var pr;
    try {
      pr = await github.rest.pulls.get({ owner, repo, pull_number: prNumber });
    } catch (e) {
      core.warning("isCIGreen: pulls.get failed for PR #" + prNumber + ": " + e.message);
      return false;
    }
    var sha = pr.data && pr.data.head && pr.data.head.sha;
    if (!sha) return false;
    var runs;
    try {
      var res = await github.rest.checks.listForRef({ owner, repo, ref: sha, per_page: 100 });
      runs = (res.data && res.data.check_runs) || [];
    } catch (e) {
      core.warning("isCIGreen: listForRef failed for " + sha + ": " + e.message);
      return false;
    }
    var BAD = ["failure", "cancelled", "timed_out", "action_required"];
    // Only runs named for the ant.yml ("Java CI") jobs count. Names are the job
    // ids, which GitHub uses as the check-run name when a job defines no `name:`.
    // `heal` is excluded: it is skipped on green and would only ever make the
    // gate read as pending. Note this filters by name, not by workflow, so a
    // job with the same id in another workflow would also count.
    var JAVA_CI_JOBS = ["build", "test", "boot32", "boot64"];
    var seen = {};
    for (var i = 0; i < runs.length; i++) {
      var r = runs[i];
      if (JAVA_CI_JOBS.indexOf(r.name) < 0) continue;
      // A run that has not finished is not green. Without this the gate reads a
      // completed green `build` job as whole-workflow green while the QEMU boot
      // jobs are still running, which is the race the gate exists to prevent.
      if (r.status !== "completed") return false;
      if (BAD.indexOf(r.conclusion) >= 0) return false;
      if (r.conclusion === "success") seen[r.name] = true;
    }
    // Every Java CI job must have reported green. Without this a run showing only
    // a green `build` passes while the boot tests have not run at all.
    for (var j = 0; j < JAVA_CI_JOBS.length; j++) {
      if (!seen[JAVA_CI_JOBS[j]]) return false;
    }
    return true;
  }

  // Trailer shape produced by .github/hooks/prepare-commit-msg and by the
  // resolver skill's review summary. Bullet and bold markers are tolerated
  // because a reviewer may write the line as a list item or emphasise the key,
  // which would otherwise hide an otherwise valid attribution.
  var MODEL_TRAILER_RE = /^[ \t]*(?:[-*+][ \t]*)?\*{0,2}Assisted-by\*{0,2}:[ \t*]*([^\s*]+)/gim;

  /** True when a body is an automation trigger, not a human or agent report. */
  function isTriggerBody(body) {
    return /(^|\s)\/(oc|run|orchestrate)(\s|$)/.test(body);
  }

  /**
   * Distinct model IDs advertised by `Assisted-by:` trailers on the PR's review
   * and comment bodies, in first-seen order.
   *
   * The resolver models reach the squash body through commit trailers, but a
   * reviewer makes no commit, so its model only ever exists as a line in the
   * review summary comment. Review verdicts and `/oc approve` both arrive as
   * issue comments rather than review objects, so both sources are scanned.
   * Trigger comments are skipped for the same reason `getAgentReviewVerdict`
   * skips them: they quote the skill text that spells the trailer out, and
   * reading one would credit the human who typed the trigger.
   */
  async function collectReviewerModels(prNumber) {
    var bodies = [];
    var sources = [
      function () { return github.rest.pulls.listReviews({ owner, repo, pull_number: prNumber, per_page: 100 }); },
      function () { return github.rest.issues.listComments({ owner, repo, issue_number: prNumber, per_page: 100 }); }
    ];
    for (var s = 0; s < sources.length; s++) {
      var res;
      try {
        res = await sources[s]();
      } catch (e) {
        core.warning("collectReviewerModels: read failed for PR #" + prNumber + ": " + e.message);
        continue;
      }
      var list = res && res.data;
      if (!Array.isArray(list)) continue;
      for (var i = 0; i < list.length; i++) {
        bodies.push((list[i] && list[i].body) || "");
      }
    }
    var seen = {};
    var models = [];
    for (var j = 0; j < bodies.length; j++) {
      var body = bodies[j];
      if (isTriggerBody(body)) continue;
      MODEL_TRAILER_RE.lastIndex = 0;
      var m;
      while ((m = MODEL_TRAILER_RE.exec(body)) !== null) {
        var id = m[1].replace(/^[*_`]+/, "").replace(/[*_`]+$/, "");
        if (!id || seen[id]) continue;
        seen[id] = true;
        models.push(id);
      }
    }
    return models;
  }

  /** Split a commit message into its subject line and the remainder. */
  function splitMessage(message) {
    var lines = String(message == null ? "" : message).replace(/\s+$/, "").split("\n");
    var subject = (lines.shift() || "").trim();
    var body = lines.join("\n").replace(/^\n+/, "").replace(/\s+$/, "");
    return { subject: subject, body: body };
  }

  /**
   * Compose the squash-merge message, reproducing what the repo's
   * `squash_merge_commit_message = COMMIT_MESSAGES` setting would have produced
   * (first commit subject as the title, every commit's text below it) and adding
   * the `Reviewed-by:` trailer.
   *
   * Passing `commit_message` to the merge API overrides that repo setting, so
   * the body has to be rebuilt here or it is silently dropped. Returns null
   * when there is nothing to attribute, which leaves the merge call untouched.
   */
  function buildMergeMessage(commitMessages, models, fallbackTitle) {
    if (!Array.isArray(models) || models.length === 0) return null;
    var parts = [];
    var title = "";
    var split = (Array.isArray(commitMessages) ? commitMessages : []).map(splitMessage);
    for (var i = 0; i < split.length; i++) {
      var chunk;
      if (i === 0) {
        title = split[0].subject;
        chunk = split[0].body;
      } else {
        chunk = split[i].subject ? split[i].subject : "";
        if (split[i].body) chunk += (chunk ? "\n\n" : "") + split[i].body;
      }
      if (chunk) parts.push(chunk);
    }
    if (!title) title = fallbackTitle || "Merge pull request";
    parts.push("Reviewed-by: " + models.join(", "));
    return [title].concat(parts).join("\n\n");
  }

  /** Return true if the user object represents a bot. */
  function isBotUser(user) {
    return !!user && ((user.type || "").toLowerCase() === "bot" ||
      (user.login || "").includes("[bot]") ||
      (user.login || "").startsWith("app/"));
  }

  /** Squash-merge a PR and delete its head branch. */
  async function mergePR(prNumber) {
    core.info("Merging PR #" + prNumber + "...");
    var pr = await github.rest.pulls.get({
      owner, repo, pull_number: prNumber
    });
    var mergeArgs = {
      owner, repo, pull_number: prNumber, merge_method: "squash"
    };
    var commitMessage = null;
    try {
      var models = await collectReviewerModels(prNumber);
      if (models.length) {
        var commits = await github.rest.pulls.listCommits({
          owner, repo, pull_number: prNumber, per_page: 100
        });
        var list = commits && commits.data;
        if (Array.isArray(list)) {
          commitMessage = buildMergeMessage(list.map(function (c) {
            return (c && c.commit && c.commit.message) || "";
          }), models, pr.data && pr.data.title);
        } else {
          core.warning("mergePR: listCommits returned no data for PR #" + prNumber +
            "; merge will use the repository default message");
        }
      }
    } catch (e) {
      core.warning("mergePR: could not build the Reviewed-by message for PR #" +
        prNumber + ": " + e.message);
      commitMessage = null;
    }
    // Only override when there is something to add: an unnecessary commit_message
    // would replace the repository's COMMIT_MESSAGES template for no reason.
    if (commitMessage) mergeArgs.commit_message = commitMessage;
    try {
      await github.rest.pulls.merge(mergeArgs);
    } catch (err) {
      var message = String((err && err.message) || "");
      if (err && err.status === 204) {
        core.info("Merge API returned 204; verifying PR state");
      } else if (/Unexpected end of JSON input/i.test(message)) {
        core.info("Merge API returned an empty body; verifying PR state");
      } else {
        throw err;
      }
    }
    var merged = await github.rest.pulls.get({
      owner, repo, pull_number: prNumber
    });
    if (!merged.data || merged.data.merged !== true) {
      throw new Error("merge not confirmed; PR state=" +
        (merged.data && merged.data.state ? merged.data.state : "unknown"));
    }
    try {
      await github.rest.git.deleteRef({
        owner, repo, ref: "heads/" + pr.data.head.ref
      });
    } catch (e) {
      core.warning("Could not delete branch " + pr.data.head.ref + ": " + e.message);
    }
  }

  /** List open PR numbers whose head SHA matches (for CI-completion wakeups). */
  async function findPRsForSHA(sha) {
    var pulls = await github.rest.pulls.list({
      owner, repo, state: "open", per_page: 100
    });
    var out = [];
    for (var i = 0; i < pulls.data.length; i++) {
      var pr = pulls.data[i];
      if (pr.head && pr.head.sha === sha) out.push(pr.number);
    }
    return out;
  }
  /** Extract labels from an issue response as an array of strings. */
  function extractLabels(issueData) {
    return (issueData.labels || []).map(function (l) {
      return (typeof l === "string") ? l : l.name;
    });
  }

  /** Labels that signal a task is \"complete\" (no further dev work needed). */
  var COMPLETION_LABELS = [
    "agent/done", "agent/investigated", "agent/skip",
    "agent/blocked", "agent/needs-info", "agent/duplicate"
  ];

  /** Labels that short-circuit the task (skip, blocked, duplicate, etc. -- not 'agent/done'). */
  var SHORT_CIRCUIT_LABELS = [
    "agent/skip", "agent/needs-info", "agent/investigated",
    "agent/blocked", "agent/duplicate"
  ];

  return {
    triggerTask: triggerTask,
    findPRForIssue: findPRForIssue,
    getReviewPrompt: getReviewPrompt,
    getAgentReviewVerdict: getAgentReviewVerdict,
    isAutoMergeEligible: isAutoMergeEligible,
    isDiffSafe: isDiffSafe,
    touchesForbiddenPath: touchesForbiddenPath,
    MAX_HISTORY_ENTRIES: MAX_HISTORY_ENTRIES,
    isCIGreen: isCIGreen,
    SAFE_AUTO_MERGE_KINDS: SAFE_AUTO_MERGE_KINDS,
    isBotUser: isBotUser,
    collectReviewerModels: collectReviewerModels,
    buildMergeMessage: buildMergeMessage,
    mergePR: mergePR,
    extractLabels: extractLabels,
    findPRsForSHA: findPRsForSHA,
    COMPLETION_LABELS: COMPLETION_LABELS,
    SHORT_CIRCUIT_LABELS: SHORT_CIRCUIT_LABELS
  };
};

// Module-level export so the runners can cap history without instantiating.
module.exports.MAX_HISTORY_ENTRIES = MAX_HISTORY_ENTRIES;


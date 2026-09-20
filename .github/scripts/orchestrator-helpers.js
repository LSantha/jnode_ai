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

  /** Check whether an issue or its PR requires human review (true unless 'auto-merge' label is present). */
  async function needsHumanReview(issueNumber, prNumber) {
    var issue = await github.rest.issues.get({
      owner, repo, issue_number: issueNumber
    });
    var labels = (issue.data.labels || []).map(function (l) {
      return (typeof l === "string") ? l : l.name;
    });
    if (labels.includes("auto-merge")) return false;

    if (prNumber) {
      try {
        var pr = await github.rest.issues.get({
          owner, repo, issue_number: prNumber
        });
        var prLabels = (pr.data.labels || []).map(function (l) {
          return (typeof l === "string") ? l : l.name;
        });
        if (prLabels.includes("auto-merge")) return false;
      } catch (_) {}
    }

    return true;
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
    var list = files.data || [];
    if (list.length === 0 || list.length >= 100) return false;
    if (list.length > 5) return false;
    var additions = 0;
    for (var i = 0; i < list.length; i++) {
      additions += list[i].additions || 0;
      if (FORBIDDEN_DIFF_RE.test(list[i].filename || "")) return false;
    }
    return additions <= 100;
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
    var ok = 0;
    for (var i = 0; i < runs.length; i++) {
      var r = runs[i];
      if (r.status !== "completed") continue;
      if (BAD.indexOf(r.conclusion) >= 0) return false;
      if (r.conclusion === "success") ok++;
    }
    return ok > 0;
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
    await github.rest.pulls.merge({
      owner, repo, pull_number: prNumber, merge_method: "squash"
    });
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
    needsHumanReview: needsHumanReview,
    isAutoMergeEligible: isAutoMergeEligible,
    isDiffSafe: isDiffSafe,
    isCIGreen: isCIGreen,
    SAFE_AUTO_MERGE_KINDS: SAFE_AUTO_MERGE_KINDS,
    isBotUser: isBotUser,
    mergePR: mergePR,
    extractLabels: extractLabels,
    findPRsForSHA: findPRsForSHA,
    COMPLETION_LABELS: COMPLETION_LABELS,
    SHORT_CIRCUIT_LABELS: SHORT_CIRCUIT_LABELS
  };
};

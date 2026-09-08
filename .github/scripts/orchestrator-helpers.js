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
    isBotUser: isBotUser,
    mergePR: mergePR,
    extractLabels: extractLabels,
    COMPLETION_LABELS: COMPLETION_LABELS,
    SHORT_CIRCUIT_LABELS: SHORT_CIRCUIT_LABELS
  };
};

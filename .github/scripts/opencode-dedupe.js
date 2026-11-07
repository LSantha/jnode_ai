'use strict';

// Dedupe guard for opencode.yml. One /oc comment must produce at most one
// agent action, and an agent's own report must never look like a trigger.
//
// Two distinct duplicates reach this workflow, and they need two guards:
//   1. Redelivery: GitHub delivers the same comment event twice, so two runs
//      are created for one comment id. The runs API cannot see a comment id,
//      so opencode.yml puts it in the run name ("(comment <id>)") and this
//      guard no-ops every run except the earliest for that id. The workflow
//      concurrency group (keyed on event name and the same id) serializes the pair, so the
//      later run only checks after the earlier one has registered.
//   2. Agent self-trigger: agent reports are posted as the maintainer (the
//      GH_PAT owner passes the collaborator gate) and carry no opencode.ai/s/
//      session link, so a report that merely quotes "/oc fix" in a heading
//      ("## Re-review (after /oc fix)") re-triggered a coding run - the
//      duplicate on PR #849, which then posted its own report quoting /oc
//      again. Report-shaped bodies that do not START with /oc are reports,
//      not triggers. A human prompt that instructs a verdict still starts
//      with /oc, so it is not caught by this rule.
//
// Model selection stays pinned to the event payload by opencode.yml
// (resolveModel on context.payload.comment.body); this module never reads a
// comment back from the API, so an override marker cannot be lost to a racy
// fresh read.

var reportSignals = require("./report-signals.js");

function isAnchoredTrigger(body) {
  return /^\s*\/oc\s+\S/.test(body || "");
}

function hasOcMention(body) {
  return /(^|\s)\/oc\s/.test(body || "");
}

function isReviewReport(body) {
  var b = body || "";
  return /Verdict:\s*(approve|request-changes|comment)/.test(b) && /Assisted-by:\s*\S+/.test(b);
}

function isReportShaped(body) {
  var b = body || "";
  if (!b) return false;
  if (b.indexOf("opencode.ai/s/") >= 0) return true;
  if (reportSignals.isRefusalComment(b)) return true;
  if (reportSignals.isTriageComment(b)) return true;
  if (reportSignals.isNeedsInfoComment(b)) return true;
  if (/##\s*[^\n]*Investigation Report/i.test(b)) return true;
  if (/(^|\n)\s*(?:Created|Opened) PR\s+#\d+/.test(b)) return true;
  if (/\[github run\]\((?:https?:\/\/)?[^)\s]*\/actions\/runs\/\d+\)/.test(b)) return true;
  if (isReviewReport(b)) return true;
  return false;
}

function parseCommentIdFromTitle(title) {
  var s = String(title || "");
  var m = s.match(/^\(comment (\d+)\)/) || s.match(/\(comment (\d+)\)\s*$/);
  return m ? Number(m[1]) : null;
}

function hasEarlierRun(commentId, currentRunId, runs) {
  if (!commentId || !runs) return false;
  for (var i = 0; i < runs.length; i++) {
    var run = runs[i] || {};
    if (currentRunId && Number(run.id) === Number(currentRunId)) continue;
    if (parseCommentIdFromTitle(run.display_title || run.name) !== Number(commentId)) continue;
    if (!currentRunId || Number(run.id) < Number(currentRunId)) return true;
  }
  return false;
}

function decideShouldRun(input) {
  var opts = input || {};
  var body = opts.body || "";
  if (!hasOcMention(body)) return { shouldRun: false, reason: "no-/oc-mention" };
  if (isReportShaped(body) && !isAnchoredTrigger(body)) {
    return { shouldRun: false, reason: "agent-report-quote" };
  }
  if (opts.runsError) return { shouldRun: false, reason: "runs-api-error" };
  if (hasEarlierRun(opts.commentId, opts.currentRunId, opts.runs || [])) {
    return { shouldRun: false, reason: "duplicate-of-earlier-run" };
  }
  return { shouldRun: true, reason: "ok" };
}

async function opencodeDedupe(deps) {
  var github = deps.github;
  var context = deps.context;
  var core = deps.core;
  var payload = (context && context.payload) || {};
  var comment = payload.comment || {};
  var commentId = comment.id || null;
  var body = comment.body || "";
  var currentRunId = Number((context && context.runId) || process.env.GITHUB_RUN_ID || 0) || null;
  var runs = [];
  var runsError = null;
  if (commentId) {
    try {
      var res = await github.rest.actions.listWorkflowRuns({
        owner: context.repo.owner,
        repo: context.repo.repo,
        workflow_id: "opencode.yml",
        per_page: 50
      });
      runs = (res && res.data && res.data.workflow_runs) || [];
    } catch (e) {
      runsError = e;
    }
  }
  var decision = decideShouldRun({
    commentId: commentId,
    body: body,
    currentRunId: currentRunId,
    runs: runs,
    runsError: runsError
  });
  core.info(
    "Dedupe guard: comment=" + commentId + " run=" + currentRunId +
    " shouldRun=" + decision.shouldRun + " reason=" + decision.reason
  );
  core.exportVariable("SHOULD_RUN", decision.shouldRun ? "true" : "false");
  if (typeof core.setOutput === "function") {
    core.setOutput("should_run", decision.shouldRun ? "true" : "false");
  }
  return decision;
}

module.exports = {
  isAnchoredTrigger: isAnchoredTrigger,
  hasOcMention: hasOcMention,
  isReportShaped: isReportShaped,
  parseCommentIdFromTitle: parseCommentIdFromTitle,
  hasEarlierRun: hasEarlierRun,
  decideShouldRun: decideShouldRun,
  opencodeDedupe: opencodeDedupe
};

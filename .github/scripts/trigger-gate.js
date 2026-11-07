'use strict';

var EXCLUDED_VERBS = { triage: true, approve: true };

function parseTrigger(body) {
  if (!body) return { triggered: false, verb: "", line: -1 };
  var lines = String(body).split(/\r\n|\r|\n/);
  for (var i = 0; i < lines.length; i++) {
    var m = lines[i].match(/^\s*\/oc(?=[\s]|$)(?:\s+(\S+))?/);
    if (!m) continue;
    var verb = m[1] || "";
    if (!verb || EXCLUDED_VERBS[verb]) {
      return { triggered: false, verb: verb, line: i };
    }
    return { triggered: true, verb: verb, line: i };
  }
  return { triggered: false, verb: "", line: -1 };
}

function isTrigger(body) {
  return parseTrigger(body).triggered;
}

function refsNumber(text, number) {
  if (!text) return false;
  return new RegExp("(^|[^0-9A-Za-z_])#" + number + "(?![0-9])").test(text);
}

function isDuplicateDispatch(current, runs) {
  if (!current || !runs) return false;
  var commentCreated = Date.parse(current.createdAt);
  if (isNaN(commentCreated)) return false;
  for (var i = 0; i < runs.length; i++) {
    var r = runs[i];
    if (!r || r.runId === current.runId) continue;
    if (current.issueNumber != null && !refsNumber(r.displayTitle, current.issueNumber)) continue;
    var created = Date.parse(r.createdAt);
    if (isNaN(created) || created < commentCreated) continue;
    if (r.runId != null && current.runId != null && r.runId < current.runId) return true;
  }
  return false;
}

async function dedupeCheck({ github, context, core }) {
  var comment = (context.payload && context.payload.comment) || {};
  var issue = (context.payload && context.payload.issue) || {};
  var current = {
    runId: context.runId,
    createdAt: comment.created_at,
    issueNumber: issue.number
  };
  try {
    var res = await github.rest.actions.listWorkflowRuns({
      owner: context.repo.owner,
      repo: context.repo.repo,
      workflow_id: "opencode.yml",
      per_page: 100
    });
    var list = (res && res.data) || [];
    var runs = list.map(function (r) {
      return { runId: r.id, createdAt: r.created_at, displayTitle: r.display_title };
    });
    if (isDuplicateDispatch(current, runs)) {
      core.info("Duplicate dispatch of comment " + comment.id + "; this run no-ops.");
      return false;
    }
  } catch (err) {
    core.warning("Trigger gate: could not read workflow runs (" + err.message + "); proceeding.");
  }
  return true;
}

module.exports = {
  parseTrigger: parseTrigger,
  isTrigger: isTrigger,
  isDuplicateDispatch: isDuplicateDispatch,
  dedupeCheck: dedupeCheck
};

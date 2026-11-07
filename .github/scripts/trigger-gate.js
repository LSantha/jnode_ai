/*
 * Trigger gate for opencode.yml.
 *
 * The job-level `if:` used to accept a comment body that merely contained
 * " /oc " anywhere, so an agent review comment titled
 * "... (after /oc fix)" passed the gate, and - because the agent posts as the
 * PAT owner - cleared the collaborator check and started a second opencode run
 * on its own output. PR #849 showed the result: two reviews of the same head,
 * one written by the PR's coding model reviewing itself.
 *
 * Two rules now hold:
 *   1. /oc is a line-leading command. Only a body that STARTS with "/oc " can
 *      trigger; a quoted or mid-line /oc never does.
 *   2. One comment, one agent action. A redelivered webhook starts a second
 *      workflow run with the same comment id; the run-name carries that id so
 *      this gate can see the earlier run and make the later delivery a no-op.
 *
 * The workflow keeps a cheap copy of rule 1 in its job-level `if:` so a
 * non-trigger never pays for a checkout. This module is the tested source of
 * truth; the workflow `if:` must stay a strict subset of it.
 */
'use strict';

var TRIGGER_PREFIX = "/oc ";
var SESSION_LINK = "opencode.ai/s/";
var NON_AGENT_VERBS = ["triage", "approve"];
var TRUSTED_ASSOCIATIONS = ["COLLABORATOR", "MEMBER", "OWNER"];

// The comment id in the run name, first in the string so GitHub's run-name
// truncation cannot cut it off. Keep the "Issue #N" part readable for the
// runners, which parse the display title.
var RUN_NAME_MARKER_RE = /\[oc:(\d+)\]/;

function firstToken(text) {
  var match = text.match(/^\S+/);
  return match ? match[0] : "";
}

// Is this body a direct /oc command? Anchored: the old contains(' /oc ')
// match is exactly what let an agent report retrigger the agent.
function isOcTrigger(commentBody) {
  var body = commentBody || "";
  if (body.indexOf(TRIGGER_PREFIX) !== 0) return false;
  if (body.indexOf(SESSION_LINK) >= 0) return false;
  var verb = firstToken(body.slice(TRIGGER_PREFIX.length));
  if (!verb) return false;
  return NON_AGENT_VERBS.indexOf(verb) < 0;
}

function isTrustedAssociation(association) {
  return TRUSTED_ASSOCIATIONS.indexOf(association) >= 0;
}

function runNameMarker(commentId) {
  return "[oc:" + commentId + "]";
}

function markerForRunName(runName) {
  var match = (runName || "").match(RUN_NAME_MARKER_RE);
  return match ? "[oc:" + match[1] + "]" : null;
}

// The earliest workflow run for a comment owns the action. A later delivery
// (higher run id) sees the earlier one in the runs list and no-ops. Runs with
// no marker are pre-change or unrelated and never count as duplicates.
function isDuplicateRun(currentRunId, commentId, runs) {
  var marker = runNameMarker(commentId);
  var current = Number(currentRunId);
  var list = runs || [];
  for (var i = 0; i < list.length; i++) {
    var run = list[i] || {};
    var id = Number(run.id);
    if (!isFinite(id) || !isFinite(current) || id >= current) continue;
    if (markerForRunName(run.display_title || run.name) === marker) return true;
  }
  return false;
}

// Decide whether this delivery should start the agent. Fails open on a runs
// API error: the anchored trigger gate - the actual fix - still suppressed the
// bad trigger, and suppressing a real run because a backstop read failed is
// worse than the redelivery it guards against.
async function evaluate(input) {
  var github = input.github;
  var context = input.context;
  var core = input.core || { info: function () {}, warning: function () {} };
  var comment = (context.payload && context.payload.comment) || {};

  if (!isOcTrigger(comment.body)) {
    core.info("Trigger gate: not a line-leading /oc command; skipping.");
    return { run: false, reason: "not-a-command" };
  }
  if (!isTrustedAssociation(comment.author_association)) {
    core.info("Trigger gate: author is not a collaborator/member/owner; skipping.");
    return { run: false, reason: "untrusted-author" };
  }
  if (!comment.id) {
    core.info("Trigger gate: event has no comment id; cannot dedupe, running.");
    return { run: true, reason: "no-comment-id" };
  }

  try {
    var res = await github.rest.actions.listWorkflowRuns({
      owner: context.repo.owner,
      repo: context.repo.repo,
      workflow_id: "opencode.yml",
      per_page: 100
    });
    var runs = (res && res.data && res.data.workflow_runs) ? res.data.workflow_runs : [];
    if (isDuplicateRun(context.runId, comment.id, runs)) {
      core.info("Trigger gate: run for comment " + comment.id + " already exists; skipping this delivery.");
      return { run: false, reason: "duplicate-delivery" };
    }
  } catch (err) {
    core.warning("Trigger gate: could not read workflow runs (" + err.message + "); running.");
  }
  return { run: true, reason: "ok" };
}

module.exports = {
  TRUSTED_ASSOCIATIONS: TRUSTED_ASSOCIATIONS,
  isOcTrigger: isOcTrigger,
  isTrustedAssociation: isTrustedAssociation,
  runNameMarker: runNameMarker,
  markerForRunName: markerForRunName,
  isDuplicateRun: isDuplicateRun,
  evaluate: evaluate
};

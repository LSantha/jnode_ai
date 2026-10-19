/*
 * Stall watchdog: re-emits a trigger when a runner's own wakeup was lost.
 *
 * The ticket runner and the orchestrator are event-driven. If GitHub drops a
 * workflow_run delivery (an Actions incident, as on 2026-10-16/17) a ticket
 * parks forever in REVIEW with review_pending_ci=true and nothing retries it.
 * This sweep polls state instead of trusting events, so it recovers those.
 *
 * It is deliberately NOT a second state machine:
 *   - it never changes a phase, merges, or labels a ticket;
 *   - its only actions are to post the same trigger a human would (/run,
 *     /orchestrate, or the CI-heal /oc fix) and to remove a provably leaked
 *     orchestrator lock, so every existing guard still applies.
 *
 * Safety rules (see the design in the PR/issue):
 *   - an agent run, CI run, or orchestrator run in flight -> skip;
 *   - require positive evidence: idle beyond a generous timeout, or a CI run
 *     that completed and whose wakeup was missed;
 *   - one nudge per state snapshot (hidden marker), a cooldown, an attempt cap;
 *   - respect no-auto, blocking agent labels, untrusted authors, HUMAN_REVIEW
 *     and terminal phases; never touch closed issues or MERGE (the /run escape
 *     hatch would post an agent prompt there);
 *   - dry-run by default. WATCHDOG_DRY_RUN=0 enables posting.
 *
 * Invoked from watchdog.yml. Module shape matches the other scripts: exports a
 * single async function taking { github, context, core, env }.
 */

'use strict';

const createHelpers = require("./orchestrator-helpers.js");

const TICKET_STATE_RE = /<!-- TICKET_RUNNER_STATE:\s*([\s\S]*?)\s*-->/;
const ORCH_STATE_RE = /<!-- ORCHESTRATOR_STATE:\s*([\s\S]*?)\s*-->/;
const WATCHDOG_PREFIX = "<!-- WATCHDOG:";
const CI_HEAL_PREFIX = "<!-- CI-HEAL:";

const ACTIVE_RUN_STATUSES = ["queued", "in_progress", "requested", "waiting", "pending"];
const TERMINAL_PHASES = ["DONE", "FAILED", "CANCELLED"];
const SKIP_LABELS = [
  "watchdog/paused",
  "no-auto",
  "agent/in-progress",
  "agent/needs-info",
  "agent/blocked",
  "agent/duplicate",
  "agent/failed",
  "agent/skip"
];

function parseBlock(re, body) {
  if (!body) return null;
  var m = body.match(re);
  if (!m) return null;
  try { return JSON.parse(m[1]); } catch (_) { return null; }
}

function isTerminal(phase) {
  return TERMINAL_PHASES.indexOf(phase) >= 0;
}

function parseTs(value) {
  var t = Date.parse(value || "");
  return isNaN(t) ? 0 : t;
}

// Newest timestamp at which the runner itself wrote state. Issue updated_at is
// not used: labels and unrelated comments churn it constantly.
function lastActivity(state) {
  var t = parseTs(state && state.started);
  var history = (state && Array.isArray(state.history)) ? state.history : [];
  for (var i = 0; i < history.length; i++) {
    var v = parseTs(history[i] && history[i].timestamp);
    if (v > t) t = v;
  }
  return t;
}

function minutesSince(t, now) {
  return (now - t) / 60000;
}

function isActiveRun(run) {
  return ACTIVE_RUN_STATUSES.indexOf(run && run.status) >= 0;
}

// Run titles look like "Issue #42 - ..." or "PR #42 - ...". Match the number
// as a standalone token so #420 does not match #42.
function refsNumber(text, number) {
  if (!text) return false;
  return new RegExp("(^|[^0-9A-Za-z_])#" + number + "(?![0-9])").test(text);
}

function commentsOf(result) {
  return (result && result.data) ? result.data : (result || []);
}

function hasBodyMarker(comments, needle) {
  for (var i = 0; i < comments.length; i++) {
    if ((((comments[i] && comments[i].body) || "").indexOf(needle)) >= 0) return true;
  }
  return false;
}

function latestMarkerTs(comments, prefix) {
  var best = 0;
  for (var i = 0; i < comments.length; i++) {
    if ((((comments[i] && comments[i].body) || "").indexOf(prefix)) >= 0) {
      var t = parseTs(comments[i].created_at || comments[i].createdAt);
      if (t > best) best = t;
    }
  }
  return best;
}

function olderThan(ts, minutes, now) {
  return ts > 0 && minutesSince(ts, now) >= minutes;
}

function config(env) {
  function get(name, fallback) {
    var v = env && env[name];
    return (v === undefined || v === null || v === "") ? fallback : v;
  }
  return {
    // Default to log-only; the workflow opts in with WATCHDOG_DRY_RUN=0.
    dryRun: String(get("WATCHDOG_DRY_RUN", "1")) !== "0",
    devTimeout: Number(get("WATCHDOG_DEV_TIMEOUT_MIN", "40")),
    reviewTimeout: Number(get("WATCHDOG_REVIEW_TIMEOUT_MIN", "45")),
    orchTimeout: Number(get("WATCHDOG_ORCH_TIMEOUT_MIN", "45")),
    ciSettle: Number(get("WATCHDOG_CI_SETTLE_MIN", "10")),
    agentSettle: Number(get("WATCHDOG_AGENT_SETTLE_MIN", "10")),
    cooldown: Number(get("WATCHDOG_COOLDOWN_MIN", "30")),
    maxAttempts: Number(get("WATCHDOG_MAX_ATTEMPTS", "3")),
    maxActions: Number(get("WATCHDOG_MAX_ACTIONS", "20")),
    lockMin: Number(get("WATCHDOG_LOCK_MIN", "25"))
  };
}

async function listOpenIssues(github, owner, repo) {
  var out = [];
  for (var page = 1; page <= 3; page++) {
    var res = await github.rest.issues.listForRepo({
      owner: owner, repo: repo, state: "open", per_page: 100, page: page
    });
    var data = (res && res.data) ? res.data : [];
    for (var i = 0; i < data.length; i++) {
      if (!data[i].pull_request) out.push(data[i]);
    }
    if (data.length < 100) break;
  }
  return out;
}

async function listRuns(github, owner, repo, workflowId, core) {
  try {
    var res = await github.rest.actions.listWorkflowRuns({
      owner: owner, repo: repo, workflow_id: workflowId, per_page: 50
    });
    return (res && res.data && res.data.workflow_runs) ? res.data.workflow_runs : [];
  } catch (err) {
    core.warning("Watchdog: could not list " + workflowId + " runs: " + err.message);
    return [];
  }
}

function runsFor(runs, issueNumber, prNumber) {
  var out = [];
  for (var i = 0; i < runs.length; i++) {
    var title = runs[i].display_title || runs[i].name || "";
    if (refsNumber(title, issueNumber) || (prNumber && refsNumber(title, prNumber))) {
      out.push(runs[i]);
    }
  }
  return out;
}

function latestCompletedTime(runs) {
  var best = 0;
  for (var i = 0; i < runs.length; i++) {
    if (runs[i].status !== "completed") continue;
    var t = parseTs(runs[i].updated_at || runs[i].created_at);
    if (t > best) best = t;
  }
  return best;
}

function decision(action, reason, key, extra) {
  return Object.assign({ action: action, reason: reason, key: key }, extra || {});
}

function ticketKey(issueNumber, state, last, suffix) {
  return "ticket:" + issueNumber + ":" + state.phase + ":" + (state.pr || 0) + ":" +
    new Date(last).toISOString() + (suffix ? ":" + suffix : "");
}

// Shared dedupe/cooldown/attempt checks. Returns a note when an action must not
// be taken, or null when it is allowed.
function nudgeGate(comments, key, cfg, now) {
  var marker = WATCHDOG_PREFIX + key + " -->";
  if (hasBodyMarker(comments, marker)) return "already nudged for this state";
  var last = latestMarkerTs(comments, WATCHDOG_PREFIX);
  if (last > 0 && minutesSince(last, now) < cfg.cooldown) {
    return "within cooldown (" + Math.round(minutesSince(last, now)) + "m)";
  }
  var attempts = 0;
  for (var i = 0; i < comments.length; i++) {
    if (((comments[i] && comments[i].body) || "").indexOf(WATCHDOG_PREFIX) >= 0) attempts++;
  }
  if (attempts >= cfg.maxAttempts) return "attempt cap reached (" + attempts + ")";
  return null;
}

async function inspectTicket(ctx, issue) {
  var github = ctx.github;
  var owner = ctx.owner;
  var repo = ctx.repo;
  var cfg = ctx.cfg;
  var now = ctx.now;

  var state = parseBlock(TICKET_STATE_RE, issue.body);
  if (!state) return null;
  if (isTerminal(state.phase) || state.phase === "HUMAN_REVIEW" || state.phase === "MERGE") {
    return null;
  }
  if (!ctx.h.isTrustedAuthor(issue.user)) return { note: "#" + issue.number + " untrusted author" };

  var labels = ctx.h.extractLabels(issue);
  for (var i = 0; i < SKIP_LABELS.length; i++) {
    if (labels.indexOf(SKIP_LABELS[i]) >= 0) {
      return { note: "#" + issue.number + " skip " + SKIP_LABELS[i] };
    }
  }

  var ocRuns = runsFor(ctx.runs.opencode, issue.number, state.pr);
  for (var j = 0; j < ocRuns.length; j++) {
    if (isActiveRun(ocRuns[j])) {
      return { note: "#" + issue.number + " agent run in flight" };
    }
  }
  // Give the workflow_run handler time to consume a just-finished agent run
  // before concluding the wakeup was lost.
  var agentDone = latestCompletedTime(ocRuns);
  if (agentDone > 0 && minutesSince(agentDone, now) < cfg.agentSettle) {
    return { note: "#" + issue.number + " agent just finished" };
  }

  var last = lastActivity(state);
  var idle = minutesSince(last, now);

  if (state.phase === "DEV" || state.phase === "FEEDBACK") {
    if (idle < cfg.devTimeout) return { note: "#" + issue.number + " " + state.phase + " idle " + Math.round(idle) + "m" };
    return { key: ticketKey(issue.number, state, last, "dev"), action: "run",
      reason: state.phase + " idle " + Math.round(idle) + "m" };
  }

  if (state.phase !== "REVIEW") return null;

  if (state.review_in_progress) {
    if (idle < cfg.reviewTimeout) return { note: "#" + issue.number + " review in flight" };
    return { key: ticketKey(issue.number, state, last, "stale-review"), action: "run",
      reason: "review_in_progress stale " + Math.round(idle) + "m" };
  }

  if (!state.pr) {
    if (idle < cfg.reviewTimeout) return { note: "#" + issue.number + " REVIEW without PR, idle " + Math.round(idle) + "m" };
    return { key: ticketKey(issue.number, state, last, "no-pr"), action: "run",
      reason: "REVIEW without PR idle " + Math.round(idle) + "m" };
  }

  var pr;
  try {
    pr = (await github.rest.pulls.get({ owner: owner, repo: repo, pull_number: state.pr })).data;
  } catch (err) {
    return { note: "#" + issue.number + " PR #" + state.pr + " unreadable: " + err.message };
  }
  if (!pr || pr.state === "closed") return null;

  var sha = (pr.head && pr.head.sha) || "";
  var ciRuns = [];
  for (var k = 0; k < ctx.runs.java.length; k++) {
    if (sha && ctx.runs.java[k].head_sha === sha) ciRuns.push(ctx.runs.java[k]);
  }
  for (var m = 0; m < ciRuns.length; m++) {
    if (isActiveRun(ciRuns[m])) return { note: "#" + issue.number + " CI in flight" };
  }
  var completed = null;
  for (var n = 0; n < ciRuns.length; n++) {
    if (ciRuns[n].status !== "completed") continue;
    if (!completed || parseTs(ciRuns[n].updated_at) > parseTs(completed.updated_at)) completed = ciRuns[n];
  }
  if (!completed) {
    if (idle < cfg.reviewTimeout) return { note: "#" + issue.number + " no CI record, idle " + Math.round(idle) + "m" };
    return { key: ticketKey(issue.number, state, last, "no-ci"), action: "run",
      reason: "REVIEW with no CI record idle " + Math.round(idle) + "m" };
  }
  var ciEnd = parseTs(completed.updated_at || completed.created_at);
  if (minutesSince(ciEnd, now) < cfg.ciSettle) {
    return { note: "#" + issue.number + " CI just completed" };
  }
  if (completed.conclusion === "success") {
    return { key: ticketKey(issue.number, state, last, "ci-green:" + sha.slice(0, 7)),
      action: "run", reason: "CI green but review not requested" };
  }
  return { key: ticketKey(issue.number, state, last, "ci-red:" + sha.slice(0, 7)),
    action: "heal", reason: "CI " + completed.conclusion + " and heal missing",
    sha: sha, runUrl: completed.html_url || "" };
}

async function inspectMaster(ctx, issue) {
  var labels = ctx.h.extractLabels(issue);
  if (labels.indexOf("kind/orchestrator") < 0) return null;
  if (!ctx.h.isTrustedAuthor(issue.user)) return { note: "#" + issue.number + " untrusted author" };
  if (labels.indexOf("watchdog/paused") >= 0) return { note: "#" + issue.number + " paused" };

  for (var i = 0; i < ctx.runs.orchestrator.length; i++) {
    if (isActiveRun(ctx.runs.orchestrator[i])) {
      return { note: "#" + issue.number + " orchestrator running" };
    }
  }

  if (labels.indexOf("orchestrator/locked") >= 0) {
    // Labels have no timestamp; issue.updated_at moves on label edits and
    // comments alike. Only treating an old timestamp as leaked is conservative
    // (a fresh comment would delay recovery by one sweep, never misfire).
    var updated = parseTs(issue.updated_at);
    if (!olderThan(updated, ctx.cfg.lockMin, ctx.now)) {
      return { note: "#" + issue.number + " lock recent" };
    }
    return { key: "master:" + issue.number + ":lock:" + new Date(updated).toISOString(),
      action: "unlock", reason: "leaked orchestrator lock" };
  }

  var state = parseBlock(ORCH_STATE_RE, issue.body);
  if (!state || state.status !== "IN_PROGRESS" || !state.current_task) return null;
  var task = state.current_task;
  if (typeof task !== "object" || !task.issue) return null; // legacy one-shot task
  if (isTerminal(task.phase) || task.phase === "HUMAN_REVIEW" || task.phase === "MERGE") return null;

  var last = lastActivity(state);
  var idle = minutesSince(last, ctx.now);
  if (idle < ctx.cfg.orchTimeout) {
    return { note: "#" + issue.number + " orchestrator idle " + Math.round(idle) + "m" };
  }
  return { key: "master:" + issue.number + ":" + task.issue + ":" + task.phase + ":" +
      new Date(last).toISOString(),
    action: "orchestrate", reason: "task #" + task.issue + " " + task.phase + " idle " + Math.round(idle) + "m" };
}

async function performAction(ctx, issue, plan) {
  var github = ctx.github;
  var owner = ctx.owner;
  var repo = ctx.repo;
  var marker = WATCHDOG_PREFIX + plan.key + " -->";

  if (ctx.cfg.dryRun) {
    ctx.core.info("Watchdog: DRY-RUN " + plan.action + " on #" + issue.number + " (" + plan.reason + ")");
    return;
  }

  if (plan.action === "run") {
    await ctx.h.triggerTask(issue.number, "/run\n\n" + marker);
  } else if (plan.action === "orchestrate") {
    await ctx.h.triggerTask(issue.number, "/orchestrate\n\n" + marker);
  } else if (plan.action === "heal") {
    var shortSha = (plan.sha || "").slice(0, 7);
    // Same body and marker as postCiFixOnce so the runner's dedupe agrees with
    // ours and a later real CI event does not double-post. The heal acts on the
    // PR, not the ticket issue.
    await ctx.h.triggerTask(statePrForHeal(issue),
      "/oc fix CI failed for " + shortSha + ": " + plan.runUrl + "\n" +
      CI_HEAL_PREFIX + shortSha + " -->\n" + marker +
      "\n\nRead the failing check output, fix the code on this branch, and push. Do not open a new PR.");
  } else if (plan.action === "unlock") {
    await github.rest.issues.removeLabel({
      owner: owner, repo: repo, issue_number: issue.number, name: "orchestrator/locked"
    });
  } else {
    return;
  }
  ctx.core.info("Watchdog: " + plan.action + " on #" + issue.number + " (" + plan.reason + ")");
}

module.exports = async function watchdog({ github, context, core, env }) {
  var owner = context.repo.owner;
  var repo = context.repo.repo;
  var cfg = config(env || process.env);
  var now = Date.now();
  var h = createHelpers({ github: github, context: context, core: core });

  var runs = {
    opencode: await listRuns(github, owner, repo, "opencode.yml", core),
    java: await listRuns(github, owner, repo, "ant.yml", core),
    orchestrator: await listRuns(github, owner, repo, "orchestrator.yml", core)
  };

  var issues;
  try {
    issues = await listOpenIssues(github, owner, repo);
  } catch (err) {
    core.error("Watchdog: cannot list open issues: " + err.message);
    return;
  }

  var actions = 0;
  for (var i = 0; i < issues.length; i++) {
    if (actions >= cfg.maxActions) {
      core.warning("Watchdog: action cap reached (" + cfg.maxActions + "), stopping sweep.");
      break;
    }
    var issue = issues[i];
    var ctx = {
      github: github, owner: owner, repo: repo, cfg: cfg, now: now, h: h, runs: runs, core: core
    };
    try {
      var plan = await inspectTicket(ctx, issue);
      if (!plan) plan = await inspectMaster(ctx, issue);
      if (!plan) continue;
      if (plan.note) {
        core.info("Watchdog: " + plan.note);
        continue;
      }
      var comments = commentsOf(await github.rest.issues.listComments({
        owner: owner, repo: repo, issue_number: issue.number, per_page: 100
      }));
      var gate = nudgeGate(comments, plan.key, cfg, now);
      if (gate) {
        core.info("Watchdog: #" + issue.number + " skip: " + gate);
        continue;
      }
      if (plan.action === "heal") {
        var shortSha = (plan.sha || "").slice(0, 7);
        var prComments = commentsOf(await github.rest.issues.listComments({
          owner: owner, repo: repo, issue_number: statePrForHeal(issue), per_page: 100
        }));
        if (hasBodyMarker(prComments, CI_HEAL_PREFIX + shortSha + " -->")) {
          core.info("Watchdog: #" + issue.number + " skip: CI heal already posted");
          continue;
        }
      }
      await performAction(ctx, issue, plan);
      actions++;
    } catch (err) {
      core.warning("Watchdog: #" + issue.number + " scan failed: " + err.message);
    }
  }

  core.info("Watchdog: scanned " + issues.length + " open issue(s), took " + actions +
    " action(s)" + (cfg.dryRun ? " (dry-run)" : ""));
};

// The heal path acts on the PR, not the issue. The plan carries the PR number in
// the key, but the caller needs it directly; recover it from the ticket state.
function statePrForHeal(issue) {
  var state = parseBlock(TICKET_STATE_RE, issue.body);
  return (state && state.pr) ? state.pr : issue.number;
}

// Export internals for testing.
module.exports._parseBlock = parseBlock;
module.exports._lastActivity = lastActivity;
module.exports._refsNumber = refsNumber;
module.exports._config = config;
module.exports._ticketKey = ticketKey;

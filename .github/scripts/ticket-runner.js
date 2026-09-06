/*
 * Per-ticket runner: multi-turn orchestration for individual issues.
 *
 * Drives a single issue through the DEV -> REVIEW -> FEEDBACK -> HUMAN_REVIEW -> MERGE
 * state machine, with retries and turn limits -- the same phases as the batch
 * orchestrator, but without the queue/master-issue machinery.
 *
 * State is stored as a hidden HTML comment in the issue body:
 *   <!-- TICKET_RUNNER_STATE: { ... JSON ... } -->
 *
 * Triggered by:
 *   - issue_comment:  /run (starts a new run or re-triggers a stalled one)
 *   - workflow_run:   opencode completes (advances phase)
 *   - pull_request_review: human approves/requests-changes (HUMAN_REVIEW phase)
 *
 * Module shape matches orchestrator.js: exports a single async function
 * taking { github, context, core }.
 */

'use strict';

const createHelpers = require("./orchestrator-helpers.js");

const STATE_RE = /<!-- TICKET_RUNNER_STATE:\s*([\s\S]*?)\s*-->/;
const ORCHESTRATOR_STATE_RE = /<!-- ORCHESTRATOR_STATE:/;

function parseState(body) {
  if (!body) return null;
  var m = body.match(STATE_RE);
  if (!m) return null;
  try { return JSON.parse(m[1]); } catch (_) { return null; }
}

function initState(maxTurns) {
  return {
    phase: "DEV",
    pr: null,
    turn: 0,
    max_turns: typeof maxTurns === "number" && maxTurns > 0 ? maxTurns : 3,
    retries: 0,
    started: new Date().toISOString(),
    history: []
  };
}

function serializeState(state) {
  return "<!-- TICKET_RUNNER_STATE:\n" + JSON.stringify(state, null, 2) + "\n-->";
}

function replaceOrAppendState(body, state) {
  var block = serializeState(state);
  if (STATE_RE.test(body)) {
    return body.replace(STATE_RE, block);
  }
  return (body || "") + "\n\n" + block;
}

function renderStatusSection(state, issueNumber) {
  var phaseEmoji = {
    DEV: "🔨", REVIEW: "🔍", FEEDBACK: "🔧",
    HUMAN_REVIEW: "👤", MERGE: "🚀", DONE: "✅", FAILED: "❌"
  };
  var emoji = phaseEmoji[state.phase] || "⏳";
  var lines = [
    "",
    "---",
    "### " + emoji + " Ticket Runner Status",
    "",
    "| Field | Value |",
    "| --- | --- |",
    "| **Phase** | " + state.phase + " |",
    "| **Turn** | " + state.turn + "/" + state.max_turns + " |",
    "| **Retries** | " + state.retries + "/3 |",
    "| **PR** | " + (state.pr ? "#" + state.pr : "-") + " |",
    "| **Started** | " + state.started + " |",
    ""
  ];
  return lines.join("\n");
}

var STATUS_SECTION_RE = /\r?\n---\r?\n### [^\r\n]* Ticket Runner Status\r?\n[\s\S]*?(?=\r?\n<!-- TICKET_RUNNER_STATE:|\r?\n---\r?\n|$)/;

function replaceOrAppendStatus(body, state, issueNumber) {
  var section = renderStatusSection(state, issueNumber);
  var stateBlock = serializeState(state);
  var newBody = body || "";

  if (STATUS_SECTION_RE.test(newBody)) {
    newBody = newBody.replace(STATUS_SECTION_RE, section);
  } else if (STATE_RE.test(newBody)) {
    newBody = newBody.replace(STATE_RE, section + "\n" + stateBlock);
    return newBody;
  } else {
    return newBody + section + "\n" + stateBlock;
  }

  if (STATE_RE.test(newBody)) {
    newBody = newBody.replace(STATE_RE, stateBlock);
  } else {
    newBody = newBody + "\n" + stateBlock;
  }

  return newBody;
}

module.exports = async ({ github, context, core }) => {
  const h = createHelpers({ github, context, core });
  const owner = context.repo.owner;
  const repo  = context.repo.repo;

  // ---- Determine issue number depending on event type ----

  if (context.eventName === "issue_comment") {
    await handleIssueComment();
  } else if (context.eventName === "workflow_run") {
    await handleWorkflowRun();
  } else if (context.eventName === "pull_request_review") {
    await handlePullRequestReview();
  } else {
    core.info("Ticket runner: unhandled event " + context.eventName);
  }

  // ================================================================
  // EVENT HANDLERS
  // ================================================================

  async function handleIssueComment() {
    var comment = context.payload.comment || {};
    var body = (comment.body || "").trim();
    if (body !== "/run" && !body.startsWith("/run ") && !body.startsWith("/run\n") && !body.startsWith("/run\r")) return;

    // Only collaborators/members/owners
    var assoc = comment.author_association || "";
    if (!["COLLABORATOR", "MEMBER", "OWNER"].includes(assoc)) {
      core.info("Ignoring /run from non-collaborator: " + assoc);
      return;
    }

    var issue = context.payload.issue;
    if (!issue) return;
    var issueNumber = issue.number;

    // Refuse if this is a PR context
    if (issue.pull_request) {
      core.info("Ticket runner: /run on a PR is not supported. Use /oc directly.");
      await github.rest.issues.createComment({
        owner, repo, issue_number: issueNumber,
        body: "⚠️ \`/run\` is designed for issues only. To trigger tasks on this pull request, use \`/oc\` directly (e.g. \`/oc review\` or \`/oc fix <feedback>\`)."
      });
      return;
    }

    core.info("Ticket runner: /run on issue #" + issueNumber);

    // Fetch latest issue body
    var issueData = await github.rest.issues.get({
      owner, repo, issue_number: issueNumber
    });
    var issueBody = issueData.data.body || "";

    // Guard: refuse if orchestrator is driving this issue (master issue or queue member)
    var orchCheck = await checkOrchestratorManaged(issueNumber, issueBody);
    if (orchCheck.managed) {
      core.info("Issue #" + issueNumber + " is managed by orchestrator. Refusing /run.");
      var msg = orchCheck.isSelf
        ? "⚠️ This is an orchestrator master issue. Use \`/orchestrate\` on this issue instead."
        : "⚠️ This issue is currently managed by the batch orchestrator (master issue #" + orchCheck.masterNumber + "). Use \`/orchestrate\` on the master issue instead.";
      await github.rest.issues.createComment({
        owner, repo, issue_number: issueNumber,
        body: msg
      });
      return;
    }

    var turnsMatch = body.match(/--(?:max-)?turns\s+(\d+)/);
    var customMaxTurns = turnsMatch ? parseInt(turnsMatch[1], 10) : 3;

    var state = parseState(issueBody);

    if (body.includes("--reset") || body.includes("--fresh")) {
      core.info("Ticket runner: reset requested via command flag.");
      state = null;
    } else if (state && (state.phase === "DONE" || state.phase === "FAILED")) {
      core.info("Previous run completed or failed. Starting fresh.");
      state = null;
    }

    if (!state) {
      // Initialize fresh run
      state = initState(customMaxTurns);
      state.history.push({ event: "start", timestamp: new Date().toISOString() });

      var newBody = replaceOrAppendStatus(issueBody, state, issueNumber);
      await github.rest.issues.update({
        owner, repo, issue_number: issueNumber, body: newBody
      });

      // Trigger the DEV phase
      await h.triggerTask(issueNumber);
      core.info("Ticket runner: DEV phase triggered for #" + issueNumber);
    } else {
      // Re-trigger current phase
      if (state.phase === "HUMAN_REVIEW") {
        core.info("Ticket runner: #" + issueNumber + " is waiting for human review.");
        await github.rest.issues.createComment({
          owner, repo, issue_number: issueNumber,
          body: "Ticket runner is currently waiting for human approval on PR #" + state.pr + ". Approve or request changes via the PR Review UI."
        });
        return;
      }
      core.info("Ticket runner: re-triggering phase " + state.phase + " for #" + issueNumber);
      var target = state.pr || issueNumber;
      var msg = phaseMessage(state);
      await h.triggerTask(target, msg);
    }
  }

  async function checkOrchestratorManaged(issueNumber, issueBody) {
    if (ORCHESTRATOR_STATE_RE.test(issueBody)) {
      return { managed: true, isSelf: true, masterNumber: issueNumber };
    }

    try {
      var masters = await github.rest.issues.listForRepo({
        owner, repo, labels: "kind/orchestrator", state: "open", per_page: 10
      });
      for (var i = 0; i < masters.data.length; i++) {
        var m = masters.data[i];
        if (m.number === issueNumber) {
          return { managed: true, isSelf: true, masterNumber: issueNumber };
        }
        var mBody = m.body || "";
        var match = mBody.match(/<!-- ORCHESTRATOR_STATE:\s*([\s\S]*?)\s*-->/);
        if (match) {
          try {
            var mState = JSON.parse(match[1]);
            if (mState && mState.status === "IN_PROGRESS") {
              var curr = mState.current_task;
              var currNum = (typeof curr === "object" && curr !== null) ? curr.issue : curr;
              var inCurrent = currNum === issueNumber;
              var inQueue = Array.isArray(mState.queue) && mState.queue.includes(issueNumber);
              if (inCurrent || inQueue) {
                return { managed: true, isSelf: false, masterNumber: m.number };
              }
            }
          } catch (_) {}
        }
      }
    } catch (err) {
      core.warning("checkOrchestratorManaged error: " + err.message);
    }

    return { managed: false };
  }

  async function handleWorkflowRun() {
    var conclusion = context.payload.workflow_run.conclusion;
    if (conclusion === "skipped") return;

    var runTitle = context.payload.workflow_run.display_title ||
                   (context.payload.workflow_run.head_commit && context.payload.workflow_run.head_commit.message) || "";
    var match = runTitle.match(/Issue #(\d+)/);
    if (!match) return;
    var runIssueNumber = parseInt(match[1], 10);

    core.info("Ticket runner: workflow_run for issue #" + runIssueNumber + " (conclusion: " + conclusion + ")");

    var issueBody = "";
    var state = null;

    try {
      var issueData = await github.rest.issues.get({
        owner, repo, issue_number: runIssueNumber
      });
      issueBody = issueData.data.body || "";
      state = parseState(issueBody);
    } catch (_) {}

    // If no state found directly on runIssueNumber, check if it was a PR run linked to an issue
    if (!state) {
      var found = await findIssueByPR(runIssueNumber);
      if (found) {
        var origPrNumber = runIssueNumber;
        runIssueNumber = found.issueNumber;
        issueBody = found.body;
        state = found.state;
        core.info("Ticket runner: mapped PR #" + origPrNumber + " to issue #" + runIssueNumber);
      }
    }

    if (!state) {
      core.info("No TICKET_RUNNER_STATE for #" + runIssueNumber + ". Skipping.");
      return;
    }

    if (state.phase === "DONE" || state.phase === "FAILED") {
      core.info("Ticket runner: issue #" + runIssueNumber + " already in terminal state " + state.phase);
      return;
    }

    var phaseFailed = conclusion !== "success";

    await advancePhase(runIssueNumber, state, phaseFailed);
  }

  async function handlePullRequestReview() {
    var prNumber = context.payload.pull_request.number;
    var reviewer = context.payload.review.user || {};

    if (h.isBotUser(reviewer)) {
      core.info("Ticket runner: ignoring bot review on PR #" + prNumber);
      return;
    }

    var assoc = context.payload.review.author_association;
    if (assoc && !["COLLABORATOR", "MEMBER", "OWNER"].includes(assoc)) {
      core.info("Ticket runner: ignoring PR review from non-collaborator: " + assoc);
      return;
    }

    // Find the issue whose ticket-runner state references this PR
    var found = await findIssueByPR(prNumber);
    if (!found) {
      core.info("Ticket runner: no issue with TICKET_RUNNER_STATE.pr === " + prNumber);
      return;
    }

    var state = found.state;
    if (state.phase !== "HUMAN_REVIEW") {
      core.info("Ticket runner: issue #" + found.issueNumber + " not in HUMAN_REVIEW phase. Skipping.");
      return;
    }

    var reviewState = context.payload.review.state;
    core.info("Ticket runner: human review on PR #" + prNumber + " for issue #" + found.issueNumber + ": " + reviewState);

    if (reviewState === "approved") {
      state.phase = "MERGE";
      state.history.push({ event: "human_approved", timestamp: new Date().toISOString() });
      try {
        await h.mergePR(prNumber);
        state.phase = "DONE";
        state.history.push({ event: "merged", pr: prNumber, timestamp: new Date().toISOString() });
        await updateIssueState(found.issueNumber, state);
        await closeIssueWithLabel(found.issueNumber);
      } catch (err) {
        core.error("Failed to merge PR #" + prNumber + ": " + err.message);
        state.history.push({ event: "merge_failed", error: err.message, timestamp: new Date().toISOString() });
        await updateIssueState(found.issueNumber, state);
        await github.rest.issues.createComment({
          owner, repo, issue_number: prNumber,
          body: "⚠️ Ticket runner: failed to auto-merge PR #" + prNumber + ": " + err.message
        });
      }
    } else if (reviewState === "changes_requested") {
      state.turn += 1;
      state.history.push({ event: "human_changes_requested", timestamp: new Date().toISOString() });
      if (state.turn > state.max_turns) {
        core.error("Ticket runner: issue #" + found.issueNumber + " exceeded max turns. FAILED.");
        state.phase = "FAILED";
        state.history.push({ event: "max_turns_exceeded", timestamp: new Date().toISOString() });
        await updateIssueState(found.issueNumber, state);
        await applyLabel(found.issueNumber, "agent/failed");
        await github.rest.issues.createComment({
          owner, repo, issue_number: found.issueNumber,
          body: "❌ Ticket runner: exceeded max turns (" + state.max_turns + "). Marking as failed."
        });
      } else {
        state.phase = "FEEDBACK";
        state.retries = 0;
        await updateIssueState(found.issueNumber, state);
        await h.triggerTask(prNumber, "/oc fix Address human review feedback.");
      }
    }
  }

  // ================================================================
  // PHASE ADVANCEMENT (called from workflow_run handler)
  // ================================================================

  async function advancePhase(issueNumber, state, phaseFailed) {
    core.info("Ticket runner: advancing #" + issueNumber + " phase=" + state.phase + " failed=" + phaseFailed);

    switch (state.phase) {
      case "DEV":
        await handleDevCompletion(issueNumber, state, phaseFailed);
        break;
      case "REVIEW":
        await handleReviewCompletion(issueNumber, state, phaseFailed);
        break;
      case "FEEDBACK":
        await handleFeedbackCompletion(issueNumber, state, phaseFailed);
        break;
      default:
        core.info("Ticket runner: nothing to advance for phase " + state.phase);
        break;
    }
  }

  async function handleDevCompletion(issueNumber, state, phaseFailed) {
    if (phaseFailed) {
      await retryOrFail(issueNumber, state);
      return;
    }

    // Check labels for short-circuit
    var issueData = await github.rest.issues.get({
      owner, repo, issue_number: issueNumber
    });
    var labels = h.extractLabels(issueData.data);

    if (h.SHORT_CIRCUIT_LABELS.some(function(l) { return labels.includes(l); })) {
      state.phase = "DONE";
      state.history.push({ event: "short_circuit", labels: labels.filter(function(l) { return h.SHORT_CIRCUIT_LABELS.includes(l); }), timestamp: new Date().toISOString() });
      await updateIssueState(issueNumber, state);
      core.info("Ticket runner: #" + issueNumber + " short-circuited.");
      return;
    }

    if (labels.includes("agent/done")) {
      var prNumber = await h.findPRForIssue(issueNumber);
      if (prNumber) {
        state.pr = prNumber;
        state.phase = "REVIEW";
        state.retries = 0;
        state.history.push({ event: "dev_done", pr: prNumber, timestamp: new Date().toISOString() });
        await updateIssueState(issueNumber, state);
        await h.triggerTask(prNumber, h.getReviewPrompt());
        core.info("Ticket runner: #" + issueNumber + " -> REVIEW on PR #" + prNumber);
      } else if (labels.includes("kind/feature") || labels.includes("kind/bug")) {
        // agent/done but no PR -- for bug/feature this is unexpected, retry
        core.error("Ticket runner: #" + issueNumber + " agent/done but no PR found. Retrying.");
        await retryOrFail(issueNumber, state);
      } else {
        // agent/done without PR for other kinds (investigation, documentation, direct task)
        state.phase = "DONE";
        state.history.push({ event: "done_no_pr", timestamp: new Date().toISOString() });
        await updateIssueState(issueNumber, state);
        await closeIssueWithLabel(issueNumber);
        core.info("Ticket runner: #" + issueNumber + " completed without PR.");
      }
    } else {
      // No completion label found
      await retryOrFail(issueNumber, state);
    }
  }

  async function handleReviewCompletion(issueNumber, state, phaseFailed) {
    if (phaseFailed) {
      await retryOrFail(issueNumber, state);
      return;
    }

    // Check PR labels for short-circuit
    if (state.pr) {
      var prData = await github.rest.issues.get({
        owner, repo, issue_number: state.pr
      });
      var prLabels = h.extractLabels(prData.data);
      if (h.SHORT_CIRCUIT_LABELS.some(function(l) { return prLabels.includes(l); })) {
        state.phase = "DONE";
        state.history.push({ event: "review_short_circuit", timestamp: new Date().toISOString() });
        await updateIssueState(issueNumber, state);
        return;
      }
    }

    var verdict = await h.getAgentReviewVerdict(state.pr);
    if (verdict === "approve") {
      var needsHuman = await h.needsHumanReview(issueNumber, state.pr);
      if (needsHuman) {
        state.phase = "HUMAN_REVIEW";
        state.retries = 0;
        state.history.push({ event: "review_approved", next: "HUMAN_REVIEW", timestamp: new Date().toISOString() });
        await updateIssueState(issueNumber, state);
        await github.rest.issues.createComment({
          owner, repo, issue_number: state.pr,
          body: "Agent review passed. Awaiting human approval via native GitHub PR Review UI."
        });
        core.info("Ticket runner: #" + issueNumber + " -> HUMAN_REVIEW");
      } else {
        state.phase = "MERGE";
        state.history.push({ event: "review_approved", next: "MERGE", timestamp: new Date().toISOString() });
        try {
          await h.mergePR(state.pr);
          state.phase = "DONE";
          state.history.push({ event: "merged", pr: state.pr, timestamp: new Date().toISOString() });
          await updateIssueState(issueNumber, state);
          await closeIssueWithLabel(issueNumber);
          core.info("Ticket runner: #" + issueNumber + " merged and closed.");
        } catch (err) {
          core.error("Failed to merge PR #" + state.pr + ": " + err.message);
          state.history.push({ event: "merge_failed", error: err.message, timestamp: new Date().toISOString() });
          await updateIssueState(issueNumber, state);
          await github.rest.issues.createComment({
            owner, repo, issue_number: state.pr,
            body: "⚠️ Ticket runner: failed to auto-merge PR #" + state.pr + ": " + err.message
          });
        }
      }
    } else if (verdict === "request-changes") {
      state.turn += 1;
      state.history.push({ event: "review_request_changes", turn: state.turn, timestamp: new Date().toISOString() });
      if (state.turn > state.max_turns) {
        core.error("Ticket runner: #" + issueNumber + " exceeded max turns. FAILED.");
        state.phase = "FAILED";
        state.history.push({ event: "max_turns_exceeded", timestamp: new Date().toISOString() });
        await updateIssueState(issueNumber, state);
        await applyLabel(issueNumber, "agent/failed");
        await github.rest.issues.createComment({
          owner, repo, issue_number: issueNumber,
          body: "❌ Ticket runner: exceeded max turns (" + state.max_turns + "). Marking as failed."
        });
      } else {
        state.phase = "FEEDBACK";
        state.retries = 0;
        await updateIssueState(issueNumber, state);
        await h.triggerTask(state.pr, "/oc fix Address review feedback.");
        core.info("Ticket runner: #" + issueNumber + " -> FEEDBACK (turn " + state.turn + ")");
      }
    } else {
      // No verdict found -- retry
      core.warning("Ticket runner: no verdict found for PR #" + state.pr + ". Retrying review.");
      await retryOrFail(issueNumber, state);
    }
  }

  async function handleFeedbackCompletion(issueNumber, state, phaseFailed) {
    if (phaseFailed) {
      await retryOrFail(issueNumber, state);
      return;
    }

    // Check PR labels for short-circuit
    if (state.pr) {
      var prData = await github.rest.issues.get({
        owner, repo, issue_number: state.pr
      });
      var prLabels = h.extractLabels(prData.data);
      if (h.SHORT_CIRCUIT_LABELS.some(function(l) { return prLabels.includes(l); })) {
        state.phase = "DONE";
        state.history.push({ event: "feedback_short_circuit", timestamp: new Date().toISOString() });
        await updateIssueState(issueNumber, state);
        return;
      }
    }

    // After feedback, go back to REVIEW
    state.phase = "REVIEW";
    state.retries = 0;
    state.history.push({ event: "feedback_done", timestamp: new Date().toISOString() });
    await updateIssueState(issueNumber, state);
    await h.triggerTask(state.pr, h.getReviewPrompt());
    core.info("Ticket runner: #" + issueNumber + " -> REVIEW (after feedback)");
  }

  // ================================================================
  // SHARED UTILITIES
  // ================================================================

  async function retryOrFail(issueNumber, state) {
    state.retries += 1;
    state.history.push({ event: "retry", retries: state.retries, phase: state.phase, timestamp: new Date().toISOString() });
    if (state.retries >= 3) {
      core.error("Ticket runner: #" + issueNumber + " phase " + state.phase + " reached max retries. FAILED.");
      state.phase = "FAILED";
      state.history.push({ event: "max_retries", timestamp: new Date().toISOString() });
      await updateIssueState(issueNumber, state);
      await applyLabel(issueNumber, "agent/failed");
      await github.rest.issues.createComment({
        owner, repo, issue_number: issueNumber,
        body: "❌ Ticket runner: phase " + state.phase + " failed after 3 retries."
      });
    } else {
      var target = state.pr || issueNumber;
      var msg = phaseMessage(state);
      await updateIssueState(issueNumber, state);
      await h.triggerTask(target, msg);
      core.info("Ticket runner: #" + issueNumber + " retrying (attempt " + (state.retries + 1) + "/3)");
    }
  }

  function phaseMessage(state) {
    if (state.phase === "FEEDBACK") return "/oc fix Address review feedback.";
    if (state.phase === "REVIEW") return h.getReviewPrompt();
    return "/oc Please proceed with this task.";
  }

  async function updateIssueState(issueNumber, state) {
    // Re-fetch in case body changed
    var issueData = await github.rest.issues.get({
      owner, repo, issue_number: issueNumber
    });
    var body = issueData.data.body || "";
    var newBody = replaceOrAppendStatus(body, state, issueNumber);
    await github.rest.issues.update({
      owner, repo, issue_number: issueNumber, body: newBody
    });
  }

  async function closeIssueWithLabel(issueNumber) {
    try {
      await github.rest.issues.addLabels({
        owner, repo, issue_number: issueNumber, labels: ["agent/done"]
      });
    } catch (err) {
      core.warning("Failed to add agent/done to #" + issueNumber + ": " + err.message);
    }
    try {
      await github.rest.issues.update({
        owner, repo, issue_number: issueNumber, state: "closed", state_reason: "completed"
      });
    } catch (err) {
      core.warning("Failed to close #" + issueNumber + ": " + err.message);
    }
  }

  async function applyLabel(issueNumber, label) {
    try {
      await github.rest.issues.addLabels({
        owner, repo, issue_number: issueNumber, labels: [label]
      });
    } catch (err) {
      core.warning("Failed to add " + label + " to #" + issueNumber + ": " + err.message);
    }
  }

  /** Search open issues for one whose TICKET_RUNNER_STATE.pr matches the given PR number. */
  async function findIssueByPR(prNumber) {
    // First, try to fetch PR to extract head branch ref or Closes/Fixes/Resolves #N
    try {
      var prData = await github.rest.pulls.get({
        owner, repo, pull_number: prNumber
      });
      var headRef = (prData.data.head && prData.data.head.ref) || "";
      var prBody = prData.data.body || "";

      var issueMatch = headRef.match(/^opencode\/issue(\d+)-/) ||
                       prBody.match(/(?:Closes|Fixes|Resolves)\s+#(\d+)/i);
      if (issueMatch) {
        var linkedIssue = parseInt(issueMatch[1], 10);
        var issueData = await github.rest.issues.get({
          owner, repo, issue_number: linkedIssue
        });
        var state = parseState(issueData.data.body);
        if (state && (state.pr === prNumber || !state.pr)) {
          return { issueNumber: linkedIssue, body: issueData.data.body, state: state };
        }
      }
    } catch (_) { /* fall through to search */ }

    // Fallback: search recent open issues
    try {
      var issues = await github.rest.issues.listForRepo({
        owner, repo, state: "open", per_page: 50, sort: "updated", direction: "desc"
      });
      for (var i = 0; i < issues.data.length; i++) {
        var issue = issues.data[i];
        if (issue.pull_request) continue;
        var st = parseState(issue.body);
        if (st && st.pr === prNumber) {
          return { issueNumber: issue.number, body: issue.body, state: st };
        }
      }
    } catch (err) {
      core.warning("findIssueByPR search failed: " + err.message);
    }
    return null;
  }
};

// Export internals for testing
module.exports._parseState = parseState;
module.exports._initState = initState;
module.exports._serializeState = serializeState;
module.exports._replaceOrAppendState = replaceOrAppendState;
module.exports._replaceOrAppendStatus = replaceOrAppendStatus;
module.exports._renderStatusSection = renderStatusSection;

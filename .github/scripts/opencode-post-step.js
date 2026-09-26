/*
 * OpenCode post-run step: applies the agent/* label and closes
 * the issue if the work product is the comment (not a PR).
 *
 * Triggered by: .github/workflows/opencode.yml (the "Apply agent/* label" step)
 * Reads:  context.payload.issue, context.payload.comment, PREV_CONCLUSION env var
 * Writes: agent/* label, removes agent/in-progress, optionally closes the issue
 *
 * Idempotent: respects an existing agent/* label; safe to re-run.
 * Same module shape as .github/scripts/orchestrator.js.
 */

'use strict';

const AGENT_COMPLETION_RE = /^agent\/(done|investigated|skip|needs-info|blocked|failed)$/;
const CLOSE_KINDS = ['kind/investigate', 'kind/question'];

function isPRContext(context) {
  return !!(context.payload.issue && context.payload.issue.pull_request);
}

function isRefusalComment(body) {
  if (!body) return false;
  return /^\s*##\s*(?:🤖\s*)?Refusal\b/im.test(body);
}

function isNeedsInfoComment(body) {
  if (!body) return false;
  return /needs more info from reporter|needs the following|suggested next:\s*needs-info/i.test(body);
}

function isTriageComment(body) {
  if (!body) return false;
  return /## .*Triage/i.test(body);
}

function isTriageClearComment(body) {
  return isTriageComment(body) && !isNeedsInfoComment(body) && !isRefusalComment(body);
}

function isTriggerComment(body) {
  if (!body) return false;
  return /(^|\s)\/(oc|run|orchestrate)(\s|$)/.test(body);
}

function isDuplicateSignal(body) {
  if (!body) return false;
  return /duplicate-of-#\d+/i.test(body);
}

function isPRCreationComment(body) {
  if (!body) return false;
  return /\b(?:Created|Opened) PR #\d+\b/i.test(body);
}

function isInvestigationReport(body) {
  if (!body) return false;
  return /## .*Investigation Report/i.test(body);
}

function isAgentHeading(body) {
  return body && (isRefusalComment(body) || isNeedsInfoComment(body) || isInvestigationReport(body) ||
    isTriageComment(body) || isPRCreationComment(body));
}

function findLatestAgentComment(comments) {
  for (let i = comments.length - 1; i >= 0; i--) {
    const c = comments[i];
    if (c.body && !isTriggerComment(c.body) && isAgentHeading(c.body)) {
      return c.body;
    }
  }
  return '';
}

function decideAgentLabel({ existing, conclusion, latestComment, labels, isPR }) {
  // Structured reports are deliberate work products: honor them even when the
  // run later died (triage runs often fail at action finalization after the
  // comment already landed). Failure maps to failed only with no report.
  if (isRefusalComment(latestComment)) {
    return { label: 'agent/skip', reason: 'refusal detected in comment' };
  }
  if (isNeedsInfoComment(latestComment)) {
    return { label: 'agent/needs-info', reason: 'needs-info detected in comment' };
  }
  if (isInvestigationReport(latestComment)) {
    return { label: 'agent/investigated', reason: 'investigation report heading detected (verb-override)' };
  }
  if (isDuplicateSignal(latestComment)) {
    return { label: 'agent/duplicate', reason: 'duplicate-of link detected in comment' };
  }
  if (isPRCreationComment(latestComment)) {
    return { label: 'agent/done', reason: 'PR creation comment detected' };
  }
  if (isTriageClearComment(latestComment)) {
    return { label: null, clearNeedsInfo: true, clearFailed: true, reason: 'clear triage, no blocking label' };
  }
  if (conclusion === 'failure' || conclusion === 'cancelled') {
    return { label: 'agent/failed', reason: 'run concluded: ' + conclusion };
  }
  if (existing && existing !== 'agent/failed') {
    return { label: existing, reason: 'existing agent/* label respected' };
  }
  if (isPR) {
    return { label: 'agent/done', reason: 'PR context' };
  }
  return { label: 'agent/done', reason: 'default for non-investigation kinds' };
}

function shouldClose({ isPR, latestComment, labels, agentLabel }) {
  if (isPR) return false;
  if (agentLabel === 'agent/duplicate') return true;
  if (agentLabel !== 'agent/investigated') return false;
  if (isInvestigationReport(latestComment)) {
    return true;
  }
  return CLOSE_KINDS.some(k => labels.includes(k));
}

module.exports = async ({ github, context, core }) => {
  const number = context.payload.issue.number;
  const isPR = isPRContext(context);
  const { owner, repo } = context.repo;
  const conclusion = process.env.PREV_CONCLUSION || 'success';

  core.info('Post-step for #' + number + ' (isPR=' + isPR + ', conclusion=' + conclusion + ')');

  let labels = [];
  let issueState = 'open';
  try {
    const { data: issue } = await github.rest.issues.get({
      owner, repo, issue_number: number,
    });
    labels = (issue.labels || []).map(l => (typeof l === 'string') ? l : l.name);
    issueState = issue.state || 'open';
  } catch (err) {
    core.warning('Could not fetch labels: ' + err.message);
  }
  core.info('Labels: ' + (labels.join(', ') || '(none)') + ' (state=' + issueState + ')');

  let latestComment = '';
  try {
    const comments = await github.paginate(github.rest.issues.listComments, {
      owner, repo, issue_number: number, per_page: 100,
    });
    latestComment = findLatestAgentComment(comments);
  } catch (err) {
    core.warning('Could not fetch comments: ' + err.message);
  }

  const existingAgent = labels.find(l => AGENT_COMPLETION_RE.test(l));
  const decision = decideAgentLabel({
    existing: existingAgent,
    conclusion,
    latestComment,
    labels,
    isPR,
  });
  core.info('Decision: ' + decision.label + ' (' + decision.reason + ')');

  if (decision.label === null) {
    if (decision.clearNeedsInfo && labels.includes('agent/needs-info')) {
      try {
        await github.rest.issues.removeLabel({
          owner, repo, issue_number: number, name: 'agent/needs-info',
        });
        core.info('Cleared stale agent/needs-info after clear triage');
      } catch (err) {
        core.warning('Failed to clear needs-info: ' + err.message);
      }
    }
    if (decision.clearFailed && labels.includes('agent/failed')) {
      try {
        await github.rest.issues.removeLabel({
          owner, repo, issue_number: number, name: 'agent/failed',
        });
        core.info('Cleared stale agent/failed after clear triage');
      } catch (err) {
        core.warning('Failed to clear failed: ' + err.message);
      }
    }
    if (!decision.clearNeedsInfo && !decision.clearFailed) {
      core.info('Clear triage, no label change');
    }
  } else {
    if (existingAgent && existingAgent !== decision.label) {
      try {
        await github.rest.issues.removeLabel({
          owner, repo, issue_number: number, name: existingAgent,
        });
        core.info('Removed old label: ' + existingAgent);
      } catch (err) {
        core.warning('Failed to remove old label: ' + err.message);
      }
    }

    try {
      await github.rest.issues.addLabels({
        owner, repo, issue_number: number, labels: [decision.label],
      });
      core.info('Applied ' + decision.label);
    } catch (err) {
      core.warning('Failed to apply label: ' + err.message);
    }
  }

  try {
    await github.rest.issues.removeLabel({
      owner, repo, issue_number: number, name: 'agent/in-progress',
    });
  } catch (_) { }

  if (shouldClose({ isPR, latestComment, labels, agentLabel: decision.label })) {
    if (issueState === 'closed') {
      core.info('Issue is already closed; skipping close call');
    } else {
      try {
        await github.rest.issues.update({
          owner, repo, issue_number: number, state: 'closed',
        });
        core.info('Closed #' + number);
      } catch (err) {
        core.warning('Failed to close: ' + err.message);
      }
    }
  } else {
    core.info('Not closing #' + number);
  }
};

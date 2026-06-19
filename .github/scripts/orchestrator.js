module.exports = async ({ github, context, core }) => {
  // Helper to trigger a task
  async function triggerTask(issueNumber, message = "/oc Please proceed with this task.") {
    core.info(`Triggering task #${issueNumber} via comment...`);
    await github.rest.issues.createComment({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: issueNumber,
      body: message
    });
  }

  // --- Multi-Step Orchestrator Helpers ---
  function isMultiStepTask(task) {
    return typeof task === 'object' && task !== null;
  }

  function getTaskIssueNumber(task) {
    if (!task) return null;
    return isMultiStepTask(task) ? task.issue : task;
  }

  function initTaskObject(issueNumber) {
    return {
      issue: issueNumber,
      pr: null,
      phase: 'DEV',
      turn: 0,
      max_turns: 3,
      retries: 0
    };
  }

  async function findPRForIssue(issueNumber) {
    const pulls = await github.rest.pulls.list({
      owner: context.repo.owner,
      repo: context.repo.repo,
      state: 'open',
      per_page: 50
    });
    for (const pr of pulls.data) {
      if (pr.head.ref.startsWith(`opencode/issue${issueNumber}-`) || (pr.body && pr.body.includes(`Closes #${issueNumber}`))) {
        return pr.number;
      }
    }
    return null;
  }

  async function getAgentReviewVerdict(prNumber) {
    const comments = await github.rest.issues.listComments({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: prNumber,
      per_page: 50
    });
    // Search backwards for the latest verdict
    for (let i = comments.data.length - 1; i >= 0; i--) {
      const body = comments.data[i].body || '';
      if (body.includes('Verdict: approve')) return 'approve';
      if (body.includes('Verdict: request-changes')) return 'request-changes';
    }
    return null;
  }

  async function needsHumanReview(issueNumber) {
    const issue = await github.rest.issues.get({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: issueNumber
    });
    const labels = (issue.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
    return !labels.includes('auto-merge');
  }

  async function mergePR(prNumber) {
    core.info(`Merging PR #${prNumber}...`);
    const pr = await github.rest.pulls.get({
      owner: context.repo.owner,
      repo: context.repo.repo,
      pull_number: prNumber
    });
    await github.rest.pulls.merge({
      owner: context.repo.owner,
      repo: context.repo.repo,
      pull_number: prNumber,
      merge_method: 'squash'
    });
    try {
      await github.rest.git.deleteRef({
        owner: context.repo.owner,
        repo: context.repo.repo,
        ref: `heads/${pr.data.head.ref}`
      });
    } catch (e) {
      core.warning(`Could not delete branch ${pr.data.head.ref}: ${e.message}`);
    }
  }
  // --- End Helpers ---

  // Helper to render a 20-char ASCII progress bar
  function renderBar(percent) {
    const barLength = 20;
    const filledLength = Math.round((percent / 100) * barLength);
    const emptyLength = barLength - filledLength;
    return `\`[${'='.repeat(filledLength)}${'>'.repeat(filledLength > 0 && emptyLength > 0 ? 1 : 0)}${'.'.repeat(Math.max(0, emptyLength - (filledLength > 0 && emptyLength > 0 ? 1 : 0)))}]\` ${percent}%`;
  }

  // Helper to update the master issue description and JSON state
  async function updateMasterIssue(issueNumber, state) {
    const totalTasks = state.queue.length + state.completed.length + state.failed.length + (state.current_task ? 1 : 0);
    const completedCount = state.completed.length;
    const failedCount = state.failed.length;
    const handledCount = completedCount + failedCount;

    const progressPercent = totalTasks > 0 ? Math.round((handledCount / totalTasks) * 100) : 0;
    const successPercent = totalTasks > 0 ? Math.round((completedCount / totalTasks) * 100) : 0;

    const progressBar = renderBar(progressPercent);
    const successBar = renderBar(successPercent);

    let currentTaskInfo = '-';
    if (state.current_task) {
      const tNum = getTaskIssueNumber(state.current_task);
      if (isMultiStepTask(state.current_task)) {
        const prInfo = state.current_task.pr ? ` → PR #${state.current_task.pr}` : '';
        currentTaskInfo = `#${tNum}${prInfo} (Phase: ${state.current_task.phase}, Turn ${state.current_task.turn}/${state.current_task.max_turns}, Attempt ${state.current_task.retries + 1}/3)`;
      } else {
        currentTaskInfo = `#${tNum} (Attempt ${state.retries + 1}/3)`;
      }
    }

    const statusEmoji = state.status === 'COMPLETED' ? '✅' : (state.status === 'IN_PROGRESS' ? '⏳' : '💤');

    // Human-readable status table
    let markdown = `## ${statusEmoji} Task Orchestrator Status

| Metric | Details |
| --- | --- |
| **Status** | ${state.status} |
| **Progress** | ${progressBar} (${handledCount}/${totalTasks} handled) |
| **Success** | ${successBar} (${completedCount}/${totalTasks} succeeded) |
| **Current Task** | ${currentTaskInfo} |

### 📋 Queue List
`;

    // Build status map (taskNum -> {box, emoji, label})
    const statusMap = new Map();
    for (const t of state.completed) {
      statusMap.set(t, { box: 'x', emoji: '✅', label: 'Succeeded' });
    }
    if (state.current_task) {
      const tNum = getTaskIssueNumber(state.current_task);
      const label = isMultiStepTask(state.current_task) 
        ? `In Progress (Phase: ${state.current_task.phase})` 
        : `In Progress, Attempt ${state.retries + 1}/3`;
      statusMap.set(tNum, { box: ' ', emoji: '⏳', label });
    }
    for (const t of state.failed) {
      statusMap.set(t, { box: 'FAIL', emoji: '❌', label: 'Failed all attempts' });
    }
    for (const t of state.queue) {
      statusMap.set(t, { box: ' ', emoji: '📋', label: 'Queued' });
    }

    // Render in original order (state.order), not status-grouped order
    for (const taskNum of state.order) {
      const info = statusMap.get(taskNum);
      if (!info) continue;
      markdown += `- [${info.box}] #${taskNum} ${info.emoji} *(${info.label})*\n`;
    }

    // Append state JSON as hidden comment
    markdown += `\n<!-- ORCHESTRATOR_STATE:\n${JSON.stringify(state, null, 2)}\n-->`;

    core.info("Updating master issue body...");
    await github.rest.issues.update({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: issueNumber,
      body: markdown
    });
  }

  // Helper to close the master issue when status becomes COMPLETED
  async function closeMasterIfDone(issueNumber, state) {
    if (state.status !== 'COMPLETED') return;
    try {
      const issue = await github.rest.issues.get({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: issueNumber
      });
      if (issue.data.state === 'closed') {
        core.info(`Master issue #${issueNumber} already closed.`);
        return;
      }
      try {
        await github.rest.issues.addLabels({
          owner: context.repo.owner,
          repo: context.repo.repo,
          issue_number: issueNumber,
          labels: ['agent/done']
        });
      } catch (err) {
        core.warning(`Failed to add agent/done label to #${issueNumber}: ${err.message}`);
      }
      await github.rest.issues.update({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: issueNumber,
        state: 'closed',
        state_reason: 'completed'
      });
      core.info(`Master issue #${issueNumber} closed (status: COMPLETED).`);
    } catch (err) {
      core.warning(`Failed to close master issue #${issueNumber}: ${err.message}`);
    }
  }

  // 1. Find the Master Orchestrator Issue
  const issues = await github.rest.issues.listForRepo({
    owner: context.repo.owner,
    repo: context.repo.repo,
    labels: 'kind/orchestrator',
    state: 'open',
    per_page: 1
  });

  if (issues.data.length === 0) {
    core.info("No open issues with label 'kind/orchestrator' found. Skipping.");
    return;
  }

  const masterIssue = issues.data[0];
  const masterIssueNumber = masterIssue.number;
  core.info(`Found Master Orchestrator Issue: #${masterIssueNumber}`);

  // 2. Mutex Lock Check
  const labels = masterIssue.labels.map(l => l.name);
  if (labels.includes('orchestrator/locked')) {
    core.info("Orchestrator is locked by another running action. Exiting.");
    return;
  }

  // Apply Lock
  core.info("Acquiring lock by adding 'orchestrator/locked' label...");
  await github.rest.issues.addLabels({
    owner: context.repo.owner,
    repo: context.repo.repo,
    issue_number: masterIssueNumber,
    labels: ['orchestrator/locked']
  });

  try {
    const body = masterIssue.body || '';
    const jsonMatch = body.match(/<!-- ORCHESTRATOR_STATE:\s*([\s\S]*?)\s*-->/);
    let state = null;

    if (jsonMatch) {
      try {
        state = JSON.parse(jsonMatch[1]);
        core.info("Successfully loaded existing state from JSON.");

        state.status = (typeof state.status === 'string') ? state.status : 'IDLE';
        // current_task can now be an object. Only cast if it's a primitive number/string.
        if (state.current_task != null && typeof state.current_task !== 'object') {
          state.current_task = Number(state.current_task);
        } else if (state.current != null && typeof state.current !== 'object') {
          state.current_task = Number(state.current);
        }
        state.queue = Array.isArray(state.queue) ? state.queue.map(Number) : [];
        state.completed = Array.isArray(state.completed) ? state.completed.map(Number)
          : (Array.isArray(state.done) ? state.done.map(Number) : []);
        state.failed = Array.isArray(state.failed) ? state.failed.map(Number) : [];
        state.retries = (typeof state.retries === 'number') ? state.retries : 0;
        state.history = Array.isArray(state.history) ? state.history : [];
        state.order = Array.isArray(state.order) ? state.order.map(Number) : [];

        // Migration: if state.order is empty (older state), build from status-grouped
        // arrays. Order will be in status-grouped order, not original — but stable.
        if (state.order.length === 0) {
          const order = [];
          for (const t of state.completed) order.push(t);
          if (state.current_task) order.push(getTaskIssueNumber(state.current_task));
          for (const t of state.failed) order.push(t);
          for (const t of state.queue) order.push(t);
          state.order = order;
        }
      } catch (e) {
        core.warning("Failed to parse hidden JSON block. Re-initializing from Markdown. Error: " + e.message);
        state = null;
      }
    }

    // Initialize from Markdown if no valid JSON state exists
    if (!state) {
      core.info("Initializing fresh state from Markdown checklist...");
      const lines = body.split('\n');
      const queue = [];
      const completed = [];
      const failed = [];
      const order = [];

      let currentTask = null;
      const currentMatch = body.match(/Current:\s*(?:#)?(\d+|-)/i);
      if (currentMatch && currentMatch[1] !== '-') {
        currentTask = parseInt(currentMatch[1], 10);
      }

      for (const line of lines) {
        // Match "- [ ] #123", "- [x] #123", "- [FAIL] #123"
        const match = line.match(/^-\s*\[\s*([ xX]|FAIL)\s*\]\s*(?:.*?)#(\d+)/);
        if (match) {
          const statusChar = match[1].trim().toLowerCase();
          const taskNum = parseInt(match[2], 10);
          order.push(taskNum);
          if (statusChar === 'x') {
            completed.push(taskNum);
          } else if (statusChar === 'fail') {
            failed.push(taskNum);
          } else {
            // Only add to queue if it's not the currently active task
            if (taskNum !== currentTask) {
              queue.push(taskNum);
            }
          }
        }
      }

      state = {
        status: 'IDLE',
        current_task: currentTask,
        queue,
        completed,
        failed,
        retries: 0,
        history: [],
        order
      };
      core.info(`Initialized State: ${JSON.stringify(state)}`);
    }

    // 2.5. Self-Healing Guard: If current task is already done, mark it as success and advance
    // For multi-step tasks, this guard is skipped since "done" requires checking phases and PRs.
    if (state.current_task && !isMultiStepTask(state.current_task)) {
      try {
        const tNum = getTaskIssueNumber(state.current_task);
        const currentIssue = await github.rest.issues.get({
          owner: context.repo.owner,
          repo: context.repo.repo,
          issue_number: tNum
        });
        const sgLabels = (currentIssue.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
        const sgCompletionLabels = ['agent/done', 'agent/investigated', 'agent/skip', 'agent/blocked', 'agent/needs-info'];
        const sgHasLabel = sgCompletionLabels.some(l => sgLabels.includes(l));
        const sgIsDone = currentIssue.data.state === 'closed' || sgHasLabel;
        if (sgIsDone) {
          const reason = currentIssue.data.state === 'closed'
            ? 'ALREADY closed on GitHub'
            : `has completion label (${sgCompletionLabels.find(l => sgLabels.includes(l))})`;
          core.info(`Self-Healing Guard: Task #${tNum} ${reason}! Advancing queue.`);
          state.completed.push(tNum);
          state.history.push({ event: 'task_success', task: tNum, timestamp: new Date().toISOString() });
          
          // Advance to the next task immediately
          if (state.queue.length > 0) {
            state.current_task = initTaskObject(state.queue.shift());
            state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
            await triggerTask(getTaskIssueNumber(state.current_task));
          } else {
            state.current_task = null;
            state.retries = 0;
            state.status = 'COMPLETED';
            await github.rest.issues.createComment({
              owner: context.repo.owner,
              repo: context.repo.repo,
              issue_number: masterIssueNumber,
              body: "✅ All tasks in the queue have been processed."
            });
          }
          await updateMasterIssue(masterIssueNumber, state);
          await closeMasterIfDone(masterIssueNumber, state);
          return; // Exit fully since we transitioned state successfully!
        }
      } catch (err) {
        core.warning(`Failed to fetch status for active task #${getTaskIssueNumber(state.current_task)}: ${err.message}`);
      }
    }

    // 3. Process Event
    if (context.eventName === 'issue_comment') {
      core.info("Manual trigger via comment detected.");
      
      if (state.status !== 'IN_PROGRESS') {
        state.status = 'IN_PROGRESS';
      }

      // If no current task or manually restarting the current task, start the first queue task
      if (!state.current_task) {
        if (state.queue.length > 0) {
          state.current_task = initTaskObject(state.queue.shift());
          state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
          await triggerTask(getTaskIssueNumber(state.current_task));
        } else {
          state.status = 'COMPLETED';
          core.info("Queue is empty. Marking as COMPLETED.");
        }
      } else {
        core.info(`Orchestrator already in progress. Retriggering current task #${getTaskIssueNumber(state.current_task)}`);
        const target = isMultiStepTask(state.current_task) && state.current_task.pr ? state.current_task.pr : getTaskIssueNumber(state.current_task);
        const msg = isMultiStepTask(state.current_task) && state.current_task.phase === 'FEEDBACK' ? "/oc fix Address review feedback." 
                  : isMultiStepTask(state.current_task) && state.current_task.phase === 'REVIEW' ? "/oc review" 
                  : "/oc Please proceed with this task.";
        await triggerTask(target, msg);
      }

      await updateMasterIssue(masterIssueNumber, state);
      await closeMasterIfDone(masterIssueNumber, state);

    } else if (context.eventName === 'workflow_run') {
      core.info("Workflow run completion trigger detected.");
      
      if (!state.current_task) {
        core.info("No active task in progress. Skipping.");
        return;
      }

      const conclusion = context.payload.workflow_run.conclusion;
      if (conclusion === 'skipped') return;

      const runTitle = context.payload.workflow_run.display_title || '';
      const match = runTitle.match(/Issue #(\d+)/);
      if (!match) return;
      
      const runIssueNumber = parseInt(match[1], 10);
      const tNum = getTaskIssueNumber(state.current_task);
      const prNum = isMultiStepTask(state.current_task) ? state.current_task.pr : null;

      if (runIssueNumber !== tNum && runIssueNumber !== prNum) {
        core.info(`Mismatch: run #${runIssueNumber} vs task issue #${tNum} / PR #${prNum}. Skipping.`);
        return;
      }

      let isDone = false; // Indicates if the entire task is finished and we should pop the next one

      if (!isMultiStepTask(state.current_task)) {
        // ONE-SHOT LOGIC
        const currentIssue = await github.rest.issues.get({
          owner: context.repo.owner, repo: context.repo.repo, issue_number: tNum
        });
        const issueLabels = (currentIssue.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
        const completionLabels = ['agent/done', 'agent/investigated', 'agent/skip', 'agent/blocked', 'agent/needs-info'];
        const isComplete = currentIssue.data.state === 'closed' || completionLabels.some(l => issueLabels.includes(l));

        if (conclusion === 'success' && isComplete) {
          core.info(`Task #${tNum} succeeded (one-shot)!`);
          state.completed.push(tNum);
          isDone = true;
        } else {
          state.retries += 1;
          if (state.retries >= 3) {
            core.error(`Task #${tNum} reached max retries. FAILED.`);
            state.failed.push(tNum);
            isDone = true;
          } else {
            await triggerTask(tNum);
            await updateMasterIssue(masterIssueNumber, state);
            return;
          }
        }
      } else {
        // MULTI-STEP LOGIC
        const task = state.current_task;
        let phaseFailed = conclusion !== 'success';
        
        switch (task.phase) {
          case 'DEV': {
            const currentIssue = await github.rest.issues.get({
              owner: context.repo.owner, repo: context.repo.repo, issue_number: task.issue
            });
            const labels = (currentIssue.data.labels || []).map(l => l.name);
            if (!phaseFailed) {
              if (labels.includes('agent/skip') || labels.includes('agent/needs-info') || labels.includes('agent/investigated') || labels.includes('agent/blocked')) {
                // Short-circuit completion
                state.completed.push(task.issue);
                isDone = true;
              } else if (labels.includes('agent/done')) {
                const foundPr = await findPRForIssue(task.issue);
                if (foundPr) {
                  task.pr = foundPr;
                  task.phase = 'REVIEW';
                  task.retries = 0;
                  await triggerTask(task.pr, "/oc review");
                } else {
                  // No PR found, treat as completed
                  state.completed.push(task.issue);
                  isDone = true;
                }
              } else {
                phaseFailed = true; // no completion labels found
              }
            }
            break;
          }
          case 'REVIEW': {
            const currentPR = await github.rest.issues.get({
              owner: context.repo.owner, repo: context.repo.repo, issue_number: task.pr
            });
            const labels = (currentPR.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
            if (!phaseFailed) {
              if (labels.includes('agent/skip') || labels.includes('agent/needs-info') || labels.includes('agent/investigated') || labels.includes('agent/blocked')) {
                state.completed.push(task.issue);
                isDone = true;
              } else {
                const verdict = await getAgentReviewVerdict(task.pr);
                if (verdict === 'approve') {
                  const needsHuman = await needsHumanReview(task.issue);
                  if (needsHuman) {
                    task.phase = 'HUMAN_REVIEW';
                    task.retries = 0;
                    await github.rest.issues.createComment({
                      owner: context.repo.owner, repo: context.repo.repo, issue_number: task.pr,
                      body: "Agent review passed. Awaiting human approval via native GitHub PR Review UI."
                    });
                  } else {
                    task.phase = 'MERGE';
                    await mergePR(task.pr);
                    state.completed.push(task.issue);
                    isDone = true;
                  }
                } else if (verdict === 'request-changes') {
                  task.turn += 1;
                  if (task.turn > task.max_turns) {
                    core.error(`Task #${task.issue} reached max turns. FAILED.`);
                    state.failed.push(task.issue);
                    isDone = true;
                  } else {
                    task.phase = 'FEEDBACK';
                    task.retries = 0;
                    await triggerTask(task.pr, "/oc fix Address review feedback.");
                  }
                } else {
                  phaseFailed = true; // Verdict not found
                }
              }
            }
            break;
          }
          case 'FEEDBACK': {
            const currentPR = await github.rest.issues.get({
              owner: context.repo.owner, repo: context.repo.repo, issue_number: task.pr
            });
            const labels = (currentPR.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
            if (!phaseFailed) {
              if (labels.includes('agent/skip') || labels.includes('agent/needs-info') || labels.includes('agent/investigated') || labels.includes('agent/blocked')) {
                state.completed.push(task.issue);
                isDone = true;
              } else {
                task.phase = 'REVIEW';
                task.retries = 0;
                await triggerTask(task.pr, "/oc review");
              }
            }
            break;
          }
          default:
            break;
        }

        if (phaseFailed) {
          task.retries += 1;
          if (task.retries >= 3) {
            core.error(`Task #${task.issue} phase ${task.phase} reached max retries. FAILED.`);
            state.failed.push(task.issue);
            isDone = true;
          } else {
            const target = task.pr ? task.pr : task.issue;
            const msg = task.phase === 'FEEDBACK' ? "/oc fix Address review feedback." : task.phase === 'REVIEW' ? "/oc review" : "/oc Please proceed with this task.";
            await triggerTask(target, msg);
            await updateMasterIssue(masterIssueNumber, state);
            return;
          }
        }
      }

      // Trigger next task if isDone
      if (isDone) {
        if (state.queue.length > 0) {
          state.current_task = initTaskObject(state.queue.shift());
          state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
          await triggerTask(getTaskIssueNumber(state.current_task));
        } else {
          state.current_task = null;
          state.status = 'COMPLETED';
          await github.rest.issues.createComment({
            owner: context.repo.owner, repo: context.repo.repo, issue_number: masterIssueNumber,
            body: "✅ All tasks in the queue have been processed."
          });
        }
      }

      await updateMasterIssue(masterIssueNumber, state);
      await closeMasterIfDone(masterIssueNumber, state);

    } else if (context.eventName === 'pull_request_review') {
      core.info("Pull request review trigger detected.");
      
      if (!state.current_task || !isMultiStepTask(state.current_task)) return;
      const task = state.current_task;
      
      if (task.phase !== 'HUMAN_REVIEW') return;
      if (task.pr !== context.payload.pull_request.number) return;
      
      let botLogin = '';
      try {
        const { data: user } = await github.rest.users.getAuthenticated();
        botLogin = user.login;
      } catch (err) {}
      
      if (context.payload.review.user.login === botLogin) {
        core.info("Ignoring review from bot identity.");
        return;
      }
      
      const reviewState = context.payload.review.state;
      if (reviewState === 'approved') {
        task.phase = 'MERGE';
        await mergePR(task.pr);
        state.completed.push(task.issue);
        
        if (state.queue.length > 0) {
          state.current_task = initTaskObject(state.queue.shift());
          state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
          await triggerTask(getTaskIssueNumber(state.current_task));
        } else {
          state.current_task = null;
          state.status = 'COMPLETED';
          await github.rest.issues.createComment({
            owner: context.repo.owner, repo: context.repo.repo, issue_number: masterIssueNumber,
            body: "✅ All tasks in the queue have been processed."
          });
        }
      } else if (reviewState === 'changes_requested') {
        task.turn += 1;
        if (task.turn > task.max_turns) {
          state.failed.push(task.issue);
          if (state.queue.length > 0) {
            state.current_task = initTaskObject(state.queue.shift());
            await triggerTask(getTaskIssueNumber(state.current_task));
          } else {
            state.current_task = null;
            state.status = 'COMPLETED';
          }
        } else {
          task.phase = 'FEEDBACK';
          task.retries = 0;
          await triggerTask(task.pr, "/oc fix Address human review feedback.");
        }
      }
      
      await updateMasterIssue(masterIssueNumber, state);
      await closeMasterIfDone(masterIssueNumber, state);
    }

  } finally {
    // 4. Release Lock
    core.info("Releasing lock by removing 'orchestrator/locked' label...");
    await github.rest.issues.removeLabel({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: masterIssueNumber,
      name: 'orchestrator/locked'
    }).catch(err => core.error("Failed to remove 'orchestrator/locked' label: " + err.message));
  }
};

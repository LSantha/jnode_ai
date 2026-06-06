module.exports = async ({ github, context, core }) => {
  // Helper to trigger a task
  async function triggerTask(issueNumber) {
    core.info(`Triggering task #${issueNumber} via comment...`);
    await github.rest.issues.createComment({
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: issueNumber,
      body: "/oc Please proceed with this task."
    });
  }

  // Helper to update the master issue description and JSON state
  async function updateMasterIssue(issueNumber, state) {
    const totalTasks = state.queue.length + state.completed.length + state.failed.length + (state.current_task ? 1 : 0);
    const completedCount = state.completed.length;
    const progressPercent = totalTasks > 0 ? Math.round((completedCount / totalTasks) * 100) : 0;
    
    // Beautiful Progress Bar
    const barLength = 20;
    const filledLength = Math.round((progressPercent / 100) * barLength);
    const emptyLength = barLength - filledLength;
    const progressBar = `\`[${'='.repeat(filledLength)}${'>'.repeat(filledLength > 0 && emptyLength > 0 ? 1 : 0)}${'.'.repeat(Math.max(0, emptyLength - (filledLength > 0 && emptyLength > 0 ? 1 : 0)))}]\` ${progressPercent}%`;

    let currentTaskInfo = '-';
    if (state.current_task) {
      currentTaskInfo = `#${state.current_task} (Attempt ${state.retries + 1}/3)`;
    }

    const statusEmoji = state.status === 'COMPLETED' ? '✅' : (state.status === 'IN_PROGRESS' ? '⏳' : '💤');

    // Human-readable status table
    let markdown = `## ${statusEmoji} Task Orchestrator Status

| Metric | Details |
| --- | --- |
| **Status** | ${state.status} |
| **Progress** | ${progressBar} (${completedCount}/${totalTasks} completed) |
| **Current Task** | ${currentTaskInfo} |

### 📋 Queue List
`;

    // Render the checkbox list
    for (const t of state.completed) {
      markdown += `- [x] #${t} ✅ *(Succeeded)*\n`;
    }
    if (state.current_task) {
      markdown += `- [ ] #${state.current_task} ⏳ *(In Progress, Attempt ${state.retries + 1}/3)*\n`;
    }
    for (const t of state.failed) {
      markdown += `- [FAIL] #${t} ❌ *(Failed all attempts)*\n`;
    }
    for (const t of state.queue) {
      markdown += `- [ ] #${t}\n`;
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
        state.current_task = (state.current_task != null) ? Number(state.current_task)
          : (state.current != null) ? Number(state.current) : null;
        state.queue = Array.isArray(state.queue) ? state.queue.map(Number) : [];
        state.completed = Array.isArray(state.completed) ? state.completed.map(Number)
          : (Array.isArray(state.done) ? state.done.map(Number) : []);
        state.failed = Array.isArray(state.failed) ? state.failed.map(Number) : [];
        state.retries = (typeof state.retries === 'number') ? state.retries : 0;
        state.history = Array.isArray(state.history) ? state.history : [];
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
        history: []
      };
      core.info(`Initialized State: ${JSON.stringify(state)}`);
    }

    // 2.5. Self-Healing Guard: If current task is already done, mark it as success and advance
    // "Done" = issue closed OR has one of the agent completion labels
    if (state.current_task) {
      try {
        const currentIssue = await github.rest.issues.get({
          owner: context.repo.owner,
          repo: context.repo.repo,
          issue_number: state.current_task
        });
        const sgLabels = (currentIssue.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
        const sgCompletionLabels = ['agent/done', 'agent/investigated', 'agent/skip', 'agent/blocked', 'agent/needs-info'];
        const sgHasLabel = sgCompletionLabels.some(l => sgLabels.includes(l));
        const sgIsDone = currentIssue.data.state === 'closed' || sgHasLabel;
        if (sgIsDone) {
          const reason = currentIssue.data.state === 'closed'
            ? 'ALREADY closed on GitHub'
            : `has completion label (${sgCompletionLabels.find(l => sgLabels.includes(l))})`;
          core.info(`Self-Healing Guard: Task #${state.current_task} ${reason}! Advancing queue.`);
          state.completed.push(state.current_task);
          state.history.push({ event: 'task_success', task: state.current_task, timestamp: new Date().toISOString() });
          
          // Advance to the next task immediately
          if (state.queue.length > 0) {
            state.current_task = state.queue.shift();
            state.retries = 0;
            state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
            await triggerTask(state.current_task);
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
          return; // Exit fully since we transitioned state successfully!
        }
      } catch (err) {
        core.warning(`Failed to fetch status for active task #${state.current_task}: ${err.message}`);
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
          state.current_task = state.queue.shift();
          state.retries = 0;
          state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
          await triggerTask(state.current_task);
        } else {
          state.status = 'COMPLETED';
          core.info("Queue is empty. Marking as COMPLETED.");
        }
      } else {
        core.info(`Orchestrator already in progress. Retriggering current task #${state.current_task}`);
        await triggerTask(state.current_task);
      }

      await updateMasterIssue(masterIssueNumber, state);

    } else if (context.eventName === 'workflow_run') {
      core.info("Workflow run completion trigger detected.");
      
      if (!state.current_task) {
        core.info("No active task in progress. Skipping.");
        return;
      }

      // Fetch conclusion
      const conclusion = context.payload.workflow_run.conclusion;
      
      // 1. Ignore non-definitive skipped runs
      if (conclusion === 'skipped') {
        core.info(`Workflow run completed with conclusion "${conclusion}". Ignoring.`);
        return;
      }

      // Fetch the display title of the completed workflow run
      const runTitle = context.payload.workflow_run.display_title || '';
      
      // Extract issue number from display title (e.g. "Issue #469 — Add SIMD-accelerated...")
      const match = runTitle.match(/Issue #(\d+)/);
      if (!match) {
        core.info(`Workflow run display title "${runTitle}" does not contain expected "Issue #[number]" format. Skipping.`);
        return;
      }
      
      const runIssueNumber = parseInt(match[1], 10);
      core.info(`Extracted completed Task #${runIssueNumber} from workflow run title: "${runTitle}"`);

      // Issue ID mismatch protection (strict sequential validation guard)
      if (runIssueNumber !== state.current_task) {
        core.info(`Workflow run for Task #${runIssueNumber} does not match current active task #${state.current_task}. Waiting for current task.`);
        return;
      }

      // Fetch current task issue details
      const currentIssue = await github.rest.issues.get({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: state.current_task
      });
      const isIssueClosed = currentIssue.data.state === 'closed';
      const issueLabels = (currentIssue.data.labels || []).map(l => (typeof l === 'string') ? l : l.name);
      const completionLabels = ['agent/done', 'agent/investigated', 'agent/skip', 'agent/blocked', 'agent/needs-info'];
      const hasCompletionLabel = completionLabels.some(l => issueLabels.includes(l));
      const isComplete = isIssueClosed || hasCompletionLabel;

      if (conclusion === 'success' && isComplete) {
        core.info(`Task #${state.current_task} succeeded and is verified closed!`);
        state.completed.push(state.current_task);
        state.history.push({ event: 'task_success', task: state.current_task, timestamp: new Date().toISOString() });
        state.current_task = null;
        state.retries = 0;
      } else {
        core.warning(`Task #${state.current_task} failed validation (Conclusion: ${conclusion}, Closed: ${isIssueClosed}, Completion label: ${hasCompletionLabel}).`);
        state.retries += 1;
        
        if (state.retries >= 3) {
          core.error(`Task #${state.current_task} reached max retry limit (3). Marking as FAILED.`);
          state.failed.push(state.current_task);
          state.history.push({ event: 'task_failed_max_retries', task: state.current_task, timestamp: new Date().toISOString() });
          state.current_task = null;
          state.retries = 0;
        } else {
          core.info(`Retrying task #${state.current_task}. Attempt ${state.retries + 1}/3...`);
          state.history.push({ event: 'task_retry', task: state.current_task, attempt: state.retries + 1, timestamp: new Date().toISOString() });
          await triggerTask(state.current_task);
          await updateMasterIssue(masterIssueNumber, state);
          return; // Exit here, do not look for the next task yet!
        }
      }

      // Trigger next task if current task finished (success or completely failed)
      if (state.queue.length > 0) {
        state.current_task = state.queue.shift();
        state.retries = 0;
        state.history.push({ event: 'start_task', task: state.current_task, timestamp: new Date().toISOString() });
        await triggerTask(state.current_task);
      } else {
        state.status = 'COMPLETED';
        core.info("All tasks in the queue have been successfully processed!");
        await github.rest.issues.createComment({
          owner: context.repo.owner,
          repo: context.repo.repo,
          issue_number: masterIssueNumber,
          body: "✅ All tasks in the queue have been processed."
        });
      }

      await updateMasterIssue(masterIssueNumber, state);
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

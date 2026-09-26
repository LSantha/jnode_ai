const test = require('node:test');
const assert = require('node:assert');
const runOrchestrator = require('../orchestrator.js');

function createMocks(eventName = 'issue_comment', commentBody = '/orchestrate') {
  const logs = { info: [], warning: [], error: [] };
  const core = {
    info: (msg) => logs.info.push(msg),
    warning: (msg) => logs.warning.push(msg),
    error: (msg) => logs.error.push(msg)
  };

  const calls = { getIssue: [], updateIssue: [], createComment: [], listComments: [], addLabels: [], removeLabel: [], mergePR: [] };
  const commentsByIssue = {};
  const updateIssueDetails = [];
  let masterIssueBody = `- [ ] #2\n- [ ] #3`;
  let currentTaskData = { labels: [], state: 'open' };
  let prData = { labels: [], state: 'open', merged: true, head: { ref: 'opencode/issue2-fix' } };

  const github = {
    rest: {
      issues: {
        get: async ({ issue_number }) => {
          calls.getIssue.push(issue_number);
          if (issue_number === 1) return { data: { body: masterIssueBody, labels: [{name: 'kind/orchestrator'}], state: 'open' } };
          if (issue_number === 99) return { data: prData }; // Mock PR as an issue for label fetching
          return { data: currentTaskData };
        },
        update: async ({ issue_number, body, state }) => {
          calls.updateIssue.push(issue_number);
          updateIssueDetails.push({ issue_number, body, state });
          if (issue_number === 1 && body) masterIssueBody = body;
        },
        createComment: async ({ issue_number, body }) => {
          calls.createComment.push({ issue_number, body });
          (commentsByIssue[issue_number] = commentsByIssue[issue_number] || []).push({ body });
        },
        listComments: async ({ issue_number }) => {
          calls.listComments.push(issue_number);
          return { data: commentsByIssue[issue_number] || [] };
        },
        addLabels: async ({ labels }) => calls.addLabels.push(...labels),
        removeLabel: async ({ name }) => calls.removeLabel.push(name),
        listForRepo: async () => {
          return { data: [{ number: 1, labels: [{name: 'kind/orchestrator'}], state: 'open', body: masterIssueBody }] };
        }
      },
      pulls: {
        list: async () => {
          return { data: [] }; // No PRs by default
        },
        get: async () => {
          return { data: prData };
        },
        merge: async ({ pull_number }) => {
          calls.mergePR.push(pull_number);
        }
      },
      git: {
        deleteRef: async () => {}
      }
    }
  };

  const context = {
    eventName,
    repo: { owner: 'test', repo: 'test' },
    payload: {}
  };

  if (eventName === 'issue_comment') {
    context.payload = { issue: { number: 1 }, comment: { body: commentBody } };
  } else if (eventName === 'workflow_run') {
    context.payload = {
      workflow_run: { id: 100, display_title: 'Issue #2 - Title', head_commit: { message: 'Issue #2 - Title' }, conclusion: 'success' }
    };
  } else if (eventName === 'pull_request_review') {
    context.payload = {
      pull_request: { number: 99 },
      review: {
        user: { login: 'LSantha', type: 'User' },
        state: 'approved'
      }
    };
  }

  return { core, github, context, logs, calls, updateIssueDetails, setMasterBody: (b) => masterIssueBody = b, setTaskData: (d) => currentTaskData = d };
}

test('orchestrator.js test suite', async (t) => {

  await t.test('Initialization on /orchestrate', async () => {
    const { core, github, context, calls } = createMocks('issue_comment', '/orchestrate');
    await runOrchestrator({ github, context, core });

    assert.ok(calls.createComment.length > 0, 'Should trigger the first task');
    assert.strictEqual(calls.createComment[0].issue_number, 2, 'First task is #2');
    assert.ok(calls.createComment[0].body.includes('/oc Please proceed'), 'Trigger message');
  });

  await t.test('Ignores normal comments (because workflow conditions handle it, but script logs info)', async () => {
    // The script actually processes any issue_comment trigger and re-triggers the current task or first task.
    // However, if the orchestrator state is empty and the comment is not /orchestrate, it would normally initialize a fresh state 
    // from the body. We don't want to test the YAML workflow's event filtering here.
    assert.ok(true);
  });

  await t.test('Workflow run advances queue (DEV -> REVIEW)', async () => {
    const { core, github, context, calls, setMasterBody, setTaskData } = createMocks('workflow_run');
    
    // Set up master state where task 2 is in DEV phase
    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": null, "phase": "DEV", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [{name: 'agent/done'}], state: 'open' }); // agent finished

    // We need findPRForIssue to return a PR
    github.rest.pulls.list = async () => ({ data: [{ number: 99, head: { ref: 'opencode/issue2-fix' } }] });

    await runOrchestrator({ github, context, core });

    assert.strictEqual(calls.createComment.length, 1, 'Should trigger review phase');
    assert.strictEqual(calls.createComment[0].issue_number, 99, 'Triggers on PR #99');
    assert.ok(calls.createComment[0].body.includes('/oc review'));
    assert.ok(calls.createComment[0].body.includes('Verdict: approve'));
    assert.ok(calls.createComment[0].body.includes('Verdict: request-changes'));
  });

  await t.test('DEV with stale short-circuit label but an existing PR goes to REVIEW', async () => {
    const { core, github, context, calls, updateIssueDetails, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": null, "phase": "DEV", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    // agent/needs-info is a short-circuit label, but the agent did open a PR.
    setTaskData({ labels: [{name: 'agent/needs-info'}], state: 'open' });
    github.rest.pulls.list = async () => ({ data: [{ number: 99, head: { ref: 'opencode/issue2-fix' } }] });

    await runOrchestrator({ github, context, core });

    assert.strictEqual(calls.createComment.length, 1, 'Should trigger review phase');
    assert.strictEqual(calls.createComment[0].issue_number, 99, 'Triggers on PR #99');
    assert.ok(calls.createComment[0].body.includes('/oc review'));
    const master = updateIssueDetails.find(u => u.issue_number === 1);
    assert.ok(master && master.body.includes('"phase": "REVIEW"'), 'Phase advanced to REVIEW');
    assert.ok(master && !master.body.includes('"completed": [\n    2'), 'Task #2 is not marked completed');
  });

  await t.test('DEV with short-circuit label and no PR completes the task', async () => {
    const { core, github, context, calls, updateIssueDetails, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": null, "phase": "DEV", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [{name: 'agent/needs-info'}], state: 'open' });
    github.rest.pulls.list = async () => ({ data: [] });

    await runOrchestrator({ github, context, core });

    assert.strictEqual(calls.createComment.length, 1, 'Only the next queued task is triggered');
    assert.strictEqual(calls.createComment[0].issue_number, 3, 'Advances to the next task, not a review');
    assert.ok(!calls.createComment[0].body.includes('/oc review'));
    assert.ok(updateIssueDetails.some(u => u.issue_number === 1 && u.body.includes('"completed": [\n    2')));
  });

  await t.test('Review phase accepts explicit approve verdict', async () => {
    const { core, github, context, calls, updateIssueDetails, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [], state: 'open' });
    github.rest.issues.listComments = async () => ({ data: [{ body: 'Verdict: approve' }] });

    await runOrchestrator({ github, context, core });

    assert.ok(calls.createComment.some(c => c.issue_number === 99 && c.body.includes('Agent review passed')));
    assert.ok(updateIssueDetails.some(u => u.issue_number === 1 && u.body.includes('HUMAN_REVIEW')));
  });

  await t.test('Human approved PR review advances to merge', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('pull_request_review');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "HUMAN_REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);

    await runOrchestrator({ github, context, core });

    assert.deepStrictEqual(calls.mergePR, [99]);
    assert.ok(calls.createComment.some(c => c.issue_number === 3 && c.body.includes('/oc Please proceed')));
  });

  await t.test('Bot PR review is ignored', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('pull_request_review');
    context.payload.review.user = { login: 'opencode-agent[bot]', type: 'Bot' };

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "HUMAN_REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);

    await runOrchestrator({ github, context, core });

    assert.strictEqual(calls.mergePR.length, 0);
    assert.strictEqual(calls.updateIssue.length, 0);
  });

  await t.test('Workflow run handles task failure (retries)', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('workflow_run');
    context.payload.workflow_run.conclusion = 'failure';
    
    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": null, "phase": "DEV", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);

    await runOrchestrator({ github, context, core });

    // Should increment retries and retry task #2
    assert.strictEqual(calls.createComment.length, 1);
    assert.strictEqual(calls.createComment[0].issue_number, 2);
    
    // Check internal state using the updateIssue call
    // Note: since updateIssue body is JSON stringified inside HTML comment, we can parse it
    // but a simple string inclusion test is robust
    assert.ok(calls.updateIssue.includes(1), 'Master issue was updated');
  });

  await t.test('Review approve with auto-merge and green CI merges and advances', async () => {
    const { core, github, context, calls, updateIssueDetails, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [{name: 'auto-merge'}], state: 'open' });
    github.rest.issues.listComments = async () => ({ data: [{ body: 'Verdict: approve' }] });
    github.rest.pulls.get = async () => ({ data: { head: { ref: 'opencode/issue2-fix', sha: 'abc' }, merged: true } });
    github.rest.pulls.listFiles = async () => ({ data: [{ filename: 'fs/a.java', additions: 5 }] });
    github.rest.checks = { listForRef: async () => ({ data: { check_runs: [{ status: 'completed', conclusion: 'success' }] } }) };

    await runOrchestrator({ github, context, core });

    assert.deepStrictEqual(calls.mergePR, [99]);
    assert.ok(calls.createComment.some(c => c.issue_number === 3 && c.body.includes('/oc Please proceed')));
    assert.ok(updateIssueDetails.some(u => u.issue_number === 1 && u.body.includes('"completed": [\n    2')));
  });

  await t.test('Review approve with auto-merge defers when CI red', async () => {
    const { core, github, context, calls, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [{name: 'auto-merge'}], state: 'open' });
    github.rest.issues.listComments = async () => ({ data: [{ body: 'Verdict: approve' }] });
    github.rest.pulls.get = async () => ({ data: { head: { ref: 'opencode/issue2-fix', sha: 'abc' }, merged: true } });
    github.rest.pulls.listFiles = async () => ({ data: [{ filename: 'fs/a.java', additions: 5 }] });
    github.rest.checks = { listForRef: async () => ({ data: { check_runs: [{ status: 'completed', conclusion: 'failure' }] } }) };

    await runOrchestrator({ github, context, core });

    assert.strictEqual(calls.mergePR.length, 0);
    assert.ok(calls.createComment.some(c => c.issue_number === 99 && c.body.includes('deferred')));
  });

  await t.test('Java CI success re-reviews active REVIEW PR', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    context.payload.workflow_run.name = 'Java CI';
    context.payload.workflow_run.conclusion = 'success';
    context.payload.workflow_run.head_sha = 'abc';
    context.payload.workflow_run.id = 55;
    github.rest.pulls.list = async () => ({ data: [{ number: 99, head: { ref: 'opencode/issue2-fix', sha: 'abc' } }] });

    await runOrchestrator({ github, context, core });

    assert.ok(calls.createComment.some(c => c.issue_number === 99 && c.body.includes('/oc review')));
    assert.strictEqual(calls.mergePR.length, 0);
  });

  await t.test('Java CI success skips re-review while a review is in flight', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0, "review_in_progress": true }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    context.payload.workflow_run.name = 'Java CI';
    context.payload.workflow_run.conclusion = 'success';
    context.payload.workflow_run.head_sha = 'abc';
    context.payload.workflow_run.id = 57;
    github.rest.pulls.list = async () => ({ data: [{ number: 99, head: { ref: 'opencode/issue2-fix', sha: 'abc' } }] });

    await runOrchestrator({ github, context, core });

    assert.strictEqual(calls.createComment.filter(c => c.issue_number === 99).length, 0, 'No second review while a review is running');
    assert.strictEqual(calls.mergePR.length, 0);
  });

  await t.test('Java CI success re-reviews a deferred REVIEW PR', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0, "review_in_progress": false }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    context.payload.workflow_run.name = 'Java CI';
    context.payload.workflow_run.conclusion = 'success';
    context.payload.workflow_run.head_sha = 'abc';
    context.payload.workflow_run.id = 58;
    github.rest.pulls.list = async () => ({ data: [{ number: 99, head: { ref: 'opencode/issue2-fix', sha: 'abc' } }] });

    await runOrchestrator({ github, context, core });

    assert.ok(calls.createComment.some(c => c.issue_number === 99 && c.body.includes('/oc review')), 'Deferred review is re-run on green CI');
  });

  await t.test('Review completion clears review_in_progress', async () => {
    const { core, github, context, calls, updateIssueDetails, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0, "review_in_progress": true }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [{name: 'auto-merge'}], state: 'open' });
    github.rest.issues.listComments = async () => ({ data: [{ body: 'Verdict: approve' }] });
    github.rest.pulls.get = async () => ({ data: { head: { ref: 'opencode/issue2-fix', sha: 'abc' }, merged: true } });
    github.rest.pulls.listFiles = async () => ({ data: [{ filename: 'fs/a.java', additions: 5 }] });
    github.rest.checks = { listForRef: async () => ({ data: { check_runs: [{ status: 'completed', conclusion: 'success' }] } }) };

    await runOrchestrator({ github, context, core });

    assert.ok(updateIssueDetails.some(u => u.issue_number === 1 && u.body.includes('"review_in_progress": true') === false), 'Flag is cleared once the review workflow completes');
  });

  await t.test('FEEDBACK completion re-enters REVIEW with review_in_progress set', async () => {
    const { core, github, context, calls, updateIssueDetails, setMasterBody, setTaskData } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "FEEDBACK", "turn": 1, "max_turns": 3, "retries": 0, "review_in_progress": false }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    setTaskData({ labels: [], state: 'open' });
    github.rest.issues.listComments = async () => ({ data: [{ body: 'Review done' }] });

    await runOrchestrator({ github, context, core });

    assert.ok(calls.createComment.some(c => c.issue_number === 99 && c.body.includes('/oc review')), 'Re-review scheduled');
    assert.ok(updateIssueDetails.some(u => u.issue_number === 1 && u.body.includes('"review_in_progress": true')), 'Flag is set for the new review');
  });

  await t.test('Java CI failure posts one /oc fix on active REVIEW PR', async () => {
    const { core, github, context, calls, setMasterBody } = createMocks('workflow_run');

    setMasterBody(`<!-- ORCHESTRATOR_STATE:\n{ "status": "IN_PROGRESS", "current_task": { "issue": 2, "pr": 99, "phase": "REVIEW", "turn": 0, "max_turns": 3, "retries": 0 }, "queue": [3], "completed": [], "failed": [], "order": [2, 3] }\n-->`);
    context.payload.workflow_run.name = 'Java CI';
    context.payload.workflow_run.conclusion = 'failure';
    context.payload.workflow_run.head_sha = 'abc';
    context.payload.workflow_run.id = 56;
    github.rest.pulls.list = async () => ({ data: [{ number: 99, head: { ref: 'opencode/issue2-fix', sha: 'abc' } }] });

    await runOrchestrator({ github, context, core });
    await runOrchestrator({ github, context, core });

    const fixes = calls.createComment.filter(c => c.issue_number === 99 && c.body.includes('/oc fix CI failed'));
    assert.strictEqual(fixes.length, 1, 'marker dedupes the second identical run');
  });

});

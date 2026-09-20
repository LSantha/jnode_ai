const test = require('node:test');
const assert = require('node:assert');
const runPostStep = require('../opencode-post-step.js');

function createMocks() {
  const logs = { info: [], warning: [], error: [] };
  const core = {
    info: (msg) => logs.info.push(msg),
    warning: (msg) => logs.warning.push(msg),
    error: (msg) => logs.error.push(msg)
  };
  
  const calls = { getIssue: 0, listComments: 0, addLabels: [], removeLabel: [], updateIssue: [] };
  let mockIssueData = { labels: [], state: 'open' };
  let mockComments = [];
  
  const github = {
    rest: {
      issues: {
        get: async () => { calls.getIssue++; return { data: mockIssueData }; },
        addLabels: async ({ labels }) => { calls.addLabels.push(...labels); },
        removeLabel: async ({ name }) => { calls.removeLabel.push(name); },
        update: async ({ state }) => { calls.updateIssue.push(state); }
      }
    },
    paginate: async () => { calls.listComments++; return mockComments; }
  };
  
  const context = { 
    repo: { owner: 'test', repo: 'test' }, 
    payload: { issue: { number: 1 } } 
  };
  
  return { core, github, context, logs, calls, setIssueData: (d) => mockIssueData = d, setComments: (c) => mockComments = c };
}

test('opencode-post-step.js test suite', async (t) => {
  
  await t.test('Applies agent/failed when conclusion is failure', async () => {
    const { core, github, context, calls } = createMocks();
    process.env.PREV_CONCLUSION = 'failure';
    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/failed'));
  });

  await t.test('Applies agent/skip when refusal comment is found', async () => {
    const { core, github, context, calls, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setComments([{ body: '## 🤖 Refusal\nOut of scope.' }]);
    
    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/skip'));
    assert.strictEqual(calls.updateIssue.length, 0, 'Should not close on skip');
  });

  await t.test('Overrides existing agent/done with agent/skip if refusal comment posted', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: ['agent/done'], state: 'open' });
    setComments([{ body: '## 🤖 Refusal\nThis is a refusal during feedback.' }]);
    
    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/skip'));
    assert.ok(calls.removeLabel.includes('agent/done'));
  });

  await t.test('Respects existing agent/done if no new heading is present', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: ['agent/done'], state: 'open' });
    setComments([{ body: 'Just a normal comment' }]);
    
    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/done'));
  });

  await t.test('Applies agent/investigated and closes if kind/investigate', async () => {
    const { core, github, context, calls, setIssueData } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: ['kind/investigate'], state: 'open' });
    
    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/investigated'));
    assert.ok(calls.updateIssue.includes('closed'));
  });

  await t.test('Does not close PRs even if investigated', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    context.payload.issue.pull_request = {}; // Is PR
    setComments([{ body: '## 🤖 Investigation Report' }]);
    
    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/investigated'));
    assert.strictEqual(calls.updateIssue.length, 0);
  });
  
  await t.test('Removes agent/in-progress', async () => {
    const { core, github, context, calls } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    await runPostStep({ github, context, core });
    assert.ok(calls.removeLabel.includes('agent/in-progress'));
  });

  await t.test('Vague triage with reporter literal applies agent/needs-info', async () => {
    const { core, github, context, calls, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setComments([{ body: '## Triage\n\n- [ ] **Repro:** needs more info from reporter\n- [ ] **Suggested next:** needs-info' }]);

    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/needs-info'));
  });

  await t.test('Vague triage with needs-the-following applies agent/needs-info', async () => {
    const { core, github, context, calls, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setComments([{ body: '## Triage\n\nNeeds the following before work can start:\n1. QEMU cmd?' }]);

    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/needs-info'));
  });

  await t.test('Clear triage applies no agent label', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: ['kind/bug', 'area/fs'], state: 'open' });
    setComments([{ body: '## Triage\n\n- [x] **Repro:** 1. boot 2. mkdir\n- [x] **Suggested next:** fix' }]);

    await runPostStep({ github, context, core });
    assert.strictEqual(calls.addLabels.length, 0, 'Clear triage must not add any agent label');
  });

  await t.test('Clear re-triage removes stale agent/needs-info', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: ['kind/bug', 'agent/needs-info'], state: 'open' });
    setComments([
      { body: '## Triage\n\n- [ ] **Repro:** needs more info from reporter' },
      { body: 'reporter reply with serial log' },
      { body: '## Triage\n\n- [x] **Repro:** 1. boot 2. mkdir\n- [x] **Suggested next:** fix' }
    ]);

    await runPostStep({ github, context, core });
    assert.ok(calls.removeLabel.includes('agent/needs-info'));
    assert.strictEqual(calls.addLabels.length, 0, 'Clear re-triage must not add any agent label');
  });

  await t.test('Vague triage overrides existing agent/done', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: ['agent/done'], state: 'open' });
    setComments([{ body: '## Triage\n\n- [ ] **Repro:** needs more info from reporter' }]);

    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/needs-info'));
    assert.ok(calls.removeLabel.includes('agent/done'));
  });

  await t.test('Trigger quoting triage is not a report', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'success';
    setIssueData({ labels: [{ name: 'kind/bug' }], state: 'open' });
    setComments([{ body: '/oc triage issue #42\n\nTriage run ONLY. Output is exactly one ## Triage comment plus kind/area label edits.' }]);

    await runPostStep({ github, context, core });
    assert.ok(!calls.removeLabel.includes('agent/needs-info'));
    assert.ok(calls.addLabels.includes('agent/done'), 'trigger-only falls to default, applies no triage logic');
  });

  await t.test('Failure with real vague triage still applies needs-info', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'failure';
    setIssueData({ labels: [{ name: 'kind/bug' }], state: 'open' });
    setComments([{ body: '## Triage\n\n- [ ] **Repro:** needs more info from reporter' }]);

    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/needs-info'));
    assert.ok(!calls.addLabels.includes('agent/failed'));
  });

  await t.test('Failure with real clear triage clears needs-info', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'failure';
    setIssueData({ labels: [{ name: 'kind/bug' }, { name: 'agent/needs-info' }], state: 'open' });
    setComments([{ body: '## Triage\n\n- [x] **Repro:** 1. boot\n- [x] **Suggested next:** fix' }]);

    await runPostStep({ github, context, core });
    assert.ok(calls.removeLabel.includes('agent/needs-info'));
    assert.ok(!calls.addLabels.includes('agent/failed'));
  });

  await t.test('Failure with no report still applies agent/failed', async () => {
    const { core, github, context, calls, setIssueData, setComments } = createMocks();
    process.env.PREV_CONCLUSION = 'failure';
    setIssueData({ labels: [{ name: 'kind/bug' }], state: 'open' });
    setComments([{ body: 'Just a normal comment' }]);

    await runPostStep({ github, context, core });
    assert.ok(calls.addLabels.includes('agent/failed'));
  });
});

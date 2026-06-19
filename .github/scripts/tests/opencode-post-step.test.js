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
});

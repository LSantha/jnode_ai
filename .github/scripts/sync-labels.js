#!/usr/bin/env node
/*
 * One-shot label bootstrap for the jnode-issue-resolver skill.
 *
 * Ensures the kind/*, agent/*, and area/* label families exist in the repo.
 * Idempotent: existing labels are left untouched, missing ones are created
 * with the documented color and description.
 *
 * Usage:
 *   GITHUB_TOKEN=ghp_xxx GITHUB_REPOSITORY=LSantha/jnode_ai \
 *     node .github/scripts/sync-labels.js
 *
 * Optional flags:
 *   --dry-run    Print what would be created/updated, make no API calls
 *   --update     Also update color/description of labels that already exist
 *   --delete     Delete labels listed in --remove (comma-separated)
 *   --remove     Comma-separated label names to delete
 *
 * Notes:
 *   - Does NOT touch existing labels kind/orchestrator or orchestrator/locked.
 *   - Paginates the labels list; works on repos with >100 labels.
 *   - Uses REST via the same Octokit pattern as orchestrator.js when GH_TOKEN
 *     is set, otherwise falls back to the gh CLI (subprocess).
 */

'use strict';

const { execFileSync } = require('child_process');

const KIND = [
  { name: 'kind/bug',         color: '1d76db', description: 'Confirmed bug report with a repro or stack trace.' },
  { name: 'kind/feature',     color: '1d76db', description: 'New feature or enhancement request.' },
  { name: 'kind/investigate', color: '1d76db', description: 'Asks the agent to investigate and report back, not to fix.' },
  { name: 'kind/wiki',        color: '1d76db', description: 'Documentation change; delegated to the update-wiki skill.' },
  { name: 'kind/review',      color: '1d76db', description: 'Asks the agent to perform a code review on a PR.' },
  { name: 'kind/chore',       color: '1d76db', description: 'Refactor, typo sweep, dead-code removal; no behavior change.' },
  { name: 'kind/question',    color: '1d76db', description: 'User question; expected output is an investigation comment.' },
  { name: 'kind/triage',      color: '1d76db', description: 'Asks the agent to triage a new issue (labels + checklist).' },
  { name: 'auto-merge',       color: '0e8a16', description: 'Skip human review; orchestrator auto-merges after agent approval.' },
];

const AGENT = [
  { name: 'agent/in-progress',  color: 'cccccc', description: 'The OpenCode agent is currently working on this issue.' },
  { name: 'agent/needs-info',   color: 'cccccc', description: 'The agent has posted clarifying questions; waiting for the reporter.' },
  { name: 'agent/blocked',      color: 'cccccc', description: 'The agent is blocked on an external dependency or build failure.' },
  { name: 'agent/done',         color: 'cccccc', description: 'The agent finished successfully; PR opened or comment posted.' },
  { name: 'agent/failed',       color: 'cccccc', description: 'The agent exhausted its retries without producing a result.' },
  { name: 'agent/skip',         color: 'cccccc', description: 'The agent decided this issue is out of scope; see comment for reason.' },
  { name: 'agent/duplicate',    color: 'cccccc', description: 'This issue duplicates another; the comment links the original.' },
  { name: 'agent/investigated', color: 'cccccc', description: 'The agent posted an investigation report on this issue.' },
];

const AREA = [
  { name: 'area/core',     color: '0e8a16', description: 'core/ — VM, kernel, scheduler, classmgr, drivers.' },
  { name: 'area/fs',       color: '0e8a16', description: 'fs/ — filesystem drivers (FAT, ext2, HFS+, NTFS, ISO9660, ExFAT).' },
  { name: 'area/net',      color: '0e8a16', description: 'net/ — network stack, sockets, ARP, IPv4, DNS, Ethernet.' },
  { name: 'area/shell',    color: '0e8a16', description: 'shell/ — command framework, built-in commands, bjorne evaluator.' },
  { name: 'area/gui',      color: '0e8a16', description: 'gui/ — AWT, video drivers, input drivers, Thinlet, desktop.' },
  { name: 'area/builder',  color: '0e8a16', description: 'builder/ — BootImageBuilder, JNasm, plugin descriptor tools.' },
  { name: 'area/docs',     color: '0e8a16', description: 'docs/ — project documentation, wiki content.' },
  { name: 'area/build',    color: '0e8a16', description: 'Build system, jnode.properties, plugin lists, ant targets.' },
  { name: 'area/vm',       color: '0e8a16', description: 'JVM internals: JIT compilers, GC, VM magic, type system.' },
  { name: 'area/test',     color: '0e8a16', description: 'Unit tests, boot tests, QEMU test infrastructure.' },
];

const PRESERVE = new Set(['kind/orchestrator', 'orchestrator/locked']);

function parseArgs(argv) {
  const opts = { dryRun: false, update: false, delete: false, remove: [] };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dry-run') opts.dryRun = true;
    else if (a === '--update') opts.update = true;
    else if (a === '--delete') opts.delete = true;
    else if (a === '--remove') opts.remove = (argv[++i] || '').split(',').map((s) => s.trim()).filter(Boolean);
    else if (a === '-h' || a === '--help') {
      process.stdout.write(
        'Usage: node sync-labels.js [--dry-run] [--update] [--delete --remove name1,name2]\n'
      );
      process.exit(0);
    } else {
      process.stderr.write('Unknown flag: ' + a + '\n');
      process.exit(2);
    }
  }
  return opts;
}

function envCheck(dryRun) {
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  const repo = process.env.GITHUB_REPOSITORY;
  if (dryRun) {
    if (!repo || !/^[^/]+\/[^/]+$/.test(repo)) return false;
    return { token: token || null, repo: repo };
  }
  if (!token || !repo) return false;
  if (!/^[^/]+\/[^/]+$/.test(repo)) return false;
  return { token: token, repo: repo };
}

function ghCli() {
  try {
    execFileSync('gh', ['--version'], { stdio: 'ignore' });
    return true;
  } catch (_) {
    return false;
  }
}

function gh(args, options) {
  const opts = Object.assign({ encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }, options || {});
  return execFileSync('gh', args, opts);
}

async function listExistingLabels(repo) {
  const out = gh([
    'api',
    '--paginate',
    '-H', 'Accept: application/vnd.github+json',
    '/repos/' + repo + '/labels?per_page=100',
  ]);
  const lines = out.split('\n').filter((l) => l.trim().length > 0);
  const merged = [];
  for (const line of lines) {
    try { merged.push(...JSON.parse(line)); } catch (_) { /* skip non-JSON trailer */ }
  }
  const map = new Map();
  for (const l of merged) map.set(l.name, l);
  return map;
}

function createOrUpdate(name, color, description, update) {
  const payload = update
    ? { color: color, description: description }
    : { name: name, color: color, description: description };
  const body = JSON.stringify(payload);
  if (update) {
    try {
      gh(['api', '--method', 'PATCH', '-H', 'Content-Type: application/json',
         '/repos/' + process.env.GITHUB_REPOSITORY + '/labels/' + encodeURIComponent(name),
         '--input', '-'], { input: body });
      return 'updated';
    } catch (e) {
      return 'update-failed: ' + e.message.split('\n')[0];
    }
  }
  try {
    gh(['api', '--method', 'POST', '-H', 'Content-Type: application/json',
       '/repos/' + process.env.GITHUB_REPOSITORY + '/labels',
       '--input', '-'], { input: body });
    return 'created';
  } catch (e) {
    const msg = e.message || '';
    if (/already_exists/i.test(msg) || (/422/.test(msg) && /already exists/i.test(msg))) {
      return 'exists';
    }
    return 'create-failed: ' + msg.split('\n')[0];
  }
}

function removeLabel(name) {
  try {
    gh(['api', '--method', 'DELETE',
       '/repos/' + process.env.GITHUB_REPOSITORY + '/labels/' + encodeURIComponent(name)]);
    return 'removed';
  } catch (e) {
    return 'remove-failed: ' + (e.message || '').split('\n')[0];
  }
}

function logResult(name, action, dryRun) {
  if (dryRun) process.stdout.write('[dry-run] ' + action + ' ' + name + '\n');
  else process.stdout.write(action + ' ' + name + '\n');
}

async function main() {
  const opts = parseArgs(process.argv);
  if (!envCheck(opts.dryRun)) {
    process.stderr.write(
      'Missing GITHUB_REPOSITORY env var (and, in non-dry-run mode, GITHUB_TOKEN).\n' +
      'Export both (e.g. `export GITHUB_TOKEN=ghp_xxx GITHUB_REPOSITORY=LSantha/jnode_ai`)\n' +
      'and ensure the `gh` CLI is authenticated.\n'
    );
    process.exit(2);
  }
  if (!opts.dryRun && !ghCli()) {
    process.stderr.write('`gh` CLI not found on PATH. Install github-cli and authenticate.\n');
    process.exit(2);
  }

  const all = KIND.concat(AGENT).concat(AREA);
  const existing = opts.dryRun ? new Map() : await listExistingLabels(process.env.GITHUB_REPOSITORY);

  process.stdout.write('--- jnode-issue-resolver label sync ---\n');
  process.stdout.write('Repo: ' + process.env.GITHUB_REPOSITORY + '\n');
  process.stdout.write('Mode: ' + (opts.dryRun ? 'dry-run' : (opts.update ? 'create+update' : 'create-only')) + '\n\n');

  let created = 0, updated = 0, skipped = 0, failed = 0;
  for (const l of all) {
    if (PRESERVE.has(l.name)) {
      logResult(l.name, 'preserve', opts.dryRun);
      skipped++;
      continue;
    }
    if (existing.has(l.name)) {
      if (opts.update) {
        const r = opts.dryRun ? 'updated' : createOrUpdate(l.name, l.color, l.description, true);
        logResult(l.name, r, opts.dryRun);
        if (r === 'updated') updated++;
        else if (/failed/.test(r)) failed++;
        else skipped++;
      } else {
        logResult(l.name, 'exists', opts.dryRun);
        skipped++;
      }
    } else {
      const r = opts.dryRun ? 'created' : createOrUpdate(l.name, l.color, l.description, false);
      logResult(l.name, r, opts.dryRun);
      if (r === 'created') created++;
      else if (/failed/.test(r)) failed++;
      else skipped++;
    }
  }

  if (opts.delete && opts.remove.length > 0) {
    process.stdout.write('\n--- delete pass ---\n');
    for (const name of opts.remove) {
      if (PRESERVE.has(name)) {
        logResult(name, 'preserve', opts.dryRun);
        continue;
      }
      const r = opts.dryRun ? 'removed' : removeLabel(name);
      logResult(name, r, opts.dryRun);
    }
  }

  process.stdout.write('\nSummary: created=' + created + ' updated=' + updated +
                       ' skipped=' + skipped + ' failed=' + failed + '\n');
  if (failed > 0) process.exit(1);
}

main().catch((e) => {
  process.stderr.write('Fatal: ' + (e && e.stack || e) + '\n');
  process.exit(1);
});

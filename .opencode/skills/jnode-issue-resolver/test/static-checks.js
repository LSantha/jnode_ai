#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const SKILL_PATH = path.join(__dirname, '..', 'SKILL.md');
const SCRIPT_PATH = path.join(__dirname, '..', '..', '..', '..', '.github', 'scripts', 'sync-labels.js');
const AGENTS_PATH = path.join(REPO_ROOT, 'AGENTS.md');

let pass = 0, fail = 0, skip = 0;
const fails = [];

function ok(name) { pass++; console.log('  ok   ' + name); }
function bad(name, detail) { fail++; fails.push(name + ': ' + detail); console.log('  FAIL ' + name + ' - ' + detail); }
function info(name) { skip++; console.log('  skip ' + name); }

function group(title) { console.log('\n=== ' + title + ' ==='); }

function readFile(p) { return fs.readFileSync(p, 'utf8'); }

function exists(p) { return fs.existsSync(p); }

function matchAll(s, re) { return [...s.matchAll(re)]; }

const skill = readFile(SKILL_PATH);

group('1. Skill file basics');
if (exists(SKILL_PATH)) ok('SKILL.md exists at .opencode/skills/jnode-issue-resolver/SKILL.md');
else { bad('SKILL.md exists', SKILL_PATH); process.exit(1); }

const fm = skill.match(/^---\n([\s\S]+?)\n---\n/);
if (fm) ok('YAML frontmatter present');
else { bad('YAML frontmatter present', 'no --- block'); }

const fmBody = fm ? fm[1] : '';
const fmKeys = {};
for (const l of fmBody.split('\n')) {
  const m = l.match(/^([a-z_-]+):\s*(.*)$/);
  if (m) fmKeys[m[1]] = m[2];
}
for (const k of ['name', 'description', 'license']) {
  if (fmKeys[k] && fmKeys[k].length > 0) ok('frontmatter has ' + k);
  else bad('frontmatter has ' + k, fmKeys[k] || '(empty)');
}
if (fmKeys.name === 'jnode-issue-resolver') ok('frontmatter name = jnode-issue-resolver');
else bad('frontmatter name = jnode-issue-resolver', fmKeys.name);

if (fmKeys.description && fmKeys.description.length >= 100) ok('description is dense (' + fmKeys.description.length + ' chars)');
else bad('description density', String(fmKeys.description && fmKeys.description.length));

if (!/Load this FIRST/i.test(fmKeys.description || '')) ok('description does not over-claim ordering');
else bad('description must not contain "Load this FIRST"', 'still present');

group('2. Cross-references resolve');
const linkRe = /\[\[([A-Za-z0-9_\-]+)\]\]/g;
const wikiLinks = [...new Set(matchAll(skill, linkRe).map(m => m[1]))];
let wikiBad = 0;
for (const l of wikiLinks) {
  if (l === 'Wiki-Page') continue;
  const p = path.join(REPO_ROOT, '.wiki', l + '.md');
  if (exists(p)) {} else { bad('wiki link [['+l+']] resolves', 'missing ' + p); wikiBad++; }
}
if (wikiBad === 0) ok('all ' + wikiLinks.length + ' wiki [[links]] resolve (template placeholder excluded)');

const pathRe = /[\`'"](\.[a-zA-Z][\w\-\/]*\.[a-z]{1,5})[\`'"]/g;
const citedPaths = [...new Set(matchAll(skill, pathRe).map(m => m[1]))];
let pathBad = 0;
for (const p of citedPaths) {
  if (exists(path.join(REPO_ROOT, p))) {} else { bad('path ' + p + ' resolves', 'missing'); pathBad++; }
}
if (pathBad === 0) ok('all ' + citedPaths.length + ' repo-relative paths resolve');

const spokeFiles = [
  '.opencode/skills/filesystem-debug/SKILL.md',
  '.opencode/skills/jnode-interact/SKILL.md',
  '.opencode/skills/update-wiki/SKILL.md',
];
for (const f of spokeFiles) {
  if (exists(path.join(REPO_ROOT, f))) ok('spoke exists: ' + f);
  else bad('spoke exists: ' + f, 'missing');
}

const infraFiles = [
  '.github/workflows/opencode.yml',
  '.github/workflows/orchestrator.yml',
  '.github/scripts/orchestrator.js',
  '.github/scripts/sync-labels.js',
  'AGENTS.md',
  '.wiki/index.md',
];
for (const f of infraFiles) {
  if (exists(path.join(REPO_ROOT, f))) ok('infra exists: ' + f);
  else bad('infra exists: ' + f, 'missing');
}

group('3. Markdown structure');
const lines = skill.split('\n');
let depth = 0, fenceCount = 0, lastLang = '';
for (let i = 0; i < lines.length; i++) {
  const m = lines[i].match(/^(\`\`\`)([a-zA-Z]+)?/);
  if (m) {
    depth = depth ? 0 : 1;
    if (depth === 1) { fenceCount++; lastLang = m[2] || ''; }
    else if (m[2] !== lastLang) { /* mismatched language, but valid */ }
  }
}
if (depth === 0) ok('all ' + fenceCount + ' code fences balance');
else bad('code fences balance', 'unclosed fence(s)');

const sectionRe = /^##\s+(\S.*)$/gm;
const sections = matchAll(skill, sectionRe).map(m => m[1]);
if (sections.length >= 8) ok('skill has ' + sections.length + ' top-level sections');
else bad('section count', 'only ' + sections.length + ' sections');

group('4. No stale or over-claiming text');
const stalePatterns = [
  { re: /update-wiki\.md/, label: 'references stale filename update-wiki.md' },
  { re: /hub-and-spoke|hub-and-spoke|hub spoke/i, label: 'over-claims hub/spoke ownership' },
  { re: /Load this FIRST/i, label: 'asserts "Load this FIRST"' },
  { re: /does not replace.*narrow skills/, label: 'claims ownership over narrow skills' },
  { re: /it routes to them/i, label: 'claims to route to other skills' },
];
for (const p of stalePatterns) {
  if (p.re.test(skill)) bad(p.label, 'still present in SKILL.md');
  else ok('absent: ' + p.label);
}

group('5. sync-labels.js sanity');
try {
  execFileSync('node', ['--check', SCRIPT_PATH], { stdio: 'ignore' });
  ok('node --check passes');
} catch (e) { bad('node --check', e.message.split('\n')[0]); }

if (exists(SCRIPT_PATH)) ok('script file present and executable');
const st = fs.statSync(SCRIPT_PATH);
if ((st.mode & 0o111) !== 0) ok('script is executable (mode ' + (st.mode & 0o777).toString(8) + ')');
else bad('script is executable', 'mode is ' + (st.mode & 0o777).toString(8));

try {
  const out = execFileSync('node', [SCRIPT_PATH, '--help'], { encoding: 'utf8' });
  if (/Usage:/.test(out)) ok('--help prints usage');
  else bad('--help prints usage', 'no Usage line');
} catch (e) { bad('--help', e.message); }

group('6. AGENTS.md sanity');
if (exists(AGENTS_PATH)) ok('AGENTS.md exists');
const agents = readFile(AGENTS_PATH);
if (/jnode-issue-resolver/.test(agents)) ok('AGENTS.md mentions jnode-issue-resolver');
else bad('AGENTS.md mentions jnode-issue-resolver', 'not found');
if (/filesystem-debug/.test(agents)) ok('AGENTS.md mentions filesystem-debug');
else bad('AGENTS.md mentions filesystem-debug', 'not found');
if (/jnode-interact/.test(agents)) ok('AGENTS.md mentions jnode-interact');
else bad('AGENTS.md mentions jnode-interact', 'not found');
if (/update-wiki/.test(agents)) ok('AGENTS.md mentions update-wiki');
else bad('AGENTS.md mentions update-wiki', 'not found');
if (!/update-wiki\.md/.test(agents)) ok('AGENTS.md no longer points to stale update-wiki.md');
else bad('AGENTS.md still has update-wiki.md', 'stale reference');

console.log('\n=== Summary ===');
console.log('  pass: ' + pass);
console.log('  fail: ' + fail);
console.log('  skip: ' + skip);
if (fails.length) {
  console.log('\nFailures:');
  for (const f of fails) console.log('  - ' + f);
  process.exit(1);
}
process.exit(0);

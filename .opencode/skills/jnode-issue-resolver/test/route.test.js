#!/usr/bin/env node
'use strict';

const { execFileSync } = require('child_process');

function route(body) {
  if (typeof body !== 'string' || body.length === 0) return 'no-trigger';
  if (!body.startsWith('/oc ')) {
    const idx = body.indexOf(' /oc ');
    if (idx < 0) return 'no-trigger';
    body = body.slice(idx + 1);
  }
  const tail = body.slice(4);
  const m = tail.match(/^\s*(\S+)/);
  const verb = m ? m[1] : '';
  switch (verb) {
    case 'fix': return 'code-fix';
    case 'review': return 'code-review';
    case 'investigate':
    case 'explain': return 'investigation';
    case 'wiki': return 'wiki-doc';
    case 'triage': return 'triage';
    case 'chore': return 'chore';
    case 'test': return 'test';
    case 'Please': return 'bot-of-bots';
    case '': return 'no-trigger';
    default: return 'fallthrough';
  }
}

const cases = [
  { body: '/oc fix the offset bug',                expect: 'code-fix' },
  { body: '/oc fix',                               expect: 'code-fix' },
  { body: '/oc investigate why mkdir fails',      expect: 'investigation' },
  { body: '/oc explain this regression',           expect: 'investigation' },
  { body: '/oc review this PR',                    expect: 'code-review' },
  { body: '/oc review',                            expect: 'code-review' },
  { body: '/oc wiki',                              expect: 'wiki-doc' },
  { body: '/oc wiki update the homepage',          expect: 'wiki-doc' },
  { body: '/oc triage',                            expect: 'triage' },
  { body: '/oc chore',                             expect: 'chore' },
  { body: '/oc test',                              expect: 'test' },
  { body: '/oc Please proceed with this task.',    expect: 'bot-of-bots' },
  { body: '/oc',                                   expect: 'no-trigger' },
  { body: '/oc refactor the parser',               expect: 'fallthrough' },
  { body: '  /oc fix typo (leading whitespace)',   expect: 'code-fix' },
  { body: 'Hello\n\n/oc investigate this',        expect: 'no-trigger' },
  { body: 'Hello /oc fix this trailing mid-comment', expect: 'code-fix' },
  { body: 'random chatter',                        expect: 'no-trigger' },
  { body: '/OC fix case-insensitive',              expect: 'no-trigger' },
  { body: '/oc  fix double-space',                 expect: 'code-fix' },
  { body: '/oc \tfix tab-space',                   expect: 'code-fix' },
  { body: '/oc    chore four-spaces',              expect: 'chore' },
  { body: '/oc-fix-with-dash',                     expect: 'no-trigger' },
  { body: '/oc wiki\twith\ttab',                   expect: 'wiki-doc' },
];

let pass = 0, fail = 0;
const fails = [];
for (const c of cases) {
  const got = route(c.body);
  if (got === c.expect) {
    pass++;
    console.log('  ok   ' + JSON.stringify(c.body).padEnd(48) + ' -> ' + got);
  } else {
    fail++;
    fails.push({ body: c.body, expect: c.expect, got: got });
    console.log('  FAIL ' + JSON.stringify(c.body).padEnd(48) + ' -> ' + got + ' (expected ' + c.expect + ')');
  }
}

console.log('\n=== Routing tests ===');
console.log('  pass: ' + pass + ' / ' + cases.length);
console.log('  fail: ' + fail);
if (fail > 0) {
  console.log('\nFailures:');
  for (const f of fails) console.log('  - body=' + JSON.stringify(f.body) + ' got=' + f.got + ' expected=' + f.expect);
  process.exit(1);
}
process.exit(0);

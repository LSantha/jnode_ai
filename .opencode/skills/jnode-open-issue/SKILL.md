---
name: jnode-open-issue
description: File a well-formed JNode GitHub issue - kind-specific templates for bug, feature, investigation, chore, test, question with repro, env, and logs. Use when creating or filing a JNode issue as human or agent.
license: MIT
metadata:
  version: 0.1.0
  author: opencode
---

# Skill: JNode Open Issue

> How to file a JNode issue that triage can act on without guessing. Complements `jnode-issue-resolver` (dispatch) and the planned `jnode-triage-issue` (classify). This skill is input-side only: it produces the issue, it never labels definitively or assigns work.

## What I do

1. Pick the right **kind** (bug / feature / investigation / chore / test / question / review).
2. Enforce the kind-specific template so triage gets repro, environment, and test basis up front.
3. Leave labels as **hints only** - triage owns the final `kind/*` and `area/*`.

## When to use me

- A human files via `gh issue create` or the web UI.
- An agent files: CI failure (`ant.yml`), a sub-bug found during DEV, wiki-update follow-up, orchestrator child split.
- Anyone re-filing after a `agent/needs-info` or `agent/skip` round.

Load explicitly:

```bash
skill({ name: "jnode-open-issue" })
```

## Label policy (hints, not truth)

- Opener MAY set tentative `kind/*` and `area/*`. Both are optional.
- Triage MUST audit them: keep correct, add missing, remove mislabels with a reason in the `## Triage` comment.
- Opener MUST NOT set `agent/*` (except nothing). `agent/*` is workflow-owned.
- Opt-out: `no-auto` skips auto-triage and auto-run. Use it for drafts, embargoed items, or external reports you want to curate first.

## Pre-file checklist (all kinds)

- [ ] Searched duplicates: `gh search issues --repo LSantha/jnode_ai "<keywords>"` (add `Found: none` or `Related: #N, differs because ...`).
- [ ] One issue = one topic. Split stack traces from feature asks.
- [ ] US-ASCII body. No smart quotes, no em-dash, no screenshots-only (paste text logs).
- [ ] No secrets, tokens, or absolute local paths in body or attachments.
- [ ] No demands touching `core/src/native/x86/*.asm`, `jnode.properties`, `all/build.xml`, `all/conf/*-plugin-list.xml` unless the bug clearly requires it (with stack trace pointing there). Java-side bugs get Java-side reports.
- [ ] No speculative perf claims ("slow", "optimize with SIMD") without profile, benchmark, or trace.

## Common environment block (paste into every bug/feature/test/chore)

```markdown
## Environment

- Commit: `<git log -1 --oneline>`
- Host JDK: `<java -version 2>&1>` (build needs 1.6/1.7/1.8)
- Assemblers: `<nasm -v; yasm --version | head -1>`
- Build target: `cd-x86-lite | cd-x86_64-lite | cd-x86-combined-lite | tests`
- Bitness: `32-bit | 64-bit`
- Run: `QEMU <full cmd> | VirtualBox <vm name + long-mode/pae flags>`
- ISO: `all/build/cdroms/<name>.iso`
- Boot reached `System has finished`: `yes | no`
```

Notes:

- CI injects `.github/qemu/jnode.properties`. Do not paste or edit local `jnode.properties` content.
- Paste the CONSOLE/SERIAL excerpt as text (20-60 lines around the failure), not a photo. Full logs go in an attached `.txt` or a fenced block.

## Templates

### bug - crash, wrong behavior, regression

Required: numbered repro from clean checkout, buggy outcome, expected outcome, test-coverage basis.

```markdown
## Summary

<one sentence, imperative: "FAT16 mkdir fails after remount">

## Steps to Reproduce

1. `git ...` / `sh build.sh <target>`
2. Boot: `<exact QEMU/VBox command>`
3. In shell: `<typed commands, one per line>`
4. Observe: `<what happens>`

## Buggy Outcome

<actual behavior + 10-30 line log excerpt with exception/stack trace>

## Expected Outcome

<correct behavior, ideally with spec or prior-good-version reference>

## Test Coverage Basis

- Existing module: `<subproject>/src/test/... (or "none found via grep")>`
- Gap: <what case is untested>
- Proposed test: `<TestClass#method idea, host-JVM (`cd <sub> && ant test`) vs boot test (QEMU + shell cmds)>

## Environment

<paste block above>

## Attachments

- [ ] serial log excerpt (above) / full `.txt`
- [ ] `build/reports/junit/TEST-*.xml` excerpt if unit test fails
```

### feature / improvement - new behavior or spec change

Required: problem, proposal, requirements, acceptance, how-to-test.

```markdown
## Summary

<one sentence: "Add ExFAT long-name fallback for ...">

## Problem

<who is blocked, what fails today, why existing workaround is insufficient>

## Proposal / Spec

<behavior change in 3-10 bullets; file:line touch zones, e.g. `fs/src/fs/...`; public API changes if any>

## Requirements

- Functional: <must/must-not list>
- Non-functional: Java 1.6 only, US-ASCII, LGPL header on new files, formatter `jnode-eclipse-formatter-settings.xml`, no ASM/config touch (or justify)
- Out of scope: <explicit>

## Acceptance Criteria

- [ ] <verifiable bullet 1>
- [ ] <verifiable bullet 2>

## How to Test

- Unit: `cd <subproject> && ant test` -> <expected>
- Boot (if kernel/VM/driver/FS): QEMU steps + shell cmds + `System has finished`
- New tests added: <yes, <TestClass> / no, because ...>

## Alternatives Considered

<1-3 rejected options with one-line why>

## Environment

<paste block above if behavior is hw/bitness dependent>
```

### investigation - question to answer, no fix expected

Required: question, scope, result shape. Output is a comment (or attached md), never a PR.

```markdown
## Summary

<one sentence: "Why does ext2 mount take >30s on ...">

## Question

<single restated question triage can quote>

## Scope

- In: <dirs/files, e.g. `fs/src/fs/org/jnode/fs/ext2`>
- Out: <explicitly excluded>
- Timebox note: reporter accepts partial report with `Confidence: low` + next step

## How to Produce Results

- Post as `## Investigation Report` comment (Question/Findings/Root-cause/Suggested-fix/Confidence/References), per resolver skill.
- Attach `.md` only if report exceeds ~100 lines (paste summary in comment + link attachment).

## Context Already Gathered

<logs tried, wiki pages read (`[[Page]]`), prior issues #N>
```

### chore - refactor, typo sweep, dead code, build cleanup

```markdown
## Summary

<one sentence: "Remove dead ... / fix typos in ...">

## Motivation

<why now; no behavior change claim>

## Scope

- Files/dirs: <list; keep diff under ~500 lines or split>
- Forbidden touch: <asm/config unless justified>

## No-Behavior-Change Proof

<how to show equivalence: `ant test` modules, boot check if touched area boots>

## How to Test

`cd <subproject> && ant test` (+ QEMU boot if `core/` touched)
```

### test - add or fix a test

```markdown
## Summary

<one sentence: "Add test for <X> in <module>">

## What to Cover

<case list, incl. edge cases>

## Where

<target file: `<subproject>/src/test/...`, new vs existing class>

## Current Gap

<grep evidence: no test asserts ...>

## How to Run

`cd <subproject> && ant test` (or `sh build.sh tests` if cross-module)
```

### question - usage or design question

```markdown
## Question

<single question>

## Context

<what you run, what you want>

## What Was Tried

<commands + wiki pages read>
```

Expected answer shape is an investigation comment, not a PR.

### review - ask for a code review (rare as issue; normally PR)

```markdown
## PR

<PR number/URL>

## Scope

<what to focus: correctness | Java 1.6 | tests | perf-evidence>

## Context

<linked issue #N, risk areas>
```

## Filing

```bash
# Search first
gh search issues --repo LSantha/jnode_ai "<keywords>" --limit 10

# File with hint labels only (both optional, triage audits)
gh issue create --repo LSantha/jnode_ai \
  --title "<area-hint>: <imperative summary>" \
  --body-file /tmp/issue.md \
  --label "kind/bug" --label "area/fs"

# Draft that skips automation
gh issue create --repo LSantha/jnode_ai \
  --title "DRAFT: ..." --body-file /tmp/issue.md --label "no-auto"
```

Title convention: `<area-hint>: <summary>` or `<Summary> (Closes #N)` only for PRs. Keep subject imperative, under ~72 chars.

## Negative constraints

- NEVER file without the Environment block for bug/feature/test/chore (question/investigation may omit host details if hw-independent, but say so).
- NEVER paste `jnode.properties` secrets or full local paths.
- NEVER file "make kernel faster" without profile/benchmark/trace.
- NEVER bundle two failures in one issue; NEVER file a typo fix on top of a real fix.
- NEVER set `agent/*`, `kind/orchestrator`, or `orchestrator/locked`.

## Related pages / files

- `.opencode/skills/jnode-issue-resolver/SKILL.md` - dispatch by verb, pre-flight, report shapes
- `.github/scripts/sync-labels.js` - label families (`kind/*`, `agent/*`, `area/*`, `auto-merge`, `no-auto`)
- `.github/scripts/opencode-post-step.js` - heading-to-label map (Triage content-match)
- `.wiki/Code-Conventions.md` - Java 1.6 + formatter + license header
- `.wiki/Testing.md` - host-JVM vs boot-test split
- `.wiki/Build-System.md` - `sh build.sh` targets
- `jnode-eclipse-formatter-settings.xml` - formatter

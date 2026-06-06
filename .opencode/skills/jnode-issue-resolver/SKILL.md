---
name: jnode-issue-resolver
description: General protocol for an OpenCode agent spawned on a JNode GitHub issue or pull request — routes work by /oc verb (fix / investigate / review / wiki / triage) or label, runs the JNode build/test/boot loop, and reports back via PR, inline review, or a single structured comment. Pairs with the orchestrator workflow for high-volume sequential resolution.
license: MIT
metadata:
  version: 0.1.0
  author: opencode
---

# Skill: JNode Issue Resolver

> The general playbook an OpenCode agent follows when it lands on a JNode issue or PR. Complementary to the focused skills (`filesystem-debug`, `jnode-interact`, `update-wiki`), each of which is also independently invokable.

## What I do

1. Detect **why** the agent was woken (event, comment verb, labels).
2. Classify the work into one of **8 task kinds**.
3. Run a JNode-aware build / test / boot loop.
4. Report back in the right shape: PR, inline review, structured comment, or wiki spoke.
5. Cooperate with the existing `kind/orchestrator` master-issue state machine.

## When to use me

You are almost certainly in the right place if:
- The trigger was a `/oc …` comment on an issue or PR.
- The trigger was a `pull_request_review_comment` mentioning `/oc`.
- The trigger was the `orchestrator` workflow re-spawning you on the next issue in its queue.
- A new GH Actions job labelled `opencode` just started and the runner is `ubuntu-latest`.

## How to use

This skill is auto-loaded when an agent session starts on a JNode issue or PR (its `description:` matches). You can also force-load it explicitly:

```bash
skill({ name: "jnode-issue-resolver" })
```

Each focused skill below has its own `description:` and loads independently — you do **not** need this skill present to use them. Load them on demand when the work calls for it:

| Focused skill | Load when… |
|---|---|
| `jnode-interact` | You must boot a built ISO in QEMU to verify a kernel/VM/driver change. |
| `filesystem-debug` | The bug is in `fs/src/fs/...` and the diagnosis needs BPB / FAT table inspection. |
| `update-wiki` | The task is to create or update a wiki spoke page. |

---

## 0. Bootstrap (first 60 s)

Before doing any work:

1. **Detect the event.** Read `github.event_name` from the runner context.
   - `issue_comment` / `pull_request_review_comment` → a human triggered you.
   - `pull_request` → a PR was just opened or updated; you are a reviewer.
   - `issues` → a new issue; you are triaging.
   - `workflow_run` is what the orchestrator listens to, not you.
2. **Parse the trigger comment body** (if any). The verb after `/oc` is the primary dispatch signal — see §2.
3. **Apply author-association guard.** Only act if `author_association` is `COLLABORATOR`, `MEMBER`, or `OWNER`. Otherwise post a one-liner asking a maintainer to add the trigger and exit.
4. **Clone `.wiki/`** if it is missing (the skill and `AGENTS.md` require it). Use `git clone git@github.com:LSantha/jnode_ai.wiki.git .wiki` or the `GH_PAT`-authenticated `https://` form in CI.
5. **Read `AGENTS.md`** end-to-end. Then read `.wiki/index.md` (Task Routing table) and `.wiki/Glossary.md`.
6. **Apply `agent/in-progress`** to the issue (create it on first run via `sync-labels.js`, see §10).
7. **Post an acknowledgement comment** so the user sees motion within 60 s.

Ack comment template (≤ 5 lines):

```
🤖 Working on this. Run: `${{ github.run_id }}`
Plan: <one-line summary of next 3 steps>
```

## 1. Task taxonomy

| Kind | Trigger | Output | Closes issue? |
|---|---|---|---|
| `code-fix` | `/oc fix`, or bare `/oc` on a `kind/bug` / `kind/feature` issue with a clear repro | branch + PR | yes, on merge |
| `code-review` | `/oc review`, or `pull_request_review_comment` with `/oc`, or `pull_request: opened/synced` | inline comments + summary review | no |
| `investigation` | `/oc investigate` / `/oc explain`, or `kind/investigate` | single structured comment | no |
| `wiki-doc` | `/oc wiki`, or `kind/wiki` (delegates to `update-wiki`) | wiki spoke + comment with URL | no |
| `triage` | `/oc triage` | labels + checklist comment | no |
| `chore` | `/oc chore`, or `kind/chore` (typo sweep, dead-code removal) | branch + PR | yes, on merge |
| `test` | `/oc test`, or `kind/test` ("add a test for X") | branch + PR with tests | yes, on merge |
| `bot-of-bots` | comment body is exactly `/oc Please proceed with this task.` (the orchestrator's signature) | acts as `code-fix` / `wiki-doc` / etc. per the source issue's labels | only on success |

If multiple signals conflict, precedence is: **verb > label > issue-body shape**.

## 2. Routing rules

```
parse(comment.body):
  if !startsWith(body, "/oc "):
    idx = indexOf(body, " /oc ")            // opencode.yml also allows embedded "/oc "
    if idx < 0: return "no-trigger"
    body = body.slice(idx + 1)              // drop the leading space
  tail = body.slice(4)                      // drop "/oc "
  m = tail.match(/^\s*(\S+)/)               // first non-whitespace token (handles "/oc  fix", "/oc\tfix")
  verb = m ? m[1] : ""
  switch(verb):
    "fix"         -> code-fix
    "review"      -> code-review
    "investigate" -> investigation
    "explain"     -> investigation
    "wiki"        -> wiki-doc     (load update-wiki)
    "triage"      -> triage
    "chore"       -> chore
    "test"        -> test
    "Please"      -> bot-of-bots  (matches "/oc Please proceed with this task.")
    ""            -> no-trigger (comment is exactly "/oc" with no verb)
    <unknown>     -> fall through to heuristic(issue)

heuristic(issue):
  if issue.pull_request                -> code-review
  if has_label(kind/bug) || has_repro  -> code-fix
  if has_label(kind/investigate)       -> investigation
  if has_label(kind/wiki)              -> wiki-doc
  if issue.body is a question          -> investigation
  default                              -> code-fix (smallest patch that compiles)
```

The verb is the first non-whitespace token after `/oc `, so `/oc wiki`, `/oc wiki update the homepage`, and even `/oc  wiki` (double-space) all dispatch to `wiki-doc`. The bare `/oc` (no verb) is **not** a valid trigger — `opencode.yml` requires a trailing space. Routing is case-sensitive to match the workflow's `startsWith` / `contains` checks.

The reference implementation lives at `test/route.test.js` and is exercised by 24 synthetic cases.

## 3. The protocol (seven steps, every kind)

1. **Acknowledge** — post the short ack comment, apply `agent/in-progress`.
2. **Plan** — write a checklist in the issue as a follow-up comment (≤ 8 bullets). Human can `/oc stop` to abort.
3. **Work** — read → patch → build (`sh build.sh cd-x86-lite`) → focused test (`cd <subproject> && ant test`).
4. **Self-check** — see §6.
5. **Report** — see §5.
6. **Mark state** — apply the right `agent/*` label, remove `agent/in-progress`.
7. **Handoff** — if you are the orchestrator's `current_task`, do **not** edit the master issue's hidden JSON. The orchestrator listens to your `workflow_run` `success` event and will mark you complete; a `failure` triggers a retry (max 3).

## 4. Build & test loop (JNode-specific)

| Step | Command | When |
|---|---|---|
| Cold ISO build | `sh build.sh cd-x86-lite` | ISO not cached, or any `core/` / `core/src/native/x86/*.asm` change |
| Cached build | (none — CI uses `actions/cache@v4` keyed on `**/*.java` + `build.sh`) | default |
| Focused unit tests | `cd <subproject> && ant test` | changed code is in `<subproject>` and the existing suite covers it |
| Full sweep | `sh build.sh tests` | cross-subsystem change (e.g., plugin descriptor, assembly, build) |
| QEMU boot test | load `jnode-interact`, run `bash .opencode/skills/jnode-interact/scripts/start_qemu.sh simple`, then `python3 .opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py "date" "mount" "plugin"` | kernel/VM/driver/FS work |
| FS structure decode | load `filesystem-debug` | FS bugs at BPB/FAT level |
| Wiki push | `cd .wiki && git add . && git commit -m "..." && git push` | wiki-doc kind only — `AGENTS.md` requires manual push |

**Build cost reality.** Cold `cd-x86-lite` is ≈ 7 min on the CI runner. ISO cache key already exists in `opencode.yml`; locally just rerun. Do **not** rebuild the ISO for a one-line typo fix — only `cd <subproject> && ant test`.

**Java 1.6 is non-negotiable.** See `.wiki/Code-Conventions.md`. No lambdas, no try-with-resources, no diamond, no `java.util.Objects`, no `String.isEmpty()` antipattern, no `java.nio.file`.

## 5. Reporting formats

### 5.1 `code-fix` / `chore` / `test` PR

- **Branch name** — `opencode/issue<N>-<YYYYMMDDHHMMSS>` (matches the convention of `c706daa1b`, `2b48d2c3b`, `7d7a73f42`).
- **Commit message** — imperative mood, ≤ 72-char subject, body explains *why*:
  ```
  <Subject> (#<N>)

  <body, ≤ 10 lines>

  Co-authored-by: LSantha <LSantha@users.noreply.github.com>
  ```
- **PR title** — `Subject` only, or `Subject (Closes #<N>)`.
- **PR body** — use this template:
  ```markdown
  ## Summary
  <one paragraph>

  ## Changes
  - file:line — <what & why>
  - file:line — <what & why>

  ## Testing
  - `cd <subproject> && ant test` → <PASS/FAIL with summary line>
  - `sh build.sh cd-x86-lite` → <OK or "unchanged, cached">

  Closes #<N>
  ```
- **Auto-close the issue** only if its title starts with `Review ` (existing convention in `opencode.yml`). Otherwise the issue closes when the PR merges.

### 5.2 `code-review` (PR review)

Use inline comments on the diff via `pull_request_review_comment` context (existing wiring in `opencode.yml` gives you file + line + hunk). Severity scale:

| Marker | Meaning | Required action |
|---|---|---|
| 🔴 | Must-fix: bug, license violation, Java 1.6 violation, missing test | request changes |
| 🟡 | Should-fix: style, naming, missing edge case | comment, do not block |
| 🟢 | Nit: typo, formatting, optional refactor | comment, do not block |

End every review with a single **summary comment** (not a review submission) that lists the findings in priority order and ends with `Verdict: approve | request-changes | comment`. Then submit a GitHub Review with the matching verdict using `gh pr review <N> --request-changes` (etc.).

### 5.3 `investigation` (single structured comment)

One comment only. Markdown:

```markdown
## 🤖 Investigation Report

**Question:** <one-line restatement of what the issue is asking>
**Findings:** <3–8 bullets, file:line where applicable>
**Root cause:** <bullet or "not determined">
**Suggested fix:** <code outline, file:line, or 2–5 step plan>
**Confidence:** low | medium | high
**References:** [[Wiki-Page]] · file:line · PR #X

<!-- If reusable knowledge was found, the agent SHOULD also create a wiki spoke (load update-wiki) and link it here. -->
```

Do **not** open a PR. Apply `agent/investigated` and leave the issue open for the human to act.

### 5.4 `triage` comment

```markdown
## 🤖 Triage

- [ ] **Area:** <area/* label added>
- [ ] **Repro:** <quoted minimal steps, or "needs more info from reporter">
- [ ] **Severity:** <bug | feature | question | chore | docs>
- [ ] **Suggested next:** <fix | investigate | needs-info | duplicate-of-#M | wontfix>
- [ ] **Labels applied:** <list>

@<reporter> — please confirm the repro and add any missing logs.
```

Apply the right `kind/*` and `area/*` labels. If the issue is a duplicate, label `agent/duplicate` and link the original.

### 5.5 `wiki-doc`

Delegate fully to the `update-wiki` skill, then post a one-liner with the page URL:

```
🤖 Wiki page created/updated: https://github.com/LSantha/jnode_ai/wiki/<Page>
```

(Reminder from `AGENTS.md`: wiki push is manual, run `cd .wiki && git push` after committing.)

## 6. Self-check (run before every PR)

- [ ] Java 1.6 only (no `->`, no `::`, no `<>`, no multi-catch, no try-with-resources, no `java.util.Objects`, no `java.nio.file`).
- [ ] US-ASCII file encoding (no smart quotes, no em-dash, no non-breaking space).
- [ ] LGPL 2.1 header present on every new `.java` file.
- [ ] Formatter applied (`jnode-eclipse-formatter-settings.xml` in repo root).
- [ ] No code comments added (AGENTS.md forbids them unless explicitly requested).
- [ ] No debug `System.out.println` / `System.err.println` / `printStackTrace()` left behind.
- [ ] No change to `jnode.properties`, `all/build.xml`, `all/conf/*-plugin-list.xml`, or `core/src/native/x86/*.asm` unless the bug clearly requires it.
- [ ] At least one unit test added/updated for every behavioral change.
- [ ] Focused test suite passes: `cd <subproject> && ant test`.
- [ ] ISO still builds if `core/` changed: `sh build.sh cd-x86-lite`.
- [ ] No secrets, tokens, or absolute paths in the diff.

## 7. Idempotency & safety

- **Skip** if `agent/in-progress` is set by a younger run (compare `run_id` / `run_attempt`).
- **Skip** if the issue is `closed`, `locked`, or from a non-collaborator (after the ack comment).
- **Skip** if the diff would touch more than ~500 lines and the issue looks like a refactor — ask the human first.
- **Never** push to `master`. The OpenCode action creates a branch and opens a PR; you do not push.
- **Never** commit secrets. `git diff --staged` should be clean of `BEGIN ... PRIVATE KEY`, `ghp_`, `sk-`, etc.
- **Never** force-push.
- **Never** delete or rewrite commits on a branch you did not create.

## 8. Anti-patterns (do not)

- ❌ Open a PR that contains AI-slop comments (`// This function does X`, `// TODO: optimize`, `// Added by AI`).
- ❌ Open a "fixed typo" PR stacked on top of a real fix; split them.
- ❌ Modify `core/src/native/x86/*.asm` or the JNasm assembler for a Java-side bug.
- ❌ Change `jnode.properties` (CI injects `.github/qemu/jnode.properties`).
- ❌ Invent a wiki page that is not backed by source code. Load `update-wiki` and follow its protocol.
- ❌ Close an issue when the task is unclear; apply `agent/needs-info` and ask 3–5 specific questions instead.
- ❌ Reimplement `filesystem-debug` / `jnode-interact` / `update-wiki` inline. Load them on demand.
- ❌ Rerun the full `sh build.sh tests` when a focused `cd <subproject> && ant test` would do.

## 9. Orchestrator awareness

The orchestrator (`.github/workflows/orchestrator.yml` + `.github/scripts/orchestrator.js`) maintains a hidden JSON state in the master issue's body:

```
<!-- ORCHESTRATOR_STATE:
{ "status": "IN_PROGRESS", "current_task": <N>, "queue": [...], "completed": [...], "failed": [...], "retries": <0..3> }
-->
```

When you are woken by a comment whose body is exactly:

```
/oc Please proceed with this task.
```

…you are the `current_task`. Rules:

- Do **not** edit the master issue body or the hidden JSON. The orchestrator owns that.
- Do your work, push the branch / comment / wiki page as usual.
- The orchestrator listens to `workflow_run` from `opencode`. Your `success` conclusion lets it mark you complete; a `failure` triggers a retry.
- The orchestrator will post the next `/oc` for the next queued issue — you do not chain yourself.
- If you are the last task, the orchestrator posts a "✅ All tasks processed" comment and flips its state to `COMPLETED`.

If you are **not** the orchestrator's `current_task` (e.g., a human `/oc`'d the same issue out-of-band), just do the work normally. The orchestrator's self-healing guard will detect the issue is already closed and advance.

## 10. Label bootstrap (one-time)

The skill assumes these labels exist in the repo. To create them, run the bundled script:

```bash
node .github/scripts/sync-labels.js
```

Requires `GITHUB_TOKEN` (or `GH_TOKEN`) and `GITHUB_REPOSITORY` (`owner/repo`) env vars. The script is **idempotent** — existing labels are left untouched, missing ones are created with the documented color and description.

| Family | Names | Color | Purpose |
|---|---|---|---|
| `kind/*` | `bug`, `feature`, `investigate`, `wiki`, `review`, `chore`, `question`, `triage` | `#1d76db` (blue) | Intent of the issue |
| `agent/*` | `in-progress`, `needs-info`, `blocked`, `done`, `failed`, `skip`, `duplicate`, `investigated` | `#cccccc` (grey) | Agent lifecycle (replaces nothing; additive) |
| `area/*` | `core`, `fs`, `net`, `shell`, `gui`, `builder`, `docs`, `build`, `vm`, `test` | `#0e8a16` (green) | Subsystem (aligned with `index.md` Task Routing) |

Existing labels `kind/orchestrator` and `orchestrator/locked` are **not** touched.

## 11. Quick reference

```bash
# Build
sh build.sh cd-x86-lite            # ISO (cached in CI)
sh build.sh tests                  # full unit-test sweep
cd <subproject> && ant test        # focused

# Issue / PR
gh issue view <N> --comments
gh issue edit <N> --add-label "agent/in-progress"
gh issue close <N> -R "$REPO"
gh pr review <N> --request-changes -b "..."

# Wiki
cd .wiki && git add . && git commit -m "Wiki: <page>" && git push

# Labels
node .github/scripts/sync-labels.js

# Self-check
rg -n "->|::" <changed.java>                       # Java 1.6 violations
rg -n "TODO|FIXME|XXX" <changed.java>              # leftover markers
file <changed.java>                                # encoding must be ASCII
```

## Related pages / files

- `.github/workflows/opencode.yml` — the workflow that wakes you up
- `.github/workflows/orchestrator.yml` — the orchestrator
- `.github/scripts/orchestrator.js` — the orchestrator's state machine
- `.github/scripts/sync-labels.js` — the label-bootstrap script (this skill, §10)
- `.opencode/skills/filesystem-debug/SKILL.md`
- `.opencode/skills/jnode-interact/SKILL.md`
- `.opencode/skills/update-wiki/SKILL.md`
- `AGENTS.md` — root agent instructions
- `.wiki/index.md` — Task Routing table
- `.wiki/Code-Conventions.md` — Java 1.6 + formatter + license header rules
- `.wiki/Testing.md` — host-JVM vs boot-test split
- `.wiki/Build-System.md` — `sh build.sh` targets
- `.wiki/Boot-Sequence.md` — for kernel/VM work

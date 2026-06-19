# Agent Instructions for .github/

CI infrastructure, agent automation, and label conventions for JNode.

## Layout

| Path | Purpose |
|------|---------|
| `workflows/opencode.yml` | Single-issue agent runner. Triggered by `/oc <verb>` comments. |
| `workflows/orchestrator.yml` | Multi-task batch runner. Triggered by `/orchestrate` or by opencode completion. |
| `workflows/ant.yml` | Plain Java CI: build + test + QEMU boot on every push/PR to master. |
| `scripts/opencode-post-step.js` | JS post-step run by opencode.yml after the agent exits. Applies `agent/*` label, closes investigations. |
| `scripts/orchestrator.js` | JS state machine run by orchestrator.yml. Drives the queue, updates the master issue, triggers child tasks. |
| `scripts/sync-labels.js` | One-shot label bootstrap. Idempotent. Use `--dry-run` to preview. |
| `qemu/jnode.properties` | CI build profile (used by opencode.yml and ant.yml). |
| `qemu/menu-cdrom.lst` | CD menu listing for the lite ISO. |

## Workflow Triggers

| Workflow | Trigger | Job-level `if` |
|----------|---------|----------------|
| `opencode` | `issue_comment: [created]` and `pull_request_review_comment: [created]` | body matches `/oc ` (prefix or preceded by space) AND author is COLLABORATOR / MEMBER / OWNER |
| `orchestrator` | `issue_comment: [created]` (body starts with `/orchestrate`) OR `workflow_run: [opencode, completed]` | event is `issue_comment` with `/orchestrate` OR event is `workflow_run` |
| `ant` (Java CI) | `push: [master]`, `pull_request: [master]` | always |

`opencode.yml` run-name: `"Issue #N - title"` (em-dash; see Encoding).

## Relationship: opencode vs orchestrator

```
                  +--------------------+
   /oc <verb> --> |  opencode workflow |  --(agent finishes)-->  apply agent/* label
                  +--------------------+                          close investigation kinds
                                                                trigger workflow_run
                                                                          |
                                                                          v
                  +--------------------+   /oc "Please proceed"   +-------------------------+
   /orchestrate->| orchestrator (js)  |------------------------->| next child task in queue |
                  +--------------------+                          +-------------------------+
                            ^   |
         pull_request_review|   | /oc review | /oc fix
                            |   v
                  +--------------------+
                  |    PR feedback     |
                  +--------------------+
```

- **opencode** is the worker. It runs the agent once per trigger, posts a result, and exits.
- **orchestrator** is the foreman. It holds a JSON state in the master issue body, picks the next child task from the queue, and tracks its phase (DEV, REVIEW, HUMAN_REVIEW, FEEDBACK, MERGE).
- `orchestrator.yml` listens for `workflow_run` from `opencode` and `pull_request_review`. It advances the phase, loops back via `/oc fix` or `/oc review`, or merges the PR.

For single-step tasks, a child task is "complete" in the orchestrator's eyes when EITHER:
- the child issue is closed on GitHub, OR
- the child has one of: `agent/done`, `agent/investigated`, `agent/skip`, `agent/blocked`, `agent/needs-info`.

For multi-step PR tasks, completion requires reaching the `MERGE` phase (or short-circuiting on skip labels).

## Orchestrator State Machine

State lives in the master issue body as a hidden HTML comment:

```html
<!-- ORCHESTRATOR_STATE:
{ "status": "IDLE|IN_PROGRESS|COMPLETED",
  "current_task": { "issue": 487, "pr": null, "phase": "DEV", "turn": 0, "max_turns": 3, "retries": 0 },
  "queue": [488, 489],
  "completed": [485, 486],
  "failed": [],
  "retries": 0,
  "history": [...],
  "order": [485, 486, 487, 488, 489] }
-->
```

| Field | Meaning |
|-------|---------|
| `status` | IDLE (initial) / IN_PROGRESS (after first trigger) / COMPLETED (queue empty) |
| `current_task` | Object representing task state, or task number (for older one-shot tasks) |
| `queue` | Pending task numbers, in execution order |
| `completed` | Tasks that finished completely (merged, or short-circuited) |
| `failed` | Tasks that hit 3 retries without success, or max_turns |
| `retries` | Attempt counter for `current_task`; resets on advance |
| `history` | Append-only event log with ISO timestamps |
| `order` | Original task order from the markdown checklist; rendered top-to-bottom in the status table |

### Phases

- **DEV**: Initial agent run. Agent creates a PR. Transition to `REVIEW`.
- **REVIEW**: Agent reviews the PR. If approved and no `auto-merge` label, transition to `HUMAN_REVIEW`. If `auto-merge`, transition to `MERGE`. If changes requested, transition to `FEEDBACK`.
- **FEEDBACK**: Agent addresses review comments. Transition to `REVIEW`.
- **HUMAN_REVIEW**: Orchestrator waits for native GitHub PR review from a human maintainer. Approval → `MERGE`, Request changes → `FEEDBACK`.
- **MERGE**: Orchestrator squashes the PR and deletes the branch inline.

Initialization: on first run, the orchestrator parses the master issue's markdown checklist (`- [ ] #N`, `- [x] #N`, `- [FAIL] #N`) and builds initial state. Subsequent runs load from the hidden JSON block. If `state.order` is empty (older master), it is backfilled from status-grouped arrays.

Self-healing guard: if the orchestrator wakes up and the `current_task` is already complete (closed or has completion label), it advances immediately and exits. This handles the case where the agent finished but the orchestrator was locked out.

Locking: `orchestrator/locked` label is added at the start of every run and removed in `finally`. If a second run sees the lock, it exits silently (concurrency group `orchestrator-concurrency` enforces single-runner).

## Label System

Three families (color-coded). `sync-labels.js` ensures they exist.

### kind/*  (what kind of work, blue)

| Label | Meaning |
|-------|---------|
| `kind/bug` | Confirmed bug with repro or stack trace |
| `kind/feature` | New feature or enhancement |
| `kind/investigate` | Investigate and report back, do not fix |
| `kind/wiki` | Documentation; delegated to update-wiki skill |
| `kind/review` | Code review on a PR |
| `kind/chore` | Refactor, typo sweep, dead code |
| `kind/question` | User question; expected output is an investigation comment |
| `kind/triage` | Ask the agent to triage a new issue (labels + checklist) |
| `kind/orchestrator` | **Master issue** that the orchestrator drives. Only ONE open master at a time. |

### agent/*  (agent status, grey)

| Label | Meaning |
|-------|---------|
| `agent/in-progress` | Agent is currently working (added by `opencode.yml` start) |
| `agent/needs-info` | Agent posted `## Triage` heading; waiting for the reporter |
| `agent/blocked` | Agent is blocked on an external dependency or build failure |
| `agent/done` | Agent finished successfully; PR opened or comment posted |
| `agent/failed` | Agent's run concluded with failure/cancelled; orchestrator will retry |
| `agent/skip` | Agent decided this is out of scope (refusal heading) |
| `agent/duplicate` | Issue duplicates another; comment links the original |
| `agent/investigated` | Agent posted an investigation report |

Five of these (`done`, `investigated`, `skip`, `blocked`, `needs-info`) are recognized by the orchestrator as task completion signals.

### area/*  (subsystem, green)

`area/core`, `area/fs`, `area/net`, `area/shell`, `area/gui`, `area/builder`, `area/docs`, `area/build`, `area/vm`, `area/test`.

### Orchestrator-internal (not in sync-labels.js)

| Label | Meaning |
|-------|---------|
| `orchestrator/locked` | Set at orchestrator run start, removed in `finally`. |

## Post-Step Decision Tree (opencode-post-step.js)

Runs on every opencode.yml run, regardless of success/failure/cancelled.

1. Read the issue's existing `agent/*` label. If it's set and not `agent/failed`, respect it.
2. If the run concluded `failure` or `cancelled`, apply `agent/failed`.
3. Detect the latest agent comment by heading. Priority order:
   - `Refusal` heading -> `agent/skip`
   - `Triage` heading (or text `needs more info` / `needs the following`) -> `agent/needs-info`
   - PR context -> `agent/done`
   - `Investigation Report` heading -> `agent/investigated` (verb-override)
4. If no heading and issue is `kind/investigate` or `kind/question` -> `agent/investigated`.
5. Default -> `agent/done`.
6. Remove `agent/in-progress`.
7. If the applied label is `agent/investigated` AND the issue is `kind/investigate` or `kind/question`, close the issue (unless already closed).

The post-step is idempotent. A second run with the same inputs makes the same decision.

## Encoding

`AGENTS.md` files in this repo are US-ASCII. No smart quotes, no em-dash, no non-breaking space. The one exception is `opencode.yml` run-name, which uses an em-dash for display only. When editing scripts and docs, prefer `-` or `--` over `-`.

## Running scripts locally

```bash
export GITHUB_TOKEN=ghp_xxx
export GITHUB_REPOSITORY=LSantha/jnode_ai

node .github/scripts/sync-labels.js --dry-run
node .github/scripts/sync-labels.js
```

The `opencode-post-step.js` and `orchestrator.js` modules export a single async function taking `{ github, context, core }`. They are normally invoked from `actions/github-script@v7` and not run standalone.

### Running tests

Unit tests for the `.github/scripts/` logic use the native `node:test` framework (requires Node.js v18+). They do not require any external dependencies.

```bash
node --test .github/scripts/tests/
```

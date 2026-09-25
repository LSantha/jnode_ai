# Agent Instructions for .github/

CI infrastructure, agent automation, and label conventions for JNode.

## Layout

| Path | Purpose |
|------|---------|
| `workflows/opencode.yml` | Single-issue agent runner (build agent). Triggered by `/oc <verb>` comments except `/oc triage`. |
| `workflows/triage.yml` | Dedicated triage runner: read-only analysis (locked checkout, no builds run, no commit/push/PR possible). Triggered by `/oc triage` only. JDK present; issues:write + read-only contents. |
| `workflows/auto-triage.yml` | Auto posts `/oc triage` on `issues: opened` and on human replies while `agent/needs-info` holds (re-triage, max 3 passes). Skips bots, masters, `no-auto`. |
| `workflows/ticket-runner.yml` | Single-issue multi-turn runner. Triggered by `/run`, by `issues: labeled` (`kind/*`), or by opencode completion. |
| `workflows/orchestrator.yml` | Multi-task batch runner. Triggered by `/orchestrate` or by opencode completion. |
| `workflows/ant.yml` | Plain Java CI: build + test + QEMU boot (32-bit and 64-bit) on every push/PR to master. The `heal` job posts one `/oc fix` per SHA on PRs when CI fails (skips `no-auto`/busy, marker-deduped). |
| `scripts/opencode-post-step.js` | JS post-step run by opencode.yml after the agent exits. Applies `agent/*` label, closes investigations. |
| `scripts/orchestrator.js` | JS state machine run by orchestrator.yml. Drives the queue, updates the master issue, triggers child tasks. |
| `scripts/ticket-runner.js` | JS state machine run by ticket-runner.yml. Drives single-ticket multi-turn DEV -> REVIEW -> FEEDBACK -> MERGE loop. |
| `scripts/orchestrator-helpers.js` | Shared GitHub REST helper functions used by orchestrator.js and ticket-runner.js. Also the merge safety gate (`isAutoMergeEligible`, `isDiffSafe`, `isCIGreen`) and `findPRsForSHA` for CI wakeups. |
| `scripts/sync-labels.js` | One-shot label bootstrap. Idempotent. Use `--dry-run` to preview. |
| `qemu/jnode.properties` | CI build profile (used by opencode.yml and ant.yml). |
| `qemu/menu-ci-32.lst` | GRUB menu for CI 32-bit boot test (jnode32.gz + tests.jgz). |
| `qemu/menu-ci-64.lst` | GRUB menu for CI 64-bit boot test (jnode64.gz + shell.jgz). |

## Workflow Triggers

| Workflow | Trigger | Job-level `if` |
|----------|---------|----------------|
| `opencode` | `issue_comment: [created]` and `pull_request_review_comment: [created]` | body matches `/oc ` (prefix or preceded by space) AND verb is NOT `triage` AND body has no `opencode.ai/s/` session link (agent self-trigger guard) AND author is COLLABORATOR / MEMBER / OWNER |
| `triage` | `issue_comment: [created]` on issues (not PRs) | body verb is `triage` AND no session link AND author is COLLABORATOR / MEMBER / OWNER |
| `auto-triage` | `issues: [opened]`, `issue_comment: [created]` | opened (non-bot, no `no-auto`, untriaged) OR human reply on a `needs-info` waiter (prior `## Triage`, <3 passes, no `in-progress`) |
| `ticket-runner` | `issue_comment: [created]` (body starts with `/run`), `issues: [labeled]` (label starts with `kind/`), `workflow_run: [opencode, Java CI, Triage, completed]`, `pull_request_review: [submitted]` | startsWith `/run` OR actionable `kind/` label with clear triage OR `workflow_run` OR `pull_request_review` |
| `orchestrator` | `issue_comment: [created]` (body starts with `/orchestrate`) OR `workflow_run: [opencode, Java CI, completed]` | event is `issue_comment` with `/orchestrate` OR event is `workflow_run` |
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
- **auto-triage** is the receptionist. On every new issue it posts `/oc triage` (owned by the `jnode-triage-issue` skill); on reporter replies to vague triage it re-posts `/oc triage` so sufficiency is re-judged against the full thread.
- **ticket-runner** auto-starts DEV without manual `/run` once an actionable kind (`bug`, `feature`, `chore`, `wiki`, `test`) plus a CLEAR `## Triage` comment exist (via `issues: labeled` or the `Triage` workflow completion path). Triage-first: labeled-but-untriaged issues get `/oc triage` before any run. `no-auto` opts an issue out of all auto paths; manual `/oc` and `/run` still work.
- **orchestrator** is the foreman. It holds a JSON state in the master issue body, picks the next child task from the queue, and tracks its phase (DEV, REVIEW, HUMAN_REVIEW, FEEDBACK, MERGE).
- `orchestrator.yml` listens for `workflow_run` from `opencode` and `Java CI` plus `pull_request_review`. It advances the phase, loops back via `/oc fix` or `/oc review`, or merges the PR. On `Java CI` success it re-runs review for a deferred active PR; on failure it posts one `/oc fix` per SHA.

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
- **REVIEW**: Agent reviews the PR. The orchestrator posts `/oc review` with explicit instructions requiring the final line to be exactly `Verdict: approve` or `Verdict: request-changes`. If approved and the task is NOT auto-merge eligible (explicit `auto-merge` label or implicit safe `kind/chore,wiki,test`), transition to `HUMAN_REVIEW`. If eligible, verify the merge safety gate (diff small and clean per `isDiffSafe`, CI green per `isCIGreen`): pass -> `MERGE`, fail -> stay in `REVIEW` with a defer comment until CI completes. If changes requested, transition to `FEEDBACK`.
- **FEEDBACK**: Agent addresses review comments. Transition to `REVIEW`.
- **HUMAN_REVIEW**: Orchestrator waits for native GitHub PR review from a human maintainer. Approval -> `MERGE`, Request changes -> `FEEDBACK`.
- **MERGE**: Orchestrator squashes the PR and deletes the branch inline.

Initialization: on first run, the orchestrator parses the master issue's markdown checklist (`- [ ] #N`, `- [x] #N`, `- [FAIL] #N`) and builds initial state. Subsequent runs load from the hidden JSON block. If `state.order` is empty (older master), it is backfilled from status-grouped arrays.

Self-healing guard: if the orchestrator wakes up and the `current_task` is already complete (closed or has completion label), it advances immediately and exits. This handles the case where the agent finished but the orchestrator was locked out.

Locking: `orchestrator/locked` label is added at the start of every run and removed in `finally`. If a second run sees the lock, it exits silently (concurrency group `orchestrator-concurrency` enforces single-runner).

## Per-Ticket Runner (ticket-runner.js)

Drives an individual issue through the same multi-turn loop as the orchestrator, without requiring a master issue or queue.

```
                  +--------------------+
     /run ------> |  ticket-runner     | ---> posts /oc on issue (DEV phase)
                  +--------------------+
                            ^
                            | workflow_run (opencode completed)
                            v
                  +--------------------+
                  |  ticket-runner     | ---> finds PR, posts /oc review prompt (REVIEW phase)
                  +--------------------+
                            ^
                            | workflow_run (verdict returned)
                            v
                  +--------------------+
                  |  ticket-runner     | ---> verdict: request-changes -> /oc fix (FEEDBACK phase)
                  +--------------------+      verdict: approve + auto-merge -> squash merge
                            ^                 verdict: approve -> wait for human (HUMAN_REVIEW phase)
                            |
                  pull_request_review (human approved / changes requested)
```

State is stored in the issue body as a markdown status block and a hidden JSON comment:

```html
---
###  Ticket Runner Status

| Field | Value |
| --- | --- |
| **Phase** | DEV |
| **Turn** | 0/3 |
| **Retries** | 0/3 |
| **PR** | - |
| **Started** | 2026-09-06T22:00:00.000Z |

<!-- TICKET_RUNNER_STATE:
{
  "phase": "DEV",
  "pr": null,
  "turn": 0,
  "max_turns": 3,
  "retries": 0,
  "started": "2026-09-06T22:00:00.000Z",
  "history": []
}
-->
```

### Commands and Flags

- `/run`: Start a new multi-turn run, or re-trigger an existing run that stalled.
- `/run --turns <N>`: Start with custom turn limit (default: 3).
- `/run --reset` or `/run --fresh`: Reset state and start fresh even if a run was already in progress or completed.

### Key Behaviors

- **Guards**: Refuses `/run` if posted on a PR (direct `/oc` should be used instead), or if the issue is managed by the orchestrator (either the master issue itself or an issue currently queued in an active `IN_PROGRESS` master issue).
- **Manual vs auto-start**: A manual `/run` intentionally bypasses every auto-start guard - `no-auto`, blocking `agent/*` labels (including `agent/needs-info`), the actionable-kind requirement, the CLEAR `## Triage` requirement, and the "no runner state yet" check. Only the PR and orchestrator-managed guards above still apply. Automatic paths (`issues: labeled`, post-triage `workflow_run`, CI-heal) stay gated by all of them.
- **Auto-start**: No manual `/run` needed when the issue carries an actionable kind (`bug`, `feature`, `chore`, `wiki`, `test`), a CLEAR `## Triage` comment, no blocking `agent/*`, no `no-auto`, and no runner state yet. Report scans skip trigger comments (same rule as post-step). Fires on `issues: labeled` and on `workflow_run` triage-clear; labeled-but-untriaged issues get `/oc triage` first instead of a run. Once DEV produces a PR, the runner advances to REVIEW using either the pulls API or the `Created PR #N` issue comment; completion labels are only consulted when no PR exists.
- **CI wakeups**: On `Java CI` success the runner re-runs `/oc review` for a deferred `REVIEW` PR (green + still-approve leads to merge); on failure it posts one marker-deduped `/oc fix` per SHA on active `REVIEW`/`FEEDBACK` PRs.
- **Concurrency**: Grouped per issue/PR for comment and PR review triggers (`ticket-runner-<id>`). For `workflow_run` events, GitHub Actions does not expose the target issue in concurrency expressions, so runs are keyed by `workflow_run.id`. Parallel completions touching the same issue are rare and self-heal on the next turn or manual `/run`.
- **Human Review**: If the issue is not auto-merge eligible (explicit `auto-merge` label or implicit safe `kind/chore,wiki,test`), successful agent review transitions to `HUMAN_REVIEW`. Human approval via the GitHub PR Review UI triggers automatic squash merge and closes the issue. Eligible tasks merge automatically once the safety gate passes (diff safe + CI green); otherwise they wait in `REVIEW` with a defer comment.
- **Retries and Turn Limits**: Up to 3 retries per failed phase. Up to `max_turns` review feedback iterations before marking the issue with `agent/failed`.

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
| `kind/test` | Add or fix a test |
| `kind/question` | User question; expected output is an investigation comment |
| `kind/triage` | Ask the agent to triage a new issue (labels + checklist) |
| `kind/orchestrator` | **Master issue** that the orchestrator drives. Only ONE open master at a time. |

### agent/*  (agent status, grey)

| Label | Meaning |
|-------|---------|
| `agent/in-progress` | Agent is currently working (added by `opencode.yml` start) |
| `agent/needs-info` | Agent posted a VAGUE `## Triage` (contains the needs-info literals); waiting for the reporter |
| `agent/blocked` | Agent is blocked on an external dependency or build failure |
| `agent/done` | Agent finished successfully; PR opened or comment posted |
| `agent/failed` | Agent's run concluded with failure/cancelled; orchestrator will retry |
| `agent/skip` | Agent decided this is out of scope (refusal heading) |
| `agent/duplicate` | Issue duplicates another; comment links the original |
| `agent/investigated` | Agent posted an investigation report |

Six of these (`done`, `investigated`, `skip`, `blocked`, `needs-info`, `duplicate`) are recognized by the orchestrator and ticket runner as task completion signals (see `COMPLETION_LABELS` in `orchestrator-helpers.js`).

### area/*  (subsystem, green)

`area/core`, `area/fs`, `area/net`, `area/shell`, `area/gui`, `area/builder`, `area/docs`, `area/build`, `area/vm`, `area/test`.

### Bare automation labels (in sync-labels.js, no family prefix)

| Label | Meaning |
|-------|---------|
| `auto-merge` | Skip human review; runners auto-merge after agent approval. |
| `no-auto` | Skip all automation; human drives via `/oc` and `/run` only. Never set by agents or workflows. |

### Orchestrator-internal (not in sync-labels.js)

| Label | Meaning |
|-------|---------|
| `orchestrator/locked` | Set at orchestrator run start, removed in `finally`. |

## Post-Step Decision Tree (opencode-post-step.js)

Runs on every opencode.yml run, regardless of success/failure/cancelled.

1. Read the issue's existing `agent/*` label. If it's set and not `agent/failed`, respect it (except clear triage below, which clears stale `needs-info`).
2. Detect the latest NON-TRIGGER agent comment by heading (trigger comments carrying `/oc`, `/run`, `/orchestrate` are never reports). Priority order:
   - `Refusal` heading -> `agent/skip`
   - Vague triage text (`needs more info from reporter` / `needs the following` / `Suggested next: needs-info`) -> `agent/needs-info`. A bare `## Triage` heading alone does NOT match.
   - `Investigation Report` heading -> `agent/investigated` (verb-override)
   - `Created PR #N` or `Opened PR #N` comment -> `agent/done`
   - Clear triage (`## Triage` without vague literals or refusal) -> apply NO label; remove stale `agent/needs-info` if present (re-triage unstick)
3. Reports above win even on `failure`/`cancelled` runs (a triage run that dies at finalization after posting still delivered); with no recognized report, `failure`/`cancelled` -> `agent/failed`. No heading anywhere -> existing label respected, PR context -> `agent/done`, else default `agent/done`. There is deliberately no kind-based default: `investigated` requires the report heading, `duplicate` requires a `duplicate-of-#N` link.
4. If no heading and the context is a PR -> `agent/done`.
5. If no heading and issue is `kind/investigate` or `kind/question` -> `agent/investigated`.
6. Default -> `agent/done`.
7. Remove `agent/in-progress`.
8. Close the issue (unless already closed) when the applied label is `agent/duplicate` (any kind), or `agent/investigated` on `kind/investigate` or `kind/question`.

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
node --test .github/scripts/tests/*.test.js
```

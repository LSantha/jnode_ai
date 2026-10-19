# Agent Instructions for .github/

CI infrastructure, agent automation, and label conventions for JNode.

## Layout

| Path | Purpose |
|------|---------|
| `workflows/opencode.yml` | Single-issue agent runner (build agent). Triggered by `/oc <verb>` comments except `/oc triage`. |
| `workflows/triage.yml` | Dedicated triage runner: read-only analysis (locked checkout, no builds run, no commit/push/PR possible). Triggered by `/oc triage` only. JDK present; issues:write + read-only contents. |
| `workflows/auto-triage.yml` | Auto posts `/oc triage` on `issues: opened` and on human replies while `agent/needs-info` holds (re-triage, max 3 passes). Skips bots, masters, `no-auto`, and any issue not authored by the trusted allowlist (`LSantha`). |
| `workflows/ticket-runner.yml` | Single-issue multi-turn runner. Triggered by `/run`, by `issues: labeled` (`kind/*`), or by opencode completion. |
| `workflows/orchestrator.yml` | Multi-task batch runner. Triggered by `/orchestrate` or by opencode completion. |
| `workflows/ant.yml` | Plain Java CI: build + test + QEMU boot (32-bit and 64-bit) on every push/PR to master. The `heal` job posts one `/oc fix` per SHA on PRs when CI fails (skips `no-auto`/busy and any PR not authored by the trusted allowlist, marker-deduped). |
| `workflows/watchdog.yml` | Scheduled stall sweep (every 15 min) plus a manual `workflow_dispatch` with an `apply` toggle. Polls runner state and re-posts the trigger a dropped event would have delivered (see `scripts/watchdog.js`). Scheduled sweeps are dry-run by default; `apply` or flipping `WATCHDOG_DRY_RUN` to `0` enables posting. |
| `scripts/opencode-post-step.js` | JS post-step run by opencode.yml after the agent exits. Applies `agent/*` label, closes investigations. |
| `scripts/orchestrator.js` | JS state machine run by orchestrator.yml. Drives the queue, updates the master issue, triggers child tasks. |
| `scripts/ticket-runner.js` | JS state machine run by ticket-runner.yml. Drives single-ticket multi-turn DEV -> REVIEW -> FEEDBACK -> HUMAN_REVIEW -> MERGE loop, with `CANCELLED` as a terminal phase for issues closed mid-run. |
| `scripts/orchestrator-helpers.js` | Shared GitHub REST helper functions used by orchestrator.js and ticket-runner.js. Also the merge safety gate (`isAutoMergeEligible`, `isDiffSafe`, `isCIGreen`) and `findPRsForSHA` for CI wakeups. `mergePR` also stamps attribution: it collects the distinct `Assisted-by:` model trailers from the PR's review and comment bodies (`collectReviewerModels`) and, when any exist, passes an explicit `commit_message` reproducing the `COMMIT_MESSAGES` body plus `Reviewed-by: <models>` (`buildMergeMessage`, which is the message BODY only - `commit_message` does not touch the subject, which stays under `squash_merge_commit_title` (`COMMIT_OR_PR_TITLE`, and GitHub appends the ` (#N)` reference). |
| `scripts/report-signals.js` | Shared report-predicates (`isRefusalComment`, `isNeedsInfoComment`, `isTriageComment`, `isTriageClearComment`, `isTriggerComment`) consumed by opencode-post-step.js, ticket-runner.js and orchestrator-helpers.js. The label decider and the auto-start gate must agree on what counts as a report; a per-file copy drifts and silently stalls tickets. |
| `scripts/trusted-author.js` | Single source of truth for `TRUSTED_AUTHOR_LOGINS`, `isBotUser` and `isTrustedAuthor`. Required by orchestrator-helpers.js (so ticket-runner/orchestrator share it) and by the inline `actions/github-script` guards in auto-triage.yml and ant.yml. Those two jobs `actions/checkout` first; ant.yml's heal job checks out the trusted base (`github.event.pull_request.base.sha`), never the PR head, so a fork cannot swap the module in a job that holds `GH_PAT` and `issues: write`. |
| `hooks/prepare-commit-msg` | Installed by `opencode.yml` via `core.hooksPath`; appends an `Assisted-by: <model>` trailer (from `OPENCODE_MODEL`) to every agent commit, and strips any `Co-authored-by:` line (the agent has no co-author to credit; GitHub renders that trailer as an extra author). No-op when the env is unset or on a merge commit, so it never touches a human message. The agent commit identity is the maintainer (`opencode.yml`/`triage.yml` `git config user.*`), never a bot: GitHub renders the git author and `Co-authored-by:` as authors (avatar + Contributors list) and does not parse `Assisted-by:`, so the model must ride only in that trailer. |
| `scripts/watchdog.js` | Stall recovery run by watchdog.yml. Reads `TICKET_RUNNER_STATE`/`ORCHESTRATOR_STATE` and the Actions runs API; when a wakeup was lost (an agent/CI run completed but the state did not advance, or the state is idle past a timeout, or an idle `orchestrator/locked` has no run behind it) it re-posts `/run`, `/orchestrate`, or the marker-deduped CI-heal `/oc fix`, and removes a provably leaked lock. It never advances a phase, merges, or labels a ticket, so every existing guard still applies; it respects `no-auto`, blocking `agent/*`, untrusted authors, `HUMAN_REVIEW`, `MERGE` and terminal phases, and takes one nudge per state snapshot with a cooldown and an attempt cap. |
| `scripts/sync-labels.js` | One-shot label bootstrap. Idempotent. Use `--dry-run` to preview. |
| `qemu/jnode.properties` | CI build profile (used by opencode.yml and ant.yml). |
| `qemu/menu-ci-32.lst` | GRUB menu for CI 32-bit boot test (jnode32.gz + tests.jgz). |
| `qemu/menu-ci-64.lst` | GRUB menu for CI 64-bit boot test (jnode64.gz + shell.jgz). |

## Workflow Triggers

| Workflow | Trigger | Job-level `if` |
|----------|---------|----------------|
| `opencode` | `issue_comment: [created]` and `pull_request_review_comment: [created]` | body matches `/oc ` (prefix or preceded by space) AND verb is NOT `triage` and NOT `approve` AND body has no `opencode.ai/s/` session link (agent self-trigger guard) AND author is COLLABORATOR / MEMBER / OWNER |
| `triage` | `issue_comment: [created]` on issues (not PRs) | body verb is `triage` AND no session link AND author is COLLABORATOR / MEMBER / OWNER |
| `auto-triage` | `issues: [opened]`, `issue_comment: [created]` | opened (non-bot, trusted author, no `no-auto`, untriaged) OR human reply on a `needs-info` waiter (trusted author, prior `## Triage`, <3 passes, no `in-progress`) |
| `ticket-runner` | `issue_comment: [created]` (body starts with `/run` or `/oc approve`), `issues: [labeled]` (label starts with `kind/`), `workflow_run: [opencode, Java CI, Triage, completed]`, `pull_request_review: [submitted]` | startsWith `/run` or `/oc approve` (no `opencode.ai/s/` session link) OR actionable `kind/` label with clear triage OR `workflow_run` OR `pull_request_review` |
| `orchestrator` | `issue_comment: [created]` (body starts with `/orchestrate`) OR `workflow_run: [opencode, Java CI, completed]` | event is `issue_comment` with `/orchestrate` OR event is `workflow_run` |
| `ant` (Java CI) | `push: [master]`, `pull_request: [master]` | always |
| `watchdog` | `schedule` (every 15 min), `workflow_dispatch` (`apply`) | always (single sweep under `watchdog-sweep`) |

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
- **auto-triage** is the receptionist. On every new issue it posts `/oc triage` (owned by the `jnode-triage-issue` skill); on reporter replies to vague triage it re-posts `/oc triage` so sufficiency is re-judged against the full thread. Only issues authored by the trusted allowlist (`LSantha`) are auto-triaged: an externally authored issue gets **no** automatic processing at all, and is handled only once the maintainer posts `/oc triage` on it (see Trusted-author intake).
- **ticket-runner** auto-starts DEV without manual `/run` once an actionable kind (`bug`, `feature`, `chore`, `wiki`, `test`) plus a CLEAR `## Triage` comment exist (via `issues: labeled` or the `Triage` workflow completion path). Triage-first: labeled-but-untriaged issues request `/oc triage` once, unless a triage request is already present. The issue author must also be trusted (`LSantha`); an untrusted-author issue auto-starts only when a maintainer kickoff comment (`/oc triage` or `/run`, no `opencode.ai/s/` session link) is present. `no-auto` opts an issue out of all auto paths; manual `/oc` and `/run` still work.
- **orchestrator** is the foreman. It holds a JSON state in the master issue body, picks the next child task from the queue, and tracks its phase (DEV, REVIEW, HUMAN_REVIEW, FEEDBACK, MERGE).
- `orchestrator.yml` listens for `workflow_run` from `opencode` and `Java CI` plus `pull_request_review`. It advances the phase, loops back via `/oc fix` or `/oc review`, or merges the PR. On `Java CI` success it posts the pending `/oc review` for a `REVIEW` task whose review was waiting on CI, and re-runs review for a deferred active PR (skipped while `current_task.review_in_progress` is `true`); on failure it posts one `/oc fix` per SHA.

For single-step tasks, a child task is "complete" in the orchestrator's eyes when EITHER:
- the child issue is closed on GitHub, OR
- the child has one of: `agent/done`, `agent/investigated`, `agent/skip`, `agent/blocked`, `agent/needs-info`.

For multi-step PR tasks, completion requires reaching the `MERGE` phase (or short-circuiting on skip labels).

## Trusted-author intake

Automatic agentic processing is restricted to issues authored by the trusted allowlist (`TRUSTED_AUTHOR_LOGINS = ["LSantha"]` in `trusted-author.js`), so a third party cannot drive the pipeline by opening an issue. This is a login allowlist, not `author_association`: "COLLABORATOR" would silently trust any future collaborator, and the automation acts as `LSantha` via `GH_PAT`, so association cannot tell a human from a prompt-injected agent.

- **Untrusted issues get nothing automatic** - not even triage (`auto-triage.yml` skips them), and `autoStartGuards` refuses to start DEV for them. `heal` likewise ignores untrusted PRs.
- **The kickoff is a maintainer comment.** A maintainer processes an external issue by posting `/oc triage` (or `/run`). That comment - a trigger comment with no `opencode.ai/s/` session link - is what `autoStartGuards` accepts as the exception for an untrusted author. No approval **label** is used: because the automation posts as `LSantha`, an agent-added label would be indistinguishable from a human-added one, whereas agent comments carry the session link and are rejected.
- **Triage is never skipped.** `/run` is triage-first: on a ticket with no clear `## Triage` it requests `/oc triage` and starts DEV only once the report lands.

## Stall watchdog

The runners are event-driven, so a dropped `workflow_run` delivery - as during the GitHub Actions incident on 2026-10-16/17 - parks a ticket forever in `REVIEW` with `review_pending_ci: true` and nothing retries it. `watchdog.yml` polls state on a schedule so recovery does not depend on event delivery.

`watchdog.js` is a **trigger re-emitter, not a second state machine**: it never changes a phase, merges, labels a ticket, or takes the orchestrator lock. Its only actions are to post the same trigger a human would and to remove a provably leaked lock, so every existing guard still applies.

- **Evidence, not just a timer.** It requires an in-flight-free, positively-evidenced stall: an agent/CI run that completed without the state advancing, or the state idle past `WATCHDOG_*_TIMEOUT_MIN` (defaults 40-45 min, longer than any normal agent or CI run). A `REVIEW` awaiting CI fires only once the PR's Java CI run is `completed`, and only after `WATCHDOG_CI_SETTLE_MIN` (10 min) so the normal wakeup is not preempted.
- **Actions:** `/run` on a stalled ticket (the runner's `/run` escape hatch re-checks CI and stays triage-first), `/orchestrate` on an idle master, the marker-deduped CI-heal `/oc fix` on a red PR, and removal of a leaked `orchestrator/locked` (only when no orchestrator run is queued/in_progress and the label is older than `WATCHDOG_LOCK_MIN`).
- **Non-interference:** skips while any opencode/Java CI/orchestrator run is in flight; one nudge per state snapshot via a hidden `<!-- WATCHDOG:... -->` marker, plus `WATCHDOG_COOLDOWN_MIN` (30) and `WATCHDOG_MAX_ATTEMPTS` (3); respects `no-auto`, blocking `agent/*`, untrusted authors, `HUMAN_REVIEW`, `MERGE` (the `/run` hatch would post an agent prompt there), terminal phases, and closed issues; capped at `WATCHDOG_MAX_ACTIONS` per sweep; its own `watchdog-sweep` concurrency group.
- **Rollout:** dry-run by default (`WATCHDOG_DRY_RUN` unset). Run the workflow manually with `apply: true`, or set the scheduled `WATCHDOG_DRY_RUN` to `0`, once the dry-run logs are clean. Per-issue opt-out is the `watchdog/paused` label.
- **Known gap it works around:** `agent/in-progress` is never actually applied (the busy guards that read it are effectively inert), so the watchdog detects in-flight work from the Actions runs API instead.

## Orchestrator State Machine

State lives in the master issue body as a hidden HTML comment:

```html
<!-- ORCHESTRATOR_STATE:
{ "status": "IDLE|IN_PROGRESS|COMPLETED",
  "current_task": { "issue": 487, "pr": null, "phase": "DEV", "turn": 0, "max_turns": 3, "retries": 0, "review_in_progress": false },
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
| `history` | Event log with ISO timestamps, capped to the most recent 200 entries when persisted (`MAX_HISTORY_ENTRIES`). The runner's own store lives in the issue body, which GitHub caps at 65536 characters, and a suppressed CI retry pushes one entry per CI-heal completion. Diagnostic only: nothing reads it |
| `order` | Original task order from the markdown checklist; rendered top-to-bottom in the status table |
| `current_task.review_in_progress` | `true` once a `/oc review` has been scheduled for the active PR; cleared when the review workflow run completes. A green `Java CI` run skips `ci_green_rereview` while it is `true` (same guard as the ticket runner) |

### Phases

- **DEV**: Initial agent run. Agent creates a PR. Transition to `REVIEW`.
- **REVIEW**: Agent reviews the PR. The orchestrator posts `/oc review` with explicit instructions requiring the final line to be exactly `Verdict: approve` or `Verdict: request-changes`; the prompt is only posted once the PR's checks are green (a pending CI leaves `review_in_progress` false and the `Java CI` success wakeup posts it). If approved and the task is NOT auto-merge eligible (explicit `auto-merge` label or implicit safe `kind/chore,wiki,test`), transition to `HUMAN_REVIEW`. If eligible, verify the merge safety gate (diff small and clean per `isDiffSafe`, CI green per `isCIGreen`): pass -> `MERGE`; diff over budget -> `HUMAN_REVIEW` with a comment naming the budget as the cause; red CI -> stay in `REVIEW` with a "Java CI is not green yet" comment until the CI-success wakeup re-checks. `isDiffSafe` budgets test and production changes independently: production is capped at 100 added lines and 5 files, tests at 2000 added lines and 20 files. A small production fix bundled with its own regression test is therefore allowed, while a large production change is not. `mergePR` treats an empty/204 merge response as unconfirmed until the PR is re-fetched with `merged: true`. A review must not approve with an untracked 🟡 finding: request changes or create and link a follow-up issue. It must also not approve a PR that locks a known defect into a test: a test asserting current broken behaviour (even with a follow-up issue filed) is a 🔴, because the eventual fix has to rewrite the test. Observed three times in the shell test batch - see the anti-pattern in `.opencode/skills/jnode-issue-resolver/SKILL.md`. If changes requested, transition to `FEEDBACK`.
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
  "review_in_progress": false,
  "started": "2026-09-06T22:00:00.000Z",
  "history": []
}
-->
```

### Commands and Flags

- `/run`: Start a new multi-turn run, or re-trigger an existing run that stalled.
- `/run --turns <N>` or `/run --max-turns <N>`: Start with a custom review feedback turn limit of `N` (default: 3). Both spellings are accepted by the same regex (`/--(?:max-)?turns\s+(\d+)/` in `ticket-runner.js`); the value is stored as `max_turns` in the runner state.
- `/run --reset` or `/run --fresh`: Reset state and start fresh even if a run was already in progress or completed.
- `/oc approve` (posted on the PR): The maintainer's equivalent of a native GitHub PR approval, for the single-maintainer case where GitHub refuses self-approval. It only acts on a ticket in `HUMAN_REVIEW` (so it cannot short-circuit an in-flight agent review or a DEV run), resolves the ticket via `findIssueByPR`, and runs the same `approveAndMerge` path as a native review - so it re-checks `isCIGreen` and parks honestly on red CI. `--force` is the deliberate emergency override that merges without the CI re-check and records `human_approved_forced` in history. It is PR-only; on an issue, or on a ticket outside `HUMAN_REVIEW`, it replies with the reason and merges nothing. Excluded from the `opencode` trigger so it never starts an agent run. Agent-authored comments are ignored: the agent posts with `GH_PAT`, so it is the PAT owner (`OWNER`) and clears the collaborator gate, but every agent comment carries an `opencode.ai/s/` session link, which is the same term `opencode.yml`, `triage.yml` and `auto-triage.yml` already use to reject agent self-triggers.

### Key Behaviors

- **Guards**: Refuses `/run` if posted on a PR (direct `/oc` should be used instead), or if the issue is managed by the orchestrator (either the master issue itself or an issue currently queued in an active `IN_PROGRESS` master issue). Closing an issue is also a way to stop the runner: `autoStartGuards` refuses to start a run on an issue that is not `open`, and `advancePhase` cancels an in-flight run when the issue closes mid-run (`CANCELLED` phase plus `agent/skip` so the cancellation is visible on the closed issue). A closed issue is treated as self-closed by the post-step rather than abandoned by a human in the two shapes `shouldClose` produces - `agent/duplicate`, or `agent/investigated` (which `decideAgentLabel` sets only through `isInvestigationReport`, and which the post-step closes behind on any kind, because `/oc investigate` and `/oc explain` route to the investigation task type by verb) - and only when no PR exists, because both shapes close from a report or a duplicate link and the `DONE` path the exception protects runs only without a PR. Everything else, including a closed `kind/bug` carrying `agent/done` (the post-step's default branch for every non-investigation run) and a closed ticket carrying a stale `agent/investigated` with a PR open, is an abandonment: cancelled and marked `agent/skip`. `COMPLETION_LABELS` is deliberately not used here - `agent/done` is in it for the orchestrator's completion scan and would swallow the abandonment case. Three known holes, all reachable only through a human action or a lost state block, none of them new: `handlePullRequestReview` merges without passing through the guard, so approving a closed `HUMAN_REVIEW` PR still merges (an explicit approval read as an instruction to merge); the `Java CI` wakeup (`handleJavaCICompletion`) consults PR/check state rather than issue state, so it can still post `/oc fix` on a closed issue's PR; and `recoverLostState` rebuilds from an open PR rather than the issue, so a closed issue with a destroyed state block still gets a `/oc review` prompt on its PR. None of them is new behaviour, and each needs one extra check to close.
- **Manual vs auto-start**: A manual `/run` intentionally bypasses every auto-start guard - `no-auto`, blocking `agent/*` labels (including `agent/needs-info`), the actionable-kind requirement, the trusted-author requirement, and the "no runner state yet" check. It does **not** bypass triage-first: a `/run` on a ticket with no clear `## Triage` requests `/oc triage` and starts DEV only once the report lands, because triage is never skipped. Only the PR and orchestrator-managed guards above still apply. Automatic paths (`issues: labeled`, post-triage `workflow_run`, CI-heal) stay gated by all of them.
- **Auto-start**: No manual `/run` needed when the issue carries an actionable kind (`bug`, `feature`, `chore`, `wiki`, `test`), a CLEAR `## Triage` comment, no blocking `agent/*`, no `no-auto`, no runner state yet, and the author is trusted or a maintainer kickoff comment exists (see Trusted-author intake). Report scans skip trigger comments (same rule as post-step). Fires on `issues: labeled` and on `workflow_run` triage-clear; labeled-but-untriaged issues get `/oc triage` first instead of a run. Once DEV produces a PR, the runner advances to REVIEW using either the pulls API or the `Created PR #N` issue comment; completion labels are only consulted when no PR exists. A completed `Triage` workflow run (`workflow_run`, name `Triage` or `Triage #N`, any conclusion except `skipped`) is the ordering-safe path: `maybeAutoStartAfterTriage` re-reads the triage report and starts DEV only once a CLEAR `## Triage` exists, so a kind label event that arrived before the report does not need a second trigger.
- **CI wakeups**: On `Java CI` success the runner requests the pending `/oc review` for a `REVIEW` PR whose review was waiting on CI (`review_in_progress` false), and re-runs the review for a deferred merge (green + still-approve leads to merge); on failure it posts one marker-deduped `/oc fix` per SHA on active `REVIEW`/`FEEDBACK` PRs. A review is only ever requested once its PR's checks are green, so an approve implies CI passed. That includes the manual `/run` escape hatch: on a `REVIEW` ticket with red CI it replies with an honest comment and leaves the ticket parked (`review_pending_ci`), rather than posting a prompt the human-approval re-check would then refuse to act on.
- **Concurrency**: Grouped per issue/PR for comment and PR review triggers (`ticket-runner-<id>`). For `workflow_run` events, GitHub Actions does not expose the target issue in concurrency expressions, so runs are keyed by `workflow_run.id`. Parallel completions touching the same issue are rare and self-heal on the next turn or manual `/run`.
- **Human Review**: If the issue is not auto-merge eligible (explicit `auto-merge` label or implicit safe `kind/chore,wiki,test`), successful agent review transitions to `HUMAN_REVIEW`. The same transition applies to eligible tasks whose diff exceeds the auto-merge safety budget: the gate names the actual cause in its comment (diff budget vs red CI) and never promises a CI retry for a diff-budget block. Human approval triggers automatic squash merge and closes the issue - either a native GitHub PR Review approval or the `/oc approve` comment equivalent for a maintainer who authors their own PRs (see Commands and Flags); both enter through `approveAndMerge`, so both re-check CI. Eligible tasks with a safe diff merge automatically once CI is green; a red CI defers with an honest "Java CI is not green yet" comment and the CI-success wakeup re-checks. `mergePR` squash-merges and then verifies: a `204` or empty merge response is not treated as success on its own - the PR is re-fetched and `merged` must be `true`, otherwise the merge is reported as unconfirmed.
- **Phases**: `DEV`, `REVIEW`, `FEEDBACK`, `HUMAN_REVIEW`, `MERGE`, plus the terminal `DONE`, `FAILED` and `CANCELLED`. `CANCELLED` is reached when the issue is closed while a run is in flight (see Guards); it is terminal via `isTerminalPhase`, so a cancelled run is never re-triggered, and a `/run` on it starts a fresh DEV run like `DONE`/`FAILED`.
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
   - Clear triage (`## Triage` without vague literals or refusal) -> apply NO label; remove stale `agent/needs-info` and `agent/failed` if present (re-triage/recovery unstick)
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

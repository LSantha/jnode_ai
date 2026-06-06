# jnode-issue-resolver — validation harness

Static and functional tests for the `jnode-issue-resolver` skill. No network access required; everything is read-only except for the optional `sync-labels.js` re-run.

## Run

```bash
node .opencode/skills/jnode-issue-resolver/test/static-checks.js
node .opencode/skills/jnode-issue-resolver/test/route.test.js
```

Both exit `0` on success, `1` on any failure.

## What they check

### `static-checks.js` — Layer 1

| Group | Checks |
|---|---|
| 1. Skill file basics | frontmatter present; `name`, `description`, `license` set; description is dense; no "Load this FIRST" over-claim |
| 2. Cross-references | every `[[wiki-link]]` resolves; every cited repo-relative path resolves; every focused skill exists; every infra file exists |
| 3. Markdown structure | code fences balance; section count is reasonable |
| 4. Stale text | no `update-wiki.md` reference, no `hub-and-spoke` claim, no "Load this FIRST", no ownership claims over narrow skills |
| 5. `sync-labels.js` | `node --check` passes; file is executable; `--help` prints usage |
| 6. `AGENTS.md` | mentions all four skills; no stale `update-wiki.md` |

### `route.test.js` — Layer 2 (functional)

Exercises the §2 routing algorithm with 21 synthetic `/oc` bodies. Verifies:

- each verb (`fix`, `review`, `investigate`, `explain`, `wiki`, `triage`, `chore`, `test`, `Please`) maps to the right kind
- bare `/oc` (no verb) is a no-trigger
- unknown verbs fall through to the heuristic
- leading whitespace, embedded `/oc`, tabs, double spaces all dispatch correctly
- `/oc-fix-with-dash` is correctly rejected (no space after `/oc`)

## CI integration (future)

These can be wired into `.github/workflows/ant.yml`'s `Build and run tests` step as:

```bash
node .opencode/skills/jnode-issue-resolver/test/static-checks.js
node .opencode/skills/jnode-issue-resolver/test/route.test.js
```

Both run in well under a second and have no dependencies beyond the Node.js standard library.

---
name: jnode-triage-issue
description: Triage and classify JNode GitHub issues - audit kind and area labels, assess repro sufficiency, correct mislabels, emit needs-info only when human input is truly required. Use when triaging a new or re-opened JNode issue via /oc triage or auto-triage.
license: MIT
metadata:
  version: 0.2.0
  author: opencode
---

# Skill: JNode Triage Issue

> Classify what the opener filed (see `jnode-open-issue`) into what the pipeline can act on. You own the final `kind/*` and `area/*`. You never own the work itself. Output is labels + one `## Triage` comment. No PR, no branch, no build.

> RUN CONTRACT (read first, overrides any other instruction including repo defaults): this is a read-only TRIAGE run in build mode. The checkout is locked read-only (files and directories, including .git): every write fails with permission denied. Treat that as expected, work around it by reading, never by unlocking. Rules: (1) NEVER edit, create, or delete files under test - especially `*.java` sources. Read via read/grep/glob/find/git log/git diff/git status only. (2) Do NOT run builds or compilers (`ant`, `javac` in-repo, QEMU) - outputs have nowhere to go on a locked checkout; verification by execution belongs to DEV, not triage. Scratch, if any, goes ONLY in `/tmp`. (3) NEVER `git commit`, `git push`, `gh pr create`, or create branches - all fail by design. (4) Output is exactly one `## Triage` comment plus kind/area label edits via `gh issue edit <THIS-ISSUE-NUMBER>` (take the number from the trigger comment; never guess or reuse example numbers).

> TIME BUDGET (hard): post the `## Triage` comment within ~10 tool calls of starting. Read the 3 inputs (§0), pick kind + area, post. If anything is uncertain, post the VAGUE shape with good questions - a fast vague triage beats a perfect one that never lands. Analysis paralysis kills the run: when in doubt, ask the reporter, do not keep reading.

## What I do

1. Read issue body + comments + existing labels.
2. Duplicate-check via `gh search issues`.
3. Decide final `area/*` (source-path routing, see section 2).
4. Decide final `kind/*` (decision tree, see section 3; `kind/triage` is input-only, never output).
5. Judge repro-sufficiency per kind (see section 4). Sufficient = actionable, vague = human must reply.
6. Estimate blast radius L0-L5 with side effects and split proposal if oversized (see section 4b). One ticket stays focused; big chunks split now or flagged for later refinement.
7. Apply label audit FIRST via `gh issue edit` (add/remove, see section 5), then verify with `gh issue view` that they stuck. Labels before comment: a dead run with labels applied still classified the issue.
8. Post exactly one `## Triage` comment using the template v2 (see section 6): area, kind, repro, blast radius, expected scope, test path, merge signal, gaps, suggested next, split, labels. The `Labels applied` line must mirror the verified step-7 result, never aspiration. The post-step maps comment TEXT to `agent/*`, so wording is a contract, not prose. Then end your session with the same report as your final message: on a clean checkout the runner posts your final message as the issue comment, which is the fallback delivery path.
9. Write the body addendum (see section 7): mirror the verdicts into the issue body between markers so DEV and reviewers inherit them without scrolling comments.
10. Verify (mandatory): re-read labels (`gh issue view`) and body markers; redo any missing piece NOW. Then stop. Optional reproduction scratch in `/tmp` only after verification - the run may die at finalization and only posted work survives.

## When to use me

- Trigger `/oc triage` on an issue, or auto-triage on `issues: opened`.
- Re-triage after reporter replies to `agent/needs-info`, or when mislabeling is suspected.
- Never on PRs, never on `kind/orchestrator` masters, never when `no-auto` is present (unless human explicitly overrides).

Load explicitly:

```bash
skill({ name: "jnode-triage-issue" })
```

## Voice (maintainer tone, mandatory)

Write like a JNode maintainer doing intake, not like a visitor describing limits. Findings, verdicts, pointers. Never narrate your own constraints: banned phrases include `not allowed`, `cannot edit`, `can't edit`, `read-only`, `as per the triage rules`, `during triage mode`, `I am unable`, any apology, any explanation of the pipeline or your permissions. Never OPEN a comment with constraint narration - the first line states the verdict (area, kind, actionable or blocked). The reader wants the classification, not your autobiography. If blocked, state exactly what is missing and who provides it (`@reporter`), then stop. State hypotheses as hypotheses: never assert an unconfirmed root cause or mechanism (`race condition`, `deadlock`, `off-by-one`) as fact - write `suspected <X>, unconfirmed` plus what evidence would confirm it.

## 0. Inputs

```bash
gh issue view <N> --comments
gh issue view <N> --json labels,body,author,state
gh search issues --repo LSantha/jnode_ai "<stack excerpt | error | keywords>" --limit 10
```

Thread discipline (MUST, enforced by auto-triage re-passes): on first triage read body + all comments; on RE-triage (a `## Triage` already exists and a human replied since) re-read the FULL thread including every reply after the last triage - sufficiency is judged against body+thread, never body alone. If the reply answers the bank questions, the new triage is clear even when the body is unchanged.

Also read: `.wiki/index.md` Task Routing table, `.wiki/Glossary.md`, and the issue's Environment block if present (see `jnode-open-issue`).

## 1. Anti-pattern pre-gate (before any classification)

If the request matches resolver pre-flight (ASM touch for a Java-side bug, `jnode.properties` edit, speculative perf with no profile, >500-line refactor-as-one-issue, AI-slop/fake-report ask): STOP. Do not triage. Emit `## Refusal` per `jnode-issue-resolver` section 5.6 and exit. Triage must not launder an out-of-scope ask into `kind/bug`.

## 2. Area routing (source-path ground truth)

Route by implicated code, not by opener's guess. One issue can carry 1-2 `area/*` max; primary first.

| Area | Source signals | Notes |
|---|---|---|
| `area/vm` | `core/src/core/org/jnode/vm`, JIT compilers, `core/src/vmmagic`, `core/src/mmtk-vm`, TIB/IMT/GC/magic traces | JVM internals. Never propose ASM fix here from triage. |
| `area/core` | `core/src/core` (kernel, scheduler, classmgr, plugin mgr), `core/src/driver`, `core/src/native/x86/*.asm` stack frames | Kernel + driver framework. ASM frames route here but triage recommends a Java-side fix. |
| `area/builder` | `builder/src` (BootImageBuilder, JNasm), `all/build.xml`, `build.sh`, plugin descriptor tools | Build-time codegen. `*.asm` build failures that are tool bugs go here, not `area/core`. |
| `area/build` | `jnode.properties`, `all/conf/*-plugin-list.xml`, `.github/qemu/*`, `menu-*.lst`, boot-menu/grub packaging | Triage flags config-edit asks as anti-pattern unless trace proves otherwise. |
| `area/fs` | `fs/src/fs/...` + sub-hint in comment: `jfat/FAT12-16-32`, `ext2`, `hfs+`, `ntfs`, `iso9660`, `exfat` | Always add sub-hint line, e.g. `Sub: FAT32 BPB`. Load `filesystem-debug` later, not in triage. |
| `area/net` | `net/src/...`, ARP/IPv4/DNS/Ethernet/socket traces | Include interface + QEMU `-net` flags in questions if missing. |
| `area/shell` | `shell/src/...`, command framework, bjorne evaluator, built-in cmds | Include typed shell lines in questions if missing. |
| `area/gui` | `gui/src/...`, AWT/video/input/Thinlet/desktop | Include driver + resolution/mode in questions if missing. |
| `area/docs` | `.wiki/`, docs-only, `kind/wiki` asks | No boot/env needed; point at page. |
| `area/test` | `*/src/test`, `tests/`, `.github/qemu` harness, QEMU boot-test infra | Host-JVM (`cd <sub> && ant test`) vs boot-test split per `.wiki/Testing.md`. |

Label note: `kind/test` exists in `sync-labels.js` for test asks; pair it with the tested area (e.g. `kind/test` + `area/fs`).

## 3. Kind decision (output, never `kind/triage`)

| If the issue ... | Output kind |
|---|---|
| Has crash/stack + numbered repro + actual vs expected | `kind/bug` |
| Asks new behavior with problem + proposal + acceptance + how-to-test | `kind/feature` |
| Asks why/how with no fix requested, open-ended | `kind/investigate` (report closes it) |
| Usage/design question answerable in a comment | `kind/question` (comment closes it) |
| Refactor/typo/dead-code, no behavior change, splittable under ~500 lines | `kind/chore` |
| Explicit "add/fix test for X" | `kind/test` (+ matching `area/*`) |
| Docs/wiki spoke ask | `kind/wiki` |
| Review ask on a PR | `kind/review` (issues rarely; normally PR context) |
| `kind/triage` present from filing | Replace it with the resolved kind above; never leave `kind/triage` on a triaged issue |
| `kind/orchestrator` present | Not triageable. Post pointer to master-queue rules and exit. |

`kind/investigate` vs `kind/question`: investigate = codebase research with file:line findings; question = usage answer with wiki refs. Both are comment-only (post-step closes on `agent/investigated`).

## 4. Repro-sufficiency rubric (this decides needs-info)

Sufficient = pipeline can start DEV/investigation with no human reply. Vague = blocked.

- bug sufficient iff ALL: numbered steps from clean checkout + build target + boot/run cmd + actual behavior + expected behavior + log/trace excerpt (or `TEST-*.xml` excerpt). Missing any one = vague.
- feature sufficient iff ALL: problem + proposal/spec + requirements (incl. Java 1.6, ASCII, header, formatter) + acceptance checkboxes + how-to-test. Missing acceptance or test path = vague.
- investigation sufficient iff: single quoted question + scope In/Out. Else vague.
- chore/test sufficient iff: file/dir scope + verify command (`ant test` module or boot check). Else vague.
- question: sufficient iff question + context + what-was-tried present. Else vague.
- Product-decision blockers (wontfix vs feature scope call, duplicate ambiguity, embargo) always count as vague even if text is complete.

## 4b. Blast radius (method -> system; system health first)

JNode is a full OS: a one-line change in the wrong layer can break boot, all filesystems, or every plugin. Triage estimates impact WITHOUT reading the whole tree (5-10 greps max). Goal: keep each ticket focused; flag oversized work for splitting now or later refinement.

Levels (report the highest reached):

- L0 method: single private method, no callers outside the class. Ex: `FatChain.read()` off-by-one.
- L1 class: one class, public methods but callers inside the same package. Ex: `BootSector.decode()` field fix.
- L2 package: several classes in one package, same subproject. Ex: `org.jnode.fs.jfat` chain handling.
- L3 plugin: crosses a plugin boundary (`*.xml` descriptor, exported API, service lookup). Check `all/conf/*-plugin-list.xml` membership and `plugin.xml` exports. Any public API signature change is at least L3.
- L4 subproject: touches 2+ subprojects (e.g. `fs/` + `shell/`, `core/` + `builder/`) or shared IDs/constants.
- L5 system/boot: boot path (`BootSequence`, classmgr, scheduler, GC/JIT, `*.asm`, `all/build.xml`, ISO layout, `menu-*.lst`). Any L5 suspicion must say so explicitly even if the fix looks small.

How to estimate (cheap, static):

```bash
rg -n "<ClassName>" --type java | head -20
rg -n "<methodName>\(" <implicated-subproject>/src | head -20
rg -ln "import org.jnode.<pkg>" <other-subprojects>/src | head -10
rg -n "<service/interface>" --glob 'plugin.xml' | head -10
```

Axes to note (one line each in reasoning, only the verdict lands in the comment):

- Scope: files/classes count if changed naively.
- Coupling: external callers, plugin exports, public API surface.
- Boot-criticality: on the `System has finished` path? If yes, L5 + QEMU proof required in DEV.
- Damage potential: data loss (FS write path), deadlock/hang (scheduler/locks), unbootable ISO (build/boot), security boundary (drivers/net).
- Testability: host-JVM coverable (`ant test`) vs needs QEMU boot proof.

Health principles (always apply):

- Smallest safe fix wins. No drive-by refactors, no API widening, no cross-area cleanup in a bug ticket.
- Side effects and collaterals must be named: "touches X, risks Y, therefore verify with Z".
- One ticket = one level ideally (L0-L2). L3+ needs justification in the comment.
- Oversized (multi-area, L4/L5-spanning, or >~500-line estimate): do NOT silently keep it whole. Add a **Split proposal** with 2-5 child scopes (each L0-L2, each independently testable), fileable later via `jnode-open-issue`. Keep the parent triaged and actionable; splitting can be refined later by human or orchestrator. If reporter must decide the split, mark vague (needs-info) with the proposal as options.

Verdict format in the comment: `**Blast radius:** L<n> <unit> (<why>)`. Ex: `L2 package (org.jnode.fs.jfat, 3 classes, no plugin export)`, `L5 system (scheduler path, boot-critical, QEMU proof required)`.

## 5. Label audit (you own kind + area)

```bash
# Keep correct, add missing
gh issue edit <N> --add-label "kind/bug" --add-label "area/fs"

# Remove mislabels with reason (quote reason in Triage comment)
gh issue edit <N> --remove-label "area/net"
```

Rules:

- Opener hints are inputs. Correct a wrong `area/*` (e.g. `area/net` on a FAT bug becomes `area/fs`) and say why: `Area: area/net -> area/fs (trace shows org.jnode.fs.jfat)`.
- Cap at 2 `area/*`. Primary = fault site, secondary = harness (e.g. `area/fs` + `area/test`).
- Replace `kind/triage` with resolved kind; never stack `kind/bug` + `kind/feature` (pick one, note alternative in comment).
- Never touch `agent/*`, `kind/orchestrator`, `orchestrator/locked`. Duplicates: link original in comment; applying `agent/duplicate` directly is allowed (post-step respects existing `agent/*`), but prefer `Suggested next: duplicate-of-#M` and let the workflow close it unless certain.
- `no-auto` present: audit labels and comment, but state `Auto-run: skipped (no-auto)`.

## 6. Comment contract (wording drives labels)

Post exactly one comment. Two shapes. The narrowed post-step matches ONLY the vague literals - so a clear triage must NEVER contain the strings `needs more info from reporter`, `needs the following`, or `Suggested next: ... needs-info`. One stray phrase flips the label. `Suggested next` uses exactly one of: `fix`, `investigate`, `needs-info`, `duplicate-of-#M`, `wontfix` - never free text.

Field guide:

- **Expected scope** (delimitation of the solution): files, approximate lines, what changes, what must NOT change (no-go zones: ASM, public API, boot path unless required). This bounds DEV.
- **Test path**: how DEV proves it - host-JVM module (`cd <sub> && ant test`), QEMU boot proof (kernel/VM/driver/FS), or docs-only (no test). Name the exact command.
- **Merge signal**: the reviewer handoff. `safe` requires ALL of: kind is chore/wiki/test (or the issue already carries `auto-merge`), small expected scope (rule of thumb: at most ~5 files, ~100 added lines), no boot path, no public API change, and a working test path - always with the why. Everything else (bug, feature, L4+, boot-critical, ambiguous repro, product call) is `human-review` with the why. The merge policy auto-merges only eligible kinds after agent approval plus green CI; humans use this field to decide whether to even let it try.
- **Gaps**: report-gap signals. Each gap names the missing piece, who provides it, and whether it blocks. Blocking gaps force the vague shape; non-blocking gaps ride along into the body addendum for DEV.

Clear (actionable):

```markdown
## Triage

- [x] **Area:** <area/* applied, ex. area/fs (Sub: FAT32 BPB)>
- [x] **Kind:** <kind/* resolved>
- [x] **Repro:** <quoted minimal steps or spec pointer>
- [x] **Blast radius:** <L0-L5 verdict (why)>
- [x] **Expected scope:** <files, ~lines; changes vs no-go zones>
- [x] **Test path:** <exact verify command or docs-only>
- [x] **Merge signal:** <safe (why) | human-review (why)>
- [x] **Gaps:** <none | non-blocking signals for the addendum>
- [x] **Suggested next:** <fix | investigate>
- [x] **Split proposal:** <none - focused | child scopes>
- [x] **Labels applied:** <list, incl. removals with reason>
```

Vague (blocked):

```markdown
## Triage

- [ ] **Area:** <best guess + uncertainty>
- [ ] **Kind:** <best guess>
- [ ] **Repro:** needs more info from reporter
- [ ] **Blast radius:** <best estimate + what is unknown>
- [ ] **Expected scope:** <tentative, pending answers>
- [ ] **Test path:** <tentative>
- [ ] **Merge signal:** human-review (insufficient signal)
- [ ] **Gaps:** <blocking signals>
- [ ] **Suggested next:** needs-info
- [ ] **Split proposal:** <none yet | tentative, pending answers>
- [ ] **Labels applied:** <list>

Needs the following before work can start:

1. <specific Q1>
2. <specific Q2>
3. <specific Q3>
```

`Suggested next: duplicate-of-#M` and `wontfix` use the vague shape (blocked) with reason, without the `needs more info` literals unless answers are also needed.

## 7. Body addendum (carry verdicts forward)

After the comment is posted, mirror the verdicts into the issue body between markers so DEV and reviewers inherit area, kind, blast radius, expected scope, test path, merge signal, and gaps without scrolling comments. Markers make it idempotent (replace on re-triage, never duplicate). Never touch anything outside the markers.

```html
<!-- TRIAGE-ADDENDUM-START -->
## Triage Addendum (auto, <YYYY-MM-DD>)

- **Area / Kind:** <area/* + kind/*>
- **Blast radius:** <verdict>
- **Expected scope:** <bounds>
- **Test path:** <command>
- **Merge signal:** <safe | human-review (why)>
- **Gaps:** <none | signals>
- **Full report:** `## Triage` comment below.
<!-- TRIAGE-ADDENDUM-END -->
```

Recipe: single step, no intermediate files. `ADDENDUM` holds the inner markdown (no marker lines). `GH_TOKEN` is already in the runner environment. Replace `<OWNER>`, `<REPO>`, `<N>`:

```bash
ADDENDUM='<paste addendum markdown here>' python3 - <<'EOF'
import json, os, re, urllib.request
owner, repo, n = "<OWNER>", "<REPO>", "<N>"
tok = os.environ["GH_TOKEN"]
api = f"https://api.github.com/repos/{owner}/{repo}/issues/{n}"
req = urllib.request.Request(api, headers={"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json"})
body = json.load(urllib.request.urlopen(req))["body"] or ""
section = "<!-- TRIAGE-ADDENDUM-START -->\n" + os.environ["ADDENDUM"].strip() + "\n<!-- TRIAGE-ADDENDUM-END -->"
pat = re.compile(r'<!-- TRIAGE-ADDENDUM-START -->.*?<!-- TRIAGE-ADDENDUM-END -->', re.S)
body = pat.sub(section, body) if pat.search(body) else (body.rstrip() + "\n\n" + section + "\n")
data = json.dumps({"body": body}).encode()
req = urllib.request.Request(api, data=data, method="PATCH", headers={"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
urllib.request.urlopen(req).read()
print("addendum written")
EOF
```

Skip the addendum only on `kind/orchestrator` masters and PRs.

## 8. Needs-info question bank (pick 3-5, be specific)

- Build: exact `sh build.sh <target>` used? commit SHA (`git log -1 --oneline`)?
- Boot: 32 or 64-bit ISO? full QEMU/VBox command? did boot reach `System has finished`?
- Repro: numbered shell lines typed? file/image attached (`mkfs` cmd, size)?
- Logs: serial `com1.txt` excerpt around failure (20-60 lines)? `TEST-*.xml` excerpt?
- Area-specific: FS type + BPB/sub-hint? net `-net` flags + interface? shell command spelling? gui driver + mode?
- Scope decision: fix vs wontfix call needed from maintainer? duplicate confirmation (`#M` same stack)?

## 9. Idempotency

- If `## Triage` already in comments and labels already match routing and no new reporter info: post nothing (or a one-line `Triage verified, no change`) and exit.
- Re-triage fully when: reporter replied after `needs-info`, labels were hand-edited, or invoked with `--fresh`.
- Never emit `## Triage` twice on the same state; update labels instead.
- Completion runs (labels or addendum missing, verdicts unchanged): fix them silently. NEVER post a second content comment to announce completion work - no plan dumps, no narration. The `## Triage` comment stays single; the addendum and labels carry the rest.

## Negative constraints

- NEVER open a PR, branch, commit, or push from triage. The checkout is locked read-only so these fail by construction.
- NEVER edit, create, or delete files under test; reproduction by execution belongs to DEV, not triage.
- NEVER emit the vague literals in a clear triage (see section 6 contract).
- NEVER leave `kind/triage` on the issue after triaging.
- NEVER invent labels outside `sync-labels.js`; use `kind/test` + matching `area/*` for test asks.
- NEVER set `agent/*` except `agent/duplicate`, and then only together with a comment line `Suggested next: duplicate-of-#M` plus link and reason. A bare duplicate label with no explanation is a failed triage.
- NEVER close the issue yourself; the post-step owns close for `investigate/question`.
- NEVER exceed ~30 lines in the comment; details go in label reasons + one follow-up only if asked.
- NEVER leave an L4/L5-spanning ticket without a Split proposal or an explicit boot-proof requirement; keep tickets focused, split big chunks, refine later.

## Related pages / files

- `.opencode/skills/jnode-open-issue/SKILL.md` - filing templates triage consumes
- `.opencode/skills/jnode-issue-resolver/SKILL.md` - sections 2.5 (pre-gate), 5.4 (old template, superseded), 5.6 (refusal), 8 (anti-patterns)
- `.github/scripts/opencode-post-step.js` - heading-to-label map (content-match for needs-info)
- `.github/scripts/sync-labels.js` - label families (`kind/*` incl. `kind/test`, `agent/*`, `area/*`, `auto-merge`, `no-auto`)
- `.github/scripts/orchestrator-helpers.js` - `COMPLETION_LABELS` vs `SHORT_CIRCUIT_LABELS`
- `.wiki/Code-Conventions.md` - Java 1.6 + formatter + license header
- `.wiki/Testing.md` - host-JVM vs boot-test split
- `.wiki/Build-System.md` - `sh build.sh` targets
- `.wiki/Glossary.md` - term definitions for area hints

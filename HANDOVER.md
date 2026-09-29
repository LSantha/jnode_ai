# HANDOVER — JNode L2 compiler, branch `L2-SpaceBunny`

A self-contained briefing for an agent picking this work up. Everything below was
measured; nothing is aspirational. Where something is unproven it says so.

## 1. Read these first, in this order

| File | Why |
|---|---|
| `tests/l2oracle/AGENTS.md` | **The methodology — read this before touching anything.** Normative sections: "RULE: every fix ships with a regression guard" (the guard table, and what is *not* a guard), "Method contract" (8 rules, each learned from a wrong result), and "Regression methodology" (the short version: reproduce → measure → choose guard → validate cheapest-first → prove a lint fires → revert rather than ship unproven). Plus the census gate rules and the environment gotchas. Non-negotiable. |
| `tests/l2oracle/OPEN-BUGS.md` | The **authoritative queue**. Every open item, with mechanism, guard state and evidence. |
| `tests/l2oracle/CENSUS-FAILURES.md` | Copy-pasteable narrow repro for each of the 3 live census failures. |
| `AGENTS.md` (root) | Build targets, boot testing, code style. |
| `local/docs/L2-DEEP-REVIEW-REPORT.md` | The original code-read review (gitignored; if missing, the register cites its section numbers) |

**If you only read three things:** §2 (current state), **§11.6** (two process lessons that
cost the most time here, one of which silently deleted a fix), and §5.7 (the defect that
was holding the boot). §11 is the open investigation; §5.8 is its most interesting thread.

## 2. Current state

- **ANCHOR-L2-193 is the current headline: JNode now BOOTS on the L2 AOT backend.**
  User-confirmed working shell with `AOT=L2 / JIT=L1A` (see §5.7 for the mechanism, the
  red proof and the guard). This supersedes every "the L2 bootimage panics" statement in
  §5.6 — those were measured with the first-block-loop-header defect live.
- **The tree is COMMITTED, not pushed** (this `docs:` pass is the last of eight).
  From the bottom of `git log`: dupforms emitter, `fix(L2-193)` entry preheader,
  `fix(L2-194)` athrow shapes, `chore(boot)` `Identity*`, `diag(boot)` EIP/frame
  chain, `feat(L2-195)` AOT/JIT selection + fallback policy, `test(l2)` regress
  guards, this file. The `TEMP-DIAG` instrumentation mentioned below was in the
  shelf patch only -- it is not in the worktree, so there is nothing to strip, and
  the `/tmp/ANCHOR-L2-193-preheader.patch` insurance copy is obsolete.
- Last full gate, `--label pre193 host`: every phase PASS — `build`, `anchors`, t0, t3,
  t1, all-junit, census `FAILED=0 lints=0 rangegap=0 OK=11626`. Live legs,
  `--label pre193live isobuild oracle mauve`: all PASS, oracle **203 rows**, mauve
  `base=20 forced=20` with no force-only regressions, and **all 5 `entryWhile_ia` rows
  match the host** (see §5.7). The 3 remaining oracle diffs are byte-identical to the
  long-standing baseline (`div_iii` MIN/-1, `classLiteral_i` id encoding) and are not
  from this work.
- **The on-disk ISO is the ORACLE image** (23:43, written by `isobuild`), *not* an
  L2-bootimage image. `isobuild` and the host `build` phase write the same path. The
  L2-bootimage images are not preserved anywhere — the booting one was overwritten
  during the §11 investigation and had to be rebuilt. **Copy any ISO worth keeping to
  `/tmp` before changing build config.**
- **Two methodology facts that cost real time, both new this session — read §11.6.**
  (a) *Every* `regress` bootimage build uses `-Djnode.jit.compiler=L1A`
  (`regress.sh:171` and `:305`), so **the host gate cannot see any defect that only
  manifests when the runtime JIT is L2** — it is green *because* such code is inert.
  (b) `git checkout -- <file>` to strip a diagnostic **discarded the uncommitted
  preheader** and I reported the tree restored without checking that file.

- Branch `L2-SpaceBunny`, tracking `origin/L2-SpaceBunny` and **in sync with it** at the
  last check (`git rev-list --count origin/L2-SpaceBunny..HEAD` → 0). I was instructed
  never to push without being asked and did not; pushes happened outside this session.
  Verify with `git rev-list --count origin/L2-SpaceBunny..HEAD` rather than assuming.
- Host gates green at the last full run (`regress.sh --label b1fixgreen
  host isobuild oracle mauve 2`, every phase PASS):
  `build` (the bootimage AOT compile of every core class), `anchors`,
  **T0 19/19, T3 19/19, T1 40/40, all-junit 256/0**, census
  `OK=11618 SKIP=1511 HANDLERS=524 FAILED=0 RANGEGAP=0`, probe census
  `constreffield=0 failed=0`, all lints at 0. Live legs of the same run:
  oracle **198 rows** = the 3 retired divergences, **mauve v2 force-only
  DIFF empty** (base=52 forced=52). Red proof for B1 is
  `--label b1red` (fix reverted): guest returns `b1HandlerPhi|0|I:1` /
  `|5|I:1`, `ORACLE DIFF host_only=5 guest_only=5`.
- **Two gate checks could not fail and were fixed (ANCHOR-L2-187).** The probe
  census called `"$HJ"` inside `sh -c` where `HJ`/`LABEL` are unset, so it never
  ran and then counted a report file that did not exist -- `constreffield=0
  failed=0` was printed on *every* run, including the runs cited as the A8 and C6
  guards. And `t0`/`t3`/`t1` ended on `| tail -n 3`, so their verdicts could never
  be FAIL (measured: `t1` printed `Tests run: 40, Failures: 1` on a PASS line).
  Both now fail when they should; the rule is in `tests/l2oracle/AGENTS.md`
  ("a check that cannot fail looks exactly like a check that found nothing").
- **T1/T3 use `X86TextAssembler`, which runs no binary validation** -- an emission
  T1 accepts can still be rejected by `X86BinaryAssembler.testDst` when the same
  method is AOT-compiled into the bootimage. Treat the `build` verdict as part of
  the emission guard.
- Census, wide corpus (must be chunked — see §7): **67,838 methods verified, FAILED=0 —
  clean for the first time.** It stood at `FAILED=2` until NEW-2 landed (§5.2).
- Live oracle: 196 rows, only the 3 retired divergences. Mauve **v1–v4 CLEAN**; v5's only
  signal is the known benign `DoubleTest` (fails in the baseline, passes under L2).
- **The on-disk ISO is whatever the last phase wrote** — an `isobuild` leaves the
  L1A oracle image (currently the case, after `--label b1fixgreen`), a host `build`
  or a `boot` phase leaves an L2-bootimage image that panics. Never infer it from
  the timestamp: read the `.artifact` stamp / the `ARTIFACT` status line, and run
  `isobuild` before a bare `oracle`/`mauve`.
- Landed since the previous handoff update (`a1a7e41c3`): **L2-181** (A8), **L2-182**
  (C6), **L2-184** (A9, superseding the guard-only L2-183), **L2-185** (NEW-2),
  **L2-186** (A10), **L2-189** (B1, this session), plus a register-drift correction.
  All are in the authoritative queue with their red proofs. Also in this session:
  **L2-187** (the two regress.sh verdicts that could not fail, described below — it
  shipped inside the L2-186 commit) and **L2-188** (`L2Dump --raw` / `--ssa0` views,
  the tooling that located B1; still uncommitted, rides with L2-189).
- **A10 / L2-186** was found by *constructing* the shapes the gates did not have:
  `((H) null).<const field> = <const>` for int/long/double/reference and the wide
  null-getfield. The A8 fix's CONSTANT arm passed the value as the operand size, so
  every constant-valued null-base putfield failed to compile; the wide getfield read
  its high half off the value instead of off null. Guard = 3 new `PrimitiveTest`
  shapes (T1 `testNullConstFieldStores`) + 4 new `Probes` methods through the
  repaired probe census. Red proof: T1 40/1 + alljunit 1 error + bootimage `build`
  FAIL + probe census `failed=3`.
- **B1 / L2-189 — the queue's oldest IR row, fixed this session, and its title was
  wrong.** The row said the handler-entry phi is *never written* on the ordinary
  try/catch dispatch; the truth is the reverse: the phi sources are the **pre-try**
  versions, so the home is written, just with a value from before the try body ran,
  and every store made inside the try before the throw is invisible to the handler.
  Measured chain: raw IR has `x = 7` before the `idiv`; after `constructSSA` the phi
  already reads the **end-of-block** version; const-prop folds the store, DCE deletes
  it, and the handler reads the pre-try 1 from `[ebp-16]`. Neither available extreme
  is correct (pre-try loses the store = this bug; end-of-block names a home the throw
  never wrote = the ANCHOR-L2-125 crash), so the fix snapshots the slot tops at the
  predecessor's **first throwing quad** and feeds that to `rewritePhiParams` for
  `pred != succ` handler edges. Guard = `Probes#b1HandlerPhi` + 2 `CASES` rows
  (host `I:7` / `I:9`), red with the fix reverted (`I:1` / `I:1`). Full chain and
  evidence labels are in `OPEN-BUGS` B1. Found with the new `L2Dump --raw` / `--ssa0`
  views (L2-188), not with any structural lint — the structural lint for this shape
  was written, could not identify handler blocks, examined 0 phis and was removed.

## 3. Regression methodology — where it lives

**It is already written down, and it is normative: `tests/l2oracle/AGENTS.md`.** Do not
re-derive a method here; read these three sections:

- **"RULE: every fix ships with a regression guard"** — the guard table, strongest
  first (whole-corpus census lint → SSA/de-SSA structural guard → synthetic
  emitter/pipeline test → value-level probe → label/emit invariant), and the explicit
  list of things that are **not** a guard on their own ("it compiled", a mauve diff
  without single-testlet isolation, a probe with no recorded pre-fix value, an
  observation with no reproducer).
- **"Method contract"** — 8 numbered rules, each learned from a wrong result, e.g.
  *verify the artifact, not the intention*; *verify the class, not the source*; *a
  check's exit status is its verdict*; *one workload per guest boot*; *measure the
  baseline before theorising*; *nothing lands unvalidated*; *revert rather than leave
  the tree red*; *after two failed attempts on a blocker, write down the evidence and
  move on*.
- **"Regression methodology"** (added this session) — the short operational order:
  reproduce → measure → choose the guard → validate cheapest-first (probe rows →
  `regress.sh host` → `oracle` → mauve) → if it is a structural claim, prove the
  instrument fires on the known case → revert rather than ship a fix you cannot prove.
  Also the census gate rules (core is the default gate; the wide corpus must be
  chunked; missing deps and harness limits are listed skips, never failures).

`HANDOVER.md` deliberately does not restate the method — it only records the state of
this particular branch and the environment traps (§6, §9).

**Guards and probes added since the previous update** (all in the committed tree):
- **`CONSTREFFIELD` census lint** — an *emission* check: a getfield/putfield through a
  CONSTANT ref must write its destination / contain a store. Required because A8's quad
  is well formed and only the emission is wrong. `regress.sh` now also censuses the
  **compiled probe classes** and requires `CONSTREFFIELD==0 AND FAILED==0` there, so this
  lint can never again read 0 merely because its shape is absent.
- `Probes#nullFieldRead` / `#nullFieldWrite` — keep A8's shape reachable
  (`((Holder) null).f` and `…f = v`; javac emits `aconst_null; get/putfield` with no null
  check). Both rows throw NPE host *and* guest; the NPE is **not** the evidence for the
  fix, the lint is.
- `Probes#casOfs_iio` — the C6 reproducer: one
  `org.vmmagic.unboxed.Address.attempt(int, int, Offset)` call (`ATTEMPTINT_OFS`) with 8
  live locals summed after it, forcing the `Offset` into a frame slot. **No `CASES` row by
  design** — the oracle driver cannot construct an `Address`/`Offset` argument, and C6 is
  a *refusal to compile*, so its guard is the probe-census `FAILED==0`.

## 4. The user's standing directives

1. **Work autonomously.** Do not ask for permission on routine actions; proceed and
   report afterwards.
2. **Impact order:** compiler bugs first, then coverage invariants, and the L2
   **bootimage last** — bootimage work is explicitly deferred until the open bug queue
   and the invariants are exhausted.
3. **Every fix ships with a guard that fails when the fix is reverted**, with the red
   proof recorded in the commit message. "It compiled" is not a guard. The full method,
   the guard table and the validation order are in `tests/l2oracle/AGENTS.md` — follow
   it rather than improvising; §9 below lists the approaches that already failed.
4. **Live regression testing is never skipped** (only bootimage is). It is the most
   convincing evidence because it compares *values*, host JDK vs JNode under L2 force.
5. Never commit secrets; never force-push; never push unless asked.
6. **Every bug I identify gets fixed. A census is not evidence and never discharges
   anything** (added 2026-09-27, after A8, C6 and A9 were each left "measured latent" on
   census evidence). "0 occurrences across 67,838 methods" says only that a shape is
   *rare in today's corpus* — never that the code is right, and never anything about code
   that does not exist yet. So **"measured latent", "guarded", "0 occurrences", "no
   production call sites" and "closed on measurement" are NOT resting states.** An
   identified defect is either fixed, or the naming is withdrawn by *reading* the code —
   never by counting hits. The tell: if a fix is available and cheap, measuring
   unreachability is what you do *instead of* fixing. This rule is recorded in
   `OPEN-BUGS.md` and `tests/l2oracle/AGENTS.md` too; §5.5 lists the items it reopens.

## 5. The queue, with the next concrete action for each

### 5.1 NEW-1 (P0) — `a[i++]` reads the incremented index — **FIXED (guarded); residual moved to NEW-1b**

The highest-value open item. Found via the first-ever mauve v2 sweep
(`AcuniaPropertiesTest` passes unforced, throws under force), bisected to the single
method `java.util.Properties.loadConvert`.

- **Mechanism:** `a[i++]` is `aload a; iload i; iinc i,1; iaload` — the index operand
  is pushed *before* the `iinc`. A live local's home is its **index**
  (`X86StackFrame.getEbpOffset`), and SSA clones copy their original's `Location`. An
  argument's home is fixed and is never register-allocated, and a clone of a
  `MethodArgument` is itself a `MethodArgument`, so the allocator skips it too —
  therefore **every SSA version of an argument slot shares one frame slot**, and a
  definition clobbers an older version that is still live. L2 emits
  `qb_13: add dword[ebp-20],1` then `qb_14: mov ecx,dword[ebp-20]`.
- **Guarded (value-level, red proof):** `postIncrRead_aii` host `0x32`=50 vs guest
  `0x46`=70; `postIncrStore_aiii` host `0x2c4`=708 vs guest `0x7`=7; plus
  `postIncrLoop_aii` (one variant even throws `ArrayIndexOutOfBounds`). All in
  `tests/l2oracle/Probes.java` + `OracleDriver.CASES`.
- **Two fix attempts were made and reverted** (both are documented in the register):
  an unlocated `MethodArgument.clone()` NPEs in `removeUnusedVars:144` on a null
  `assignQuad`; returning a `LocalVariable` instead builds but collapses the corpus
  (`OK` 11607→1556) with `SSA-PRE: use without def … reads l0_1`, because the def quad
  attaches to the *original* argument object.
- **Two fixes landed (2026-09-27), each with red proof:**
  1. `LinearScanAllocator.allocate()` **ANCHOR-L2-171** — a *defined* `MethodArgument`
     version gets its own spill home; only the incoming value (`getAssignQuad()==null`)
     keeps the JVM slot. `setSpilledVariables` de-aliases the spilled list and
     `endMethod` reserves those slots, so the frame still holds the incoming value.
  2. `IRControlFlowGraph.flushCopy` **ANCHOR-L2-176** — an *edge* copy (destination
     block != the phi's block) is placed at `max(source-def end, last in-block read of
     the phi result + 1)`, clamped before the terminator. The old ANCHOR-L2-159
     "first use" bound is only correct when the copy is flushed into the phi's own
     block (handler-entry / source-in-block witnesses keep it). Plus a
     `removeDefUseChains` guard: refuse copy coalescing when a live quad between the
     def and the copy reads the copy's LHS.
- **Guards:** the 7 `postIncr*` rows in `OracleDriver.CASES` (red before, green now),
  `regress.sh --label new1-fix2 host` (alljunit 254/0, census `OK=11608 FAILED=0`,
  lints=0) and the live oracle (`host_only=3 guest_only=3` = only the 3 retired
  divergences).
- **Next action:** NEW-1b landed in §5.1a and NEW-3 in §5.3; the queue continues at NEW-2 (§5.2).

### 5.1a NEW-1b (P0) — residual `java.util.Properties` force defect — **FIXED (guarded, red proof)**

Two halves of one defect class: live ranges built from a purely LINEAR walk
of addresses while the CFG says otherwise.

1. **Range END** (`loadConvert`'s `\u` branch): `Quad.computeLiveness` raises
   `lastUseAddress` at each use it sees, but a value that must survive a *cold*
   failure-path `call` (a bounds check's failure block, laid out after the
   reader) never had that call inside its range, so ANCHOR-L2-107's forced
   spill did not fire and the digit scratch (`sal esi,4`) reused the register
   holding the converted length -> `MISMATCH uni` with a stale `convtBuf` tail.
   Measured: `--ranges s11_14: 8-11 -> 8-84`, `--homes ebp-48`, asm reloads the
   bound from `[ebp-48]`.
2. **Range START** (the mauve headline): a loop-carried phi is de-SSA'd into
   copies in the LATCH blocks, which lay out AFTER the blocks that read it, so
   `Variable.getFirstDefAddress()` is a high address. `AcuniaPropertiesTest#test_store`
   defines `start` (`l5_4`) at 163/168 and reads it at 137/141/151 -> range
   `[164,169]`, a hole at the start. Nothing else could see it: the IR was
   right, the frame reserved enough slots, and the allocator only COMPARES
   ranges, so a hole looks exactly like "not live yet" -- EBX went to the
   inner-loop temporaries as well (`s10_67` 139, `s10_71` 145, `s9_68` 147)
   and the guest called `new String(ba, <ba.length>, ...)` ->
   `StringIndexOutOfBoundsException: 892382384` at check 54.

**Fix:** `IRControlFlowGraph.extendRangesAcrossBackEdges()` handles both ends
of the same dataflow -- live-OUT of a block raises `lastUseAddress` (ANCHOR-L2-177),
live-IN pulls the start back to that block's top through the new
`Variable.noteLiveFrom(blockStart - 1)` (keeps the minimum, like `noteDef`).
Straight-line methods are untouched.

**Guard:** census lint `RANGEGAP` (ANCHOR-L2-178, `L2Census.checkRangeCoverage`):
every use must lie inside its variable's final range; wired into the census
gate as its OWN verdict (`rangegap=0`, not just an informational count).
Red proof, `noteLiveFrom` disabled:
`RANGEGAP ...AcuniaPropertiesTest.test_store var=5 use=137 range=[164,169]` and
`...test_save var=5 use=148 range=[175,180]` -- a second, latent victim the
crash had hidden; enabled, `rangegap=0`.

**Evidence:** `regress.sh --label new1b7 host isobuild oracle mauve 2` green
(build, anchors, t0 19, t3 19, t1 38, alljunit 254/0, census
`OK=11610 FAILED=0 lints=0 rangegap=0`, isobuild), live oracle rows=193 =
only the 3 retired divergences, **mauve v2 force-only DIFF empty**
(base=52 forced=52). Guest: `AcuniaPropertiesTest` `DONE checks=81 failed=0`
(was `THREW after 54`), `PropsForce forceprops` `forced 24 of 24 ... OK`,
`ConvProbe` CLEAN/DIRTY/ASCII/ESC all correct.

**Next action:** NEW-3 landed in §5.3; the queue continues at NEW-2 (§5.2).

### 5.2 NEW-2 (P1) — `throw` in jsr/finally reads a non-dominated exception variable — **FIXED (L2-185, red proof)**

The phi that defines the variable is the first quad of the **next** block, with
`UndefinedVariable` sources on the entering paths. `dominates()` walks the idom chain, so
this was not an address-ordering artefact.

**Mechanism (measured, not theorised):** when a phi source is an `UndefinedVariable`,
`deconstructOnePhi` built the edge copy and then marked it `setDeadCode(true)`, on the
stated theory that it was "a real SSA def for verification but must never be read by the
code generator". Both halves were wrong: **a dead quad is invisible to the SSA verifier
*and* to liveness**, so it was not a definition at all and the join's incoming edge was
left undefined. A temporary probe pinned the firing sub-case — phi `l12_3` in join
**B542**, two `UndefinedVariable` sources tagged with the synthetic critical-edge blocks
**B-2147483645** and **B-2147483644**, so two of that phi's three incoming edges carried
no live def.

**Three lowerings were measured, two wrong:**
1. live copy of the `UndefinedVariable` → complaint moves to its own operand
   (`read of u12_0 at 176: l12_3 = u12_0 in B-2147483645 is not written on every path`);
2. self-copy `x = x` → a real definition, tolerated by the verifier, but **elided as a
   no-op** and failure (1) returns;
3. **define the phi's result to the type default on that edge** — `ConstantRefAssignQuad`
   with `Constant.getInstance(0)`, because a null reference in this IR is an
   `IntConstant(0)` (as `IRGenerator.NULL_CONSTANT` shows; a `ReferenceConstant` reaches
   the emitter as `Non-int constant def: null`). This is the JVM rule for an
   uninitialized local, it survives to the verifier, and liveness gets a real def. The
   ctor re-types the shared lhs, so the phi's type is restored after (ANCHOR-L2-137).

**Red proof:** both corpus methods `FAILED=1 → 0`; **wide census `OK=67836 FAILED=2` →
`OK=67838 FAILED=0`**, clean for the first time (gnu 18340 → 18342). Host gate 7 PASS,
live oracle 196 rows / 3 known divergences, mauve v1–v4 CLEAN, v5 only the benign
`DoubleTest`. Narrow repros are kept as regression recipes in `CENSUS-FAILURES.md` and
both go red again with the fix reverted.

**Known limit, recorded not hidden:** non-reference bottoms keep the self-copy. This IR
has no constant-assign quad for primitives, and the index-based `BinaryQuad` ctor
*clones* its lhs (`AssignQuad.java:48`), which would break the shared-lhs invariant
L2-137 depends on. No such instance is known; with the census clean, one appearing would
surface as a census `FAILED`, not silently.

### 5.3 NEW-3 (P1) — `putstatic float <constant>` did not compile — **FIXED (guarded, red proof)**

Measured, and *not* the `$$ic`/F1 barrier family the original note suspected. `javap` says
`private static float EST_LINES` and `<clinit>` is `ldc 2.1f; putstatic F; iconst_0;
putstatic Z; return`, so `fieldRef.isWide()` is **false** and the NARROW arms of
`generateCodeFor(StaticRefStoreQuad)` ran — those tested only `instanceof IntConstant`, while
`ldc <float>` yields a `FloatConstant`, so the value fell through to
`throw new IllegalArgumentException()` at `GenericX86CodeGenerator:6889` and the method emitted
nothing at all. The corpus already knew the bit conversion: `constBits32` exists for putfield and
array stores ("HashMap#<clinit> CCEs here"); only the `putstatic` arms were left out (ANCHOR-L2-094
added the int one, L2-095 the wide pair).

**Fix:** both narrow CONSTANT arms accept `FloatConstant` and emit `constBits32`, i.e. a raw
32-bit move of `Float.floatToRawIntBits` (emission: `mov eax,0x40066666` then
`mov dword[edx+152],eax`).

**Guards:** T1 `testFloatConstPutStaticStores` (new `static float floatConstStatic = 2.1f` in
`PrimitiveTest`'s `<clinit>`, beside L2-149's `wideConstStatic`; pins the immediate *and* the
following store) plus a value probe `staticFloatBits` + `CASES` row (read-back, so a
materialize-without-store regression fails too). Red proof with the fix reverted: T1
`Tests run: 39, Failures: 2`; census over `Probes` `FAILED=2` (`Probes#<clinit>`,
`Probes#staticFloatBits`); the classlib narrow repro back to `FAILED=1`.

**Evidence:** `regress.sh --label new3a host isobuild oracle mauve 2` green (t1 39, alljunit 255/0,
census `OK=11611 FAILED=0 lints=0 rangegap=0`), live oracle rows=194 = only the 3 retired
divergences (the new row matches the host), mauve v2 force-only DIFF empty; `census-wide.sh`
FAILED 3 → 2.


### 5.4 Unisolated behavioural signal

mauve v5 reported `FIXED-UNDER-FORCE(?): gnu.testlet.java.lang.Double.DoubleTest
base fail=1 -> force fail=0` — a testlet that *fails* unforced and *passes* forced, the
opposite direction. Needs single-testlet isolation before it is believed
(`mauve_diff.sh` cross-testlet state has produced phantom results before).

### 5.5 Remaining queue — under the new §4.6 rule, *fix*, don't measure-and-park

`C1`, `C2`, `C4`, `C5`, `D1`, `D2`, `B2`, `B3`, `P11–P19`, `H6`, `M2`, `M5`.

**Reopened by §4.6 (were parked on census evidence, which is not evidence):**
- **C2** `removeDefUseChains` coalescing aliases the def's LHS onto the copy's LHS
  (report P10: *"aliasing, no clone"*), guarded only by L2-171's read-between check.
  A fix was written once and **reverted** because instrumenting it showed 4,352
  coalescing sites with all three hazards at 0. Under §4.6 that is not a resting place.
  **This is the most direct instance of the rule still outstanding — do it next.**
- **A6** `FREM` is closed as **CLOSED-NB on 14 probe rows matching the host**. That
  justification is measurement, not structure. Either re-derive it by reading the
  emission (all six arms provably correct) or fix it.
- **C1** and **NEW-1a** are **register drift**: C1 reads OPEN but is fixed (L2-169,
  `LiveRange` total order); NEW-1a is a dead row superseded by NEW-1's landed fix. Same
  class of lie as A8's row once was. Cheap; do it early, the queue is the authority.

**No longer in this list:** C6 (→ L2-182), A8 (→ L2-181), A9 (→ L2-184), NEW-2
(→ §5.2), **B1 (→ L2-189, §2)**. **B3** and **D2** are unitemised clusters and must be
itemised before they can be fixed — that is a prerequisite, not a dodge.

### 5.6 Bootimage — now the front of the queue, and F1's description is **wrong**

> **SUPERSEDED IN PART BY §5.7 — read that first.** Everything below was measured while
> the first-block-loop-header defect (§5.7) was still live, and that defect is what made
> the L2 bootimage wedge. With it fixed, **AOT=L2 / JIT=L1A boots to a working shell**.
> The stack-overflow analysis below is still good and still relevant; the "panics at
> `0x10B807`" conclusion is not the current state. The residual live question is the
> **runtime** L2 compiler, which is a different thing entirely — see §11.

`sh tests/l2oracle/regress.sh --label <l> boot --boots N` builds an **L2-compiled
bootimage** (`rm -rf all/build/x86/cdrom-lite/ox` + touch the L2 sources) and boots it.
The compiler queue has no reproducing defect left, so per §4.2 this is next.

**Measured this session (deterministic, 5/5 boots):**
- L2 bootimage **builds and boots**, then panics at **EIP `0x10B807`**, reporting
  `Real panic: int_die_halt!` and vector `int: 00000031`.
- **`int: 00000031` is `int_stack_overflow`, and that is the root cause** — this
  paragraph supersedes everything below it that treats `0x10B807` as the faulting
  code. `core/src/native/x86/ints.asm:45` (`int_noerror int_stack_overflow,0x31`,
  "Stack overflow trap") and `:115` (`intport 0x31, int_stack_overflow, 3`); the
  handler is `core/src/native/x86/vm-ints.asm:484`, which falls through to
  `doFatal_stack_overflow` and prints `Real panic: int_die_halt!`. **L2 emits this
  instruction itself in every method prologue**: `X86StackFrame.java:201-206`
  (`stackEndOffset = entryPoints.getVmProcessorStackEnd()`,
  `cmp ESP, <stackEnd>`, taken arm) then `os.writeINT(0x31)`. So the panic is by
  construction **L2's own stack-limit check firing**, not a wild call through a
  bad object — `Real panic: int_die_halt!` is the symptom, `0x31` is the cause, and
  the `0x10B807` block the previous reading dissected is only where the reporter's
  saved state pointed.
- **The boot-thread stack is ~60 KB and it runs out.** `_$$Setup_initial_thread`
  (builder-generated, identical under L1A and L2) does `mov edx,_$$initialStack;
  [ebx+28]=edx; lea edx,[edx+1024]; [ebx+32]=edx; [proc+24]=edx`, so `stackEnd =
  0x10D488 + 1024 = 0x10D888`, and `_$$initialStack` is
  `dd 1102984, dd 1167496` = `0x10D488 .. 0x11C3E8` — usable region
  `0x10D888 .. 0x11C3E8`, **~60 KB** with a 1024-byte red zone below it. The panic
  dump's `ESP=0010D488` is `_$$initialStack[0]` exactly (likely the fatal handler's
  own reporting stack, so do not read it as "the faulting SP was here"). L1A boots
  from the same region with the same setup code, so the difference is L2's frame
  cost: L2 reserves a frame home for **every SSA version**, where L1A reuses homes,
  so an L2 frame is much larger and the same call depth exhausts 60 KB.
- The failure window is unchanged: last guest line `Initialize BootLog`, and a valid
  L1A boot prints `Detected 1 processor` (`VmImpl.java:288`) right after — i.e.
  `VmSystem.initialize()` between `BootLogImpl.initialize()` and processor detection.
- The **signature moved**: 3 older boots recorded `0x18EC3B`; all of this session's are
  `0x10B807`. Under the stack-overflow reading this is a *depth* signal, not a
  distinct defect: how deep the boot thread gets before the 60 KB runs out depends on
  which frames L2 has already grown.
- (Context, no longer load-bearing) Faulting-site bytes are
  `c7 45 38 07 b8 10 00` (`mov dword[ebp+0x38],0x0010B807`), `c3` (`ret`),
  `50 53 b8 <obj> ff 50 24 e9 …` — the previous routine materialises `0x10B807`
  as a call target and the block ends in `jmp 0xb1d2`, i.e. it sits in a loop. The
  object address differs every boot.
- **It is *not* inside any compiled Java method.** Image offset `0xb807` is below the
  smallest compiled-method stream end (`0x1d0f8`) and inside the image's first blob
  (exactly `0xd000` bytes). It is the image's **entry/clInit prelude**; the builder's
  text listing shows that region as
  `_$$Initial_call_to_clInitCaller: call _$$clInitCaller; mov eax,<org.jnode.boot.Main.vmMain()I>; call [eax+36]`.
- **So F1's symptom text is stale.** It records `Integer.stringSize` / null `sizeTable`;
  in fact the log's last line is `Initialize BootLog` and `Integer` never appears.
- **The earlier retraction is itself retracted.** "A shallow stack, a handful of
  frames" cannot be right in either direction: a handful of frames does not put the
  boot thread at its stack limit, and the trap that fired is the limit check. What
  `_$$initialStack[0] == 0x10D488` actually proves is the size of the region
  (`dd 1102984, dd 1167496`), which is ~60 KB — small, and L2 blows through it.

**Mapping tool — the exact incantation, with two traps.** The facility is
`NativeCodeCompiler.DUMP_METHOD_MAP` (ANCHOR-L2-129) = the JVM system property
`jnode.dump.methodmap`, printing one `[methodmap] cls.method @<ref> stream=0x…` per
compiled method (18,046 lines):

```sh
rm -rf all/build/x86/cdrom-lite/ox && touch core/src/core/org/jnode/vm/x86/compiler/l2/*.java \
  core/src/core/org/jnode/vm/compiler/ir/*.java core/src/core/org/jnode/vm/compiler/ir/quad/*.java \
  core/src/core/org/jnode/vm/classmgr/VmType.java
ANT_OPTS=-Djnode.dump.methodmap=true JAVA_TOOL_OPTIONS=-Djnode.dump.methodmap=true \
  sh build.sh -verbose -Djnode.compiler=L2 "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite
```

- **Trap 1:** `sh build.sh -Dfoo=bar` sets an **Ant** property, not a JVM system property
  (`build.sh` is `java -jar ant-launcher.jar`), so `-Djnode.dump.methodmap=true` there is
  silently ignored. It must go through `ANT_OPTS=` / `JAVA_TOOL_OPTIONS=`.
- **Trap 2:** it needs **`-verbose`**, or the bootimage step's logging is filtered and you
  get zero lines.
- **Known limitation:** the map still **cannot answer "which method contains this EIP"**.
  Its `@` field is a mangled `VmAddress` *reference*, and `stream=` cannot be differenced
  into method ranges because the gaps hold constants pools, class metadata and `$$init_`
  helper bodies. Differencing it attributed a nonsense **119 KB** range to
  `NativeSystemProperties.doSetProperties` — that is how you can tell it is wrong.
  Adding a numeric address does not help: `ObjectResolver.addressOf32` is **unresolved
  at `compileBootstrap` time** (prints `0xffffffff`; a compiler-side patch was tried and
  reverted). The correct home for this map is **emission time in `ObjectEmitter`** — the
  builder's `bootimage.debug` already carries 614,916 **resolved** `$offset` markers, just
  without method names beside them. That is the concrete next piece of work.
- Also useful: `local/kdb_eip.sh <kdb-log> <class> <method>` is the existing per-method
  attribution tool (GH #652) — it regenerates boot-image-identical L2 codegen via
  `BinDump <cls> <mth> <out> resolve` and matches the fault bytes. `BulkMatch.java` is the
  bulk form, with the *previous* crash's patterns baked in.
- Ruled out by measurement: sweeping the crash signature through the real L2 pipeline for
  **6,753 `org.jnode.vm.*` + 8,103 `java.lang.*`/`java.util.*` methods produced zero
  matches**, which independently confirms "not a compiled method".
  (`local/l2boot-tools/EipMatch.java` is that sweep, adapted from `BulkMatch`; gitignored.)

**Next action (F1, restated now that the vector is known):** the question is no longer
"which routine owns `jmp 0xb1d2`" — it is **why L2 exhausts a ~60 KB boot stack**.
Cheapest discriminating measurement first: make the fatal `int_stack_overflow` path print
`stackStart`/`stackEnd`/`ESP` for the overflowing thread (they are already in the `VmThread`
record, offsets `[ebx+28]`/`[ebx+32]` from `_$$Setup_initial_thread`), boot once, and read
whether the overflow is *frame size* (a normal `VmSystem.initialize()` call chain reaching
`0x10D888`, i.e. L2 frames far larger than L1A's) or *stackEnd wrong* (a plausible-looking
`stackEnd` that the check compares against incorrectly). Then either shrink L2 frames
(reserve homes only for versions actually live at a point, instead of one per SSA version)
or grow the boot-thread stack; a `boot --boots N` leg that reaches `Detected 1 processor`
is the pass condition. Also still open: `B2/F2` `allocObject` (result 16), `F4` AOT
coverage, `F5/F6`.

### 5.7 ANCHOR-L2-193 — first-block loop header had no phi (FIXED; this is what made the boot)

**This is the defect that held the boot.** It is fixed, guarded, and its guard is
`Probes#entryWhile_ia` + 5 `CASES` rows. It is written up here rather than in
`OPEN-BUGS.md` (that ledger still needs the row — see §11.7).

**Mechanism, two defects in one shape.** `VmType.getAllInterfaces(HashSet, VmType)` is
`while (C != null) { ... C = C.getSuperClass(); }` with `C` a *parameter*. The loop
condition is bytecode pc 0, so the method's first basic block **is** the loop header,
and its only CFG predecessor is the back edge — the implicit method-entry edge is not a
block. Therefore:

1. `computeDominanceFrontier`'s `predList.size() >= 2` test skipped it, so **no phi was
   placed** for the loop-carried `C`. The header kept reading the incoming *argument*
   version forever. This is still **valid SSA** — the argument dominates the header — so
   no verifier and no SSA lint can ever catch it. Its only symptom is a non-terminating
   loop. Host repro of the emission: header emitted `cmp dword[ebp+16],0x0` (the argument
   slot, never written by the loop) while `C = C.getSuperClass()` stored into `[ebp-44]`,
   which nothing read.
2. **Even with a phi it would still be wrong.** There is no predecessor to carry the
   phi's entry copy, so the copy could only live in the header itself — and the back edge
   re-enters *at* the header, so it re-executes every iteration and clobbers the latch's
   store. That is ANCHOR-L2-124's clobber in its other guise.

**Why `for` loops were spared:** their back edge aims at the *condition*, and
`IRBasicBlockFinder` starts a new block at every branch target, so the loop header
already has two predecessors. Only a loop whose condition sits at pc 0 is affected.

**Fix.** `IRControlFlowGraph.insertEntryPreheader()`, called from the constructor
*before* `computeDominance(bytecode)` — it has to exist before dominance, since it is
what gives the header its second predecessor. It is a no-op unless the first block
actually has predecessors. The synthetic block: PC from `nextSyntheticBlockPC++`,
inserted at `bblocks[0]`, `setIDominator(body)` (stale until `computeDominance` rewrites
it, but non-null so the body's `getVariables`/`getStackOffset` fallbacks are safe), and
**no terminator** — it falls through into the body, which is correct because
`fixupAddresses` numbers blocks in `bblocks` order and codegen walks that same order.
It inherits the method's variable array because `IRGenerator.startMethod` writes it onto
`bblocks[0]`, which is now the preheader.

**Emission proof** (this is the part that makes it obviously right):

```
_qb_0:   push [ebp+12]      <- preheader: a2_2 = a2_1, runs ONCE
         pop  [ebp-28]
_qb_1:   mov  esi,[ebp-28]   <- header body (the back-edge target)
         test esi,esi / je __qb_22
...
_qb_20:  push [ebp-48]      <- latch: a2_2 = a2_3
         pop  [ebp-28]
_qb_21:  jmp  __qb_1        <- back edge SKIPS _qb_0
```

**Guard.** `Probes#entryWhile_ia(int v, int[] steps)`: `while (v > 0) { v -= 2;
steps[0] -= 1; if (steps[0] <= 0) break; } return v;` Host reference
`entryWhile_ia(7,1000) == -1`; the broken L2 build returns `7`. Two deliberate choices:

- **The trip counter is an array *element*, not a local.** An array element needs no
  phi, so it counts down correctly in the broken build too and the loop *terminates* —
  turning a guest hang into a wrong value, which is what a probe row can report.
- **The operands are parameters.** With `int`/`long` constants the optimizer deletes the
  whole `dup`/phi sequence as dead code and the probe silently reports "L2 ok". This bit
  me during the §11.4 work; a constant-fed version of a probe is not a probe.

**javac cannot generate this shape** (measured, §11.5), so the guard for the *dup* family
had to be a hand-built class generator — the `entryWhile_ia` row itself is reachable by
javac because it only needs a plain `iinc` loop.

**Red proof.** Scratch overlay `/tmp/pp193/prefix-classes` (same sources, `insertEntryPreheader()`
gated on `-Djnode.noEntryPreheader=true`): the header then reads the argument slot and
the latch's store is stranded. Green: 5/5 rows match host on the guest.

### 5.8 The three `dup` form gaps in `IRGenerator` — measured, NOT fixed

Full detail and method in §11.4/§11.5. Summary: `IRGenerator` implements **144 of 144**
abstract `visit_*` (no bytecode is wholly missing — `visit_sipush` is a concrete `final`
in `BytecodeVisitor`), so the only `"byte code not yet supported"` throws are **three
partial-form `else` branches**: `:614` in `visit_dup_x2`, `:696` in `visit_dup2_x1`,
`:752` in `visit_dup2_x2`. `visit_dup2` needs no change — both its forms are implemented.

Measured against **JNode's own verifier** (which accepts 18/18 layouts; HotSpot accepts
only 12, refusing to split a category-2 value), **13 of the 18 reachable layouts are
refused**, including `dup2_x1 ili` — which is the layout the guest actually hit at
`00:00:03` during plugin startup. Corpus and red proof:
`tests/l2oracle/dupforms/mkdupforms.py` (8 methods, all verifier-legal on a stock JVM,
all returning the value the dup leaves on top).

**Not landed.** Three attempts, all reverted; see §9. Start from the `dup2_x2` branch-2
question below, because it is probably a *latent miscompile* rather than a missing
feature and it changes what the fix should be.

**`dup2_x2` branch 2 is the most interesting thing in this section.** `javac` emits
`dup2_x2` for `a[i] = <wide>` — e.g. `PrimitiveTest#dupArrAssign(long[] a, int i, long v)
{ return a[i] = v; }` compiles to `aload_0; iload_1; lload_2; dup2_x2; lastore; lreturn`.
At that `dup2_x2` the slot types are `[-1]=LONG [-2]=LONG [-3]=INT [-4]=REFERENCE`
(`LONG = 6` in `org.jnode.vm.JvmType`), which **satisfies** the pre-existing branch-2
condition at `IRGenerator.java:820`. Yet adding an `else` branch that these shapes should
never reach still produced 6 census failures including `dupArrAssign`/`dupArrUse`, with
`ArrayIndexOutOfBoundsException` on a `VariableRefAssignQuad` write and the stack frame
naming the new `else`. Either that attribution is wrong, or **branch 2 is off by one**.
The existing guard is `assertCompiles` — *compilation only* — and `L2PipelineTest` itself
says correctness beyond completion is verified elsewhere. Unresolved; measure it next.



- **Never hand-type a path.** Pin the session once with
  `cd "$(git rev-parse --show-toplevel)"`; a typo in a path/permission field raises a
  permission prompt, which the user explicitly does not want.
- **`pkill` is blocked** in this sandbox. Kill by PID (`pgrep -f` then `kill <pid>`).
  Beware `pgrep -f` self-matching: the pattern sits in your own `bash -c` line.
- **Never run a host build while a live run is in flight.** The host gate
  (`regress.sh … host`) links to a scratch ISO precisely so it cannot clobber the live
  one; the runner also re-verifies the artifact before *every* boot. Both were added
  after the same mistake corrupted a mauve run twice.
- `local/mk-ox-iso.sh` is **gitignored** (per-machine). The *enforcement* is committed
  in `tests/l2oracle/regress.sh`; if you change the builder, keep the committed checks
  in step.
- **Stale guests squatting on `/tmp/jnode.serial2` produce a live leg that hangs
  forever while still reporting "guest up"** (cost ~50 min on 2026-09-28). The mux
  connects to `/tmp/jnode.serial2`, and VirtualBox's `changeuartmode2 server` silently
  fails if anything else already owns that path. Two **QEMU** processes left over from
  an earlier session owned it, their own guest had panicked two days earlier
  (`Real panic: int_die_halt!` in `/tmp/qemu_serial.log`, fd 13 already deleted), and
  the mux happily talked to them. Symptom, exactly: `LIVE oracle: guest up` (the
  `boot-wait.sh` probe answered) followed by every command ending in
  `TIMEOUT link down too long`, `gsh-*.out` growing only with
  `[Ns still running: …]`, and `/tmp/jnode_serial_resp/*.done` reading
  `TIMEOUT link down too long` with a 0-byte `.out` — while a good run writes
  `OK 1` and a multi-KB `.out` in seconds. Diagnose with
  `ss -xlp | grep jnode.serial2` (owner should be VirtualBox, not `qemu-system-x86`)
  and `cat /tmp/jnode_serial_mux.status` (`link=down`). Recover by killing the stale
  PIDs and the mux (`kill <pid>`; `pkill` is blocked) — after that the same run
  completed in **15 s**. Also: another worktree (`jnode_ai_2`) shares this VM and the
  `/tmp` serial paths; its tooling power-offs the same VM, which looks exactly like a
  compiler crash. If guest instability reappears, check
  `ps -eo pid,cmd | grep serial_mux` first, then `ss -xlp | grep jnode.serial`.
## 6. Environment facts that will otherwise cost you hours

- **`git checkout -- <file>` is destructive to uncommitted work** (§11.6b). It silently
  discarded the ANCHOR-L2-193 preheader from `IRControlFlowGraph.java` while stripping an
  unrelated diagnostic, and the loss went unnoticed for hours. Snapshot a file before any
  `checkout --`, and re-read the whole file when removing a probe.
- **The 1-second check that the boot fix is present**:
  `grep -c insertEntryPreheader core/src/core/org/jnode/vm/compiler/ir/IRControlFlowGraph.java`.
  It returns 0 on a tree that wedges ~150 s into the boot with `int_die_halt!` while
  **every host gate stays green** (§11.6a).
- **KDB on this VM: UART1 is a raw file, not a pipe.** `vboxmanage showvminfo JNode` →
  `UART 1 … raw file '/tmp/jnode.kdb'`, `UART 2 … pipe (server) '/tmp/jnode.serial2'`.
  The `jnode-kdb-serial` skill's stated mapping (KDB on UART1 via `/tmp/jnode.kdb`) is
  **inverted for this VM**: KDB is unreachable, and `/tmp/jnode.serial2` is the shell
  agent. To use KDB, power off, re-point UART1 at a pipe, and drain it continuously — an
  undrained pipe blocks the guest on every log byte.
- **The serial log cannot distinguish a good boot from a silent hang.** The shell renders
  on the VGA textscreen, not UART1, so `/tmp/jnode.kdb` just goes quiet after
  `JIFSPlugin: Mounted JIFS on jifs`. Worse, the `regress.sh` `boot` phase's readiness
  break greps for `"bootimage"` — a string that no longer appears anywhere — so that check
  can never fire, the phase always burns the full 300 s, and it reports "no panic" for
  both a successful boot and a wedge. **A human at the console is a better instrument
  than that phase.** Known open item.
- `sh build.sh -D…` sets **Ant** properties only; anything read with
  `Boolean.getBoolean(...)` / `System.getProperty(...)` needs `ANT_OPTS=` or
  `JAVA_TOOL_OPTIONS=`. (This is why the red-proof overlay in §8 sets
  `-Djnode.noEntryPreheader` on the *java* command, not on `build.sh`.)
- This mauve build nests testlets as **directories with dot-named class files**:
  `gnu/testlet/java/io/File/security.class` is the class
  `gnu.testlet.java.io.File.security`, not `…io.File`. Getting this wrong yields a
  mangled FQCN or a silent `FAILED=0`.
- The census derives a class's FQCN from its path under the scanned directory, so a
  narrow repro must stage the class at its exact package path, and must pass the extra
  roots (`local/classlib`, `/tmp/jars/mauve`, `/tmp/opencode/cl`) or the method is
  skipped instead of compiled.
- **`OPEN-BUGS.md` must not drift from the commits.** It read "MEASURED LATENT" for A8
  *after* A8's fix had landed, and still reads OPEN for C1 (fixed in L2-169). A queue that
  lies about what is fixed is precisely how C6 survived a full day of work. Re-check status
  fields when you update it.
- **A guest class in `Probes.java` must be resolvable from the guest classpath.**
  `org.jnode.vm.VmMagic` is a *core* class the guest cannot resolve, so probes using it
  fail the `regress.sh` probe-census gate. That gate refusing the build was the constraint
  working — keep shape-carrying probes guest-resolvable and put core-class probes elsewhere.
- `sh build.sh -D…` sets **Ant** properties only (see §5.6 trap 1); anything read with
  `Boolean.getBoolean(...)` / `System.getProperty(...)` needs `ANT_OPTS=` or
  `JAVA_TOOL_OPTIONS=`.

## 7. The wide census must be chunked

A single sweep over all 11,495 classlib classes reports `OK=59,085 FAILED=22` because a
**cumulative `0x20000` bound** (`ArrayIndexOutOfBoundsException: 131072`) silently
aborts methods and truncates a third of the corpus. Chunked by package prefix the same
tree verifies **more** methods (it was `OK=67,836` while two defects were open; it is
`OK=67,838 FAILED=0` now that NEW-2 is fixed). Note the earlier figure of **93,313** was
double-counting `javax.*` and was wrong; `67,838` is the correct chunked coverage.

```sh
tests/l2oracle/census-wide.sh                 # default disjoint prefix set
tests/l2oracle/census-wide.sh gnu. java.      # or an explicit subset
```

The core corpus (`core/build/classes`, 11,613 methods) is the default gate and needs no
chunking. **Do not "simplify" the wide census back to one sweep.** The chunks now run
concurrently (`JOBS`, default `nproc`; the box has 12 cores) — poll for completion, do not
insert fixed long sleeps.

## 8. Commands

```sh
# host gate: build + anchors + T0/T3/T1 + all-junit + core census
sh tests/l2oracle/regress.sh --label <label> host

# live oracle (rebuilds the L1A oracle ISO, boots, diffs host vs L2-force guest)
sh tests/l2oracle/regress.sh --label <label> oracle

# mauve: default v1; --full for v1..5; digits select levels
sh tests/l2oracle/regress.sh --label <label> --full mauve 2 3 4 5

# L2 bootimage build + cold boot, records the panic signature (opt-in, ~1 min)
sh tests/l2oracle/regress.sh --label <label> boot --boots 3
# signatures accumulate in tests/l2oracle/baselines/boot-signatures.txt
# the bootimage method map (needs ANT_OPTS/JAVA_TOOL_OPTIONS *and* -verbose) — see §5.6

# per-method crash attribution against a KDB log (GH #652)
sh local/kdb_eip.sh <kdb-log> <class> <method> [sigfilter]

# one testlet in isolation (fresh boot per mode)
bash tests/l2oracle/one-testlet.sh <testlet-fqcn> <noforce|force>

# wide census
sh tests/l2oracle/census-wide.sh

# IR / native dumps for one method
sh -c '/home/levente/ext/prg/java/bin/java -Djnode.root=$PWD \
  -cp core/build/testclasses:core/build/classes:local/classlib \
  org.jnode.vm.compiler.ir.L2Dump <fqcn> <method> [extraURLs] --ir|--pre|--ranges'

# --- runtime-JIT=L2 (see §11). The L2/L1A config that BOOTS is the default. ---
# AOT=L2 / JIT=L1A  -> boots to a shell (§5.7)
sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L1A cd-x86-lite
# AOT=L2 / JIT=L2   -> plugin startup, then the D1/D2/D3 exception family (§11.2)
#   add the all-plugins GRUB entry to get "boots far" (needed for §11.2's shape):
sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L2 \
  "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite
# NB: no "Runtime JIT compiler X86-L1A" line in the build output means AOT == JIT.
# Measured 2026-09-29 with the L2-195 policy in place (build log "Compiler union"):
#   L2/L2 -> union "X86-Stub X86-L2", no fallback. Stops 3s into plugin startup:
#     CompileError on org.apache.log4j.AppenderSkeleton#doAppend, caused by
#     X86Level2Compiler.allocate NPE (0041494F), escaping into QueueProcessor --
#     the method stays on its stub and the guest never reaches the shell.
#   -Djnode.compiler=L2 alone -> union "X86-Stub X86-L2 X86-L1A", boots to a shell,
#     and the log carries the rescues: "COMPILE FALLBACK: ... rejected by X86-L2
#     (NullPointerException ...), compiled by X86-L1A" for addHandlers,
#     refreshFinders, TextScreenConsoleManager$1#serviceBound, BaseCalendar#<clinit>.
#   So the L2 allocate defect, not the policy, is what blocks a pure-L2 boot.
# Copy any ISO worth keeping before changing config -- build and isobuild both
# write all/build/cdroms/jnode-x86-lite.iso, and there is no backup (§2).

# hand-built dup/phi corpus for the L2 frontend gaps (§5.8, §11.5)
python3 tests/l2oracle/dupforms/mkdupforms.py /tmp/dupf/classes
# -> DupForms.class, 8 static (IJ) methods, all verifier-legal on a stock JVM.
#    Dump any one with L2Dump; the pre-fix failure is
#    "byte code not yet supported" from visit_dup_x2 / visit_dup2_x1 / visit_dup2_x2.

# red proof for ANCHOR-L2-193 (§5.7) without touching the tree: a scratch overlay
# whose insertEntryPreheader() is gated on a system property, put FIRST on the
# classpath, and run L2Dump with -Djnode.noEntryPreheader=true.

# bisect driver for java.util.Properties under L2 (self-checking, no host compare)
#   modes: noforce | forceprops | forcereader | forceone <fqcn> <method> | disasm <fqcn> <method> <out>
#   staged on the ISO; compile in-guest with:
#   javac -d /jnode/tmp/ox /devices/sg0/ox/PropsForce.java
```

## 9. Approaches that were tried and failed — do not repeat them

- **Rewriting `IRGenerator`'s `dup_x2`/`dup2`/`dup2_x1`/`dup2_x2` wholesale to a generic
  slot-accurate helper** (§5.8). It compiled all 54 probed layouts and broke **37** corpus
  methods: `NativeStrictMath#exp` NPE'd in `removeUnusedVars:377`, and `remPiOver2`
  overran `max_stack` (block stack depths shifted). The in-place branches those methods
  depend on are validated by 67,838 methods; **add an `else`, do not replace**.
- **`dup2` treated as `dup_x2`** (third attempt). On a category-2 top `dup2` duplicates
  **ONE** value, not two — duplicating the wide value *plus its neighbour* shifts the
  whole frame. This cost a full gate cycle.
- **Emitting the dup'd values in the wrong order** (third attempt). `order` entries are
  indices counted from the **top** and are emitted **bottom-up**, so the sequence is
  reversed: `dup2` form 1 is `value2, value1, value2, value1` bottom-up, not
  `value1, value2, ...`. Getting it backwards silently swaps the values. Also: the new
  `stackOffset` must be counted in **slots**, and a wide value contributes two — deriving
  it as "consumed width + a constant" is only right when every value is category 1.
- **A constant-fed probe for a dup/phi shape.** `iconst`/`lconst` operands let the
  optimizer delete the whole sequence as dead code, so the probe reported "L2 ok" for
  layouts L2 in fact rejects. **Probe operands must be parameters.**
- **Probing class-file layouts with `ClassLoader.loadClass`.** It parses; it does not
  verify. Use `Class.forName(name, true, cl)`.
- **Stripping a diagnostic with `git checkout -- <file>`** (§11.6b). It discarded an
  uncommitted fix in a *different* part of that file. Snapshot first, or edit precisely.
- **Trusting `build PASS` / `census FAILED=0` after a revert** (§11.6a). Both regress
  bootimage builds pin `-Djnode.jit.compiler=L1A`, so runtime-L2 defects are invisible
  to the entire host gate.
- **Overwriting the only ISO while changing build config** (§2). The booting
  L2-bootimage was destroyed by an unrelated investigation and had to be rebuilt.
- **Three hand-rolled census lints, all discarded**: the B1 handler-entry-phi lint
  (examined 0 phis — blind), `ARGSPLIT` (fired on 7, of which **5 were known-good
  methods** the oracle passes daily), and `PHINOTATHEAD` (fired on 0, including the two
  methods it was written for, because its premise was a misread of the dump). The
  corpus SSA verifier caught NEW-2 unaided and the value probes caught NEW-1. **Prove a
  lint fires on the known case before keeping it.**
- **Two NEW-1 fixes at the `clone()` level** (§5.1) — wrong layer.
- **Skipping `<clinit>`** in the census to dodge the prepare cascade: rejected on
  measurement, it costs 161 real methods.
- **Reproducing the `0x20000` cluster in narrow slices** (single class → 3 ORB classes →
  whole CORBA tree): all clean, because the trigger is cumulative. Capture a stack from
  a real run instead.
- **"The corpus count is 0, so the codegen is right"** — the mistake that hid **A8, C6 and
  A9** for a day. Cost on A8: the emission genuinely discarded a getfield value and dropped
  a putfield store; the reproducer was two lines of Java. Cost on C6: a legal four-operand
  magic CAS simply could not compile. A9 was "fixed" with a tripwire *throw* before being
  properly fixed. See §4.6.
- **Byte-pattern sweeping the corpus to attribute the boot crash** instead of using the
  method map: 6,753 + 8,103 methods compiled through the real L2 pipeline, zero matches.
  It did prove a *negative* (not a compiled method), but the method map answers it
  directly — use the map.
- **Differencing the `[methodmap]` `stream=` offsets** to bracket an EIP. It attributed a
  **119 KB** range to `NativeSystemProperties.doSetProperties`; the gaps between methods
  hold constants pools, class metadata and `$$init_` bodies, so it is not a linear map.
  If a number looks absurd, the method is wrong, not the number.
- **Byte-counting the bootimage text listing** to convert image offsets to labels. The
  listing's `db` lines come from the *object* dumper, not the code listing, so the
  arithmetic silently misaligns. The listing's labels are reliable; its offsets are not.
- **Adding a numeric address to `[methodmap]` in `NativeCodeCompiler`**: printed
  `0xffffffff`, because `ObjectResolver.addressOf32` is unresolved at `compileBootstrap`
  time. Reverted. The map belongs in `ObjectEmitter` (§5.6).

## 10. Reading the register honestly

`OPEN-BUGS.md` distinguishes **LANDED / CLOSED-NB / OPEN / measured-latent**, and records
why each was reverted. Two things in it are *measurements, not fixes* and must not be
mistaken for either: C2 (latent, 4,352 sites, 0 hazards) and A6 (closed as
not-reproducible after 14 FP probe rows matched the host bit-for-bit). If you add an
entry, keep that distinction — the register's value is that nothing is claimed without a
guard.

---

## 11. AOT=L2 / JIT=L2 — the boot-time exception family (investigated this session)

Everything in §11 is measured. Nothing here is fixed. The AOT=L2 / JIT=L1A
configuration boots (§5.7); the **runtime** L2 compiler does not survive contact with
the boot path, for three independent reasons, and they compound.

### 11.1 The two configurations and how to build them

```bash
# boots to a working shell (the milestone)
sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L1A cd-x86-lite

# the crash family below
sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L2 cd-x86-lite
# ...plus the all-plugins GRUB entry, which is what "boots far" actually needs:
sh build.sh -Djnode.compiler=L2 -Djnode.jit.compiler=L2 \
    "-Dmy-conf.dir=$PWD/local/l2oracle/conf-x86" cd-x86-lite
```

`all/build-x86.xml:106-107` — `jnode.compiler` defaults to `default` and
**`jnode.jit.compiler` defaults to `${jnode.compiler}`**, so `-Djnode.compiler=L2` on its
own gives you AOT=L2 **and** JIT=L2. You must pass both. `local/regress.sh` (lines 92,
134) is **stale** and passes only `-Djnode.compiler=L2`, so it silently builds the
pre-split configuration; `tests/l2oracle/regress.sh` (171, 305) is the current one.

Verify, don't trust the exit code — note the `Runtime JIT` line is **absent** when the
two are the same compiler, which is itself the tell:

```
[bootimage] Compiling using X86-Stub and X86-L2 compilers
[bootimage] Runtime JIT compiler X86-L1A      <- only when AOT != JIT
```

### 11.2 Boot shape under JIT=L2 (reproducible)

Plugins start, filesystems mount (`Mounted ISO9660 on /devices/sg0`, `Mounted JFAT`),
`RAMFSPlugin`, `NetDeviceMonitor`, `SerialConsolePlugin`, `JIFSPlugin` — then the shell's
**first `File.exists()`** dies:

```
CommandShell.main -> File.exists -> UnixFileSystem.getBooleanAttributes
  -> VmMethod.recompileMethod -> LoadCompileService.compile -> X86Level2Compiler.doCompile
Caused by: java.lang.NoClassDefFoundError: java.io.VMIOUtils
  at IRGenerator.visit_invokestatic(IRGenerator.java:1370)
```

No shell prompt, but the text screen is scrollable and shows the whole trace. Three
distinct failures appear, the first two being cascades of the same compile.

### 11.3 D1 / D2 / D3 — the three independent causes

**D1 — L2 codegen: `athrow` of a rethrown caught exception is rejected.**
`GenericX86CodeGenerator.generateCodeFor(ThrowQuad)` handles only `REGISTER` and
`STACK`, but `AddressingMode` has **four** values. A rethrown caught exception is an
`ExceptionArgument`, whose constructor hard-codes `TopStackLocation`, so the operand is
`TOPS` and falls into a **message-less** `else throw new IllegalArgumentException()`.
Every `catch (E e) { throw e; }` that L2 compiles hits this. Captured live after adding
a temporary diagnostic:

```
ThrowQuad operand: class=org.jnode.vm.compiler.ir.ExceptionArgument str=e2_0 mode=TOPS loc=TS
```

Host-reproducible sibling: `throw (RuntimeException) null;` gives
`class=IntConstant str=0 mode=CONSTANT` (`L2Dump Throws t4null`).

**D2 — L2 eagerly resolves constant-pool refs, so a bootimage-absent class breaks
compilation.** `IRGenerator.visit_invokestatic:1370` loads the target class *at compile
time*, so merely **compiling** `UnixFileSystem.getBooleanAttributes0` triggers a load of
`java.io.VMIOUtils` → `ClassNotFoundException`. That class **is** in
`all/lib/classlib.jar` (md5-identical to `local/classlib`, verified) — it is simply not
in the guest **bootimage**. Verified: the jar holds 21,600 classes, `local/classlib`
25,901, and all 21,600 common files are byte-identical.

**D3 — the architectural one, and the reason any gap is fatal.** AOT tolerates a compile
failure; the runtime does not. `LoadCompileService.Request.waitUntilFinished` does
`throw new RuntimeException(errorMessage(), exception)` with **no interpreter
fallback** — so a method L2 cannot compile kills the VM instead of running interpreted.
`errorMessage()` returns the literal `"Error in compilation: "` with an **empty method
name**, which is why the exception alone never named the offending method (only the
separate `ERROR in compilation of <m>` line did). With ~93 gaps reachable, **this is the
highest-leverage fix in the whole area** and it is what makes JIT=L2 survivable while the
gaps are filled incrementally. Recommend doing D3 before any individual gap.

### 11.4 Why every gate is blind to D1/D2 — this is the important part

Chunked classlib census: **67,838 methods, FAILED=0**. Core census: `FAILED=0`. Both
clean, both useless here:

- **The host census loader sees a superset of the guest's classes.** `L2Census` loads
  from `local/classlib` on the app classpath, so any L2 failure that depends on class
  *availability* is invisible **by construction**.
- **The trigger class is generated at runtime.** `$Proxy0` is a
  `java.lang.reflect.Proxy` subclass created during boot — no on-disk class file, so no
  corpus can contain it.
- **Native-replacement bodies** (`java.io.VMFile#exists!`) compile in a re-entrant
  annotation-resolution context the census never reproduces.

⇒ **`FAILED==0` is not evidence that JIT=L2 is sound.** Per §4.6 this is exactly the
"census is not evidence" case, in a new disguise: not "the shape is rare" but "the
instrument cannot see the shape at all".

### 11.5 The `dup` family — what was measured, what the generator is for

`tests/l2oracle/dupforms/mkdupforms.py` emits `DupForms.class`: 8 `static (IJ)` methods
covering every layout the shipped corpus cares about, each returning the value the dup
leaves on top. All 8 verify on a stock JVM and return the right values. Three
measurements shaped it, each of which first went the wrong way:

1. **javac cannot produce the missing forms.** 30 hand-written candidates (mixed
   int/long array and field assignment, compound and nested assignment): javac emitted
   `dup_x2` and `dup2_x2` but **never `dup2_x1`**, and only ever the all-category-1 form —
   the one L2 already implements. All 20 methods that got a dup-family opcode compiled
   clean, as does the whole 67,838-method classlib census. The gaps are unreachable from
   javac output, which is why they survived.
2. **JNode's verifier is more permissive than HotSpot's.** 18 layouts probed one class
   each. **HotSpot rejects 6** (`dup_x2 il/ll`, `dup2_x1 lli/ili/lii/lll`, `dup2_x2
   lli/ili/lil`) because it will not split a category-2 value; **JNode accepted all 18**,
   zero rejects. So **13 of 18 JNode-reachable layouts are refused by `IRGenerator`**,
   including `dup2_x1 ili` — the guest's actual failure. Consequence: the guest's layout
   was probably `iil`/`ili`, **not** the JVMS "form 3" the code comments suggest.
3. **Verification needs linking.** `ClassLoader.loadClass` only *parses*; it does not
   verify. An early probe of mine reported all 18 layouts "ACCEPTED" because of exactly
   this. Use `Class.forName(name, true, cl)` or invoke reflectively.

Generator gotchas, all of which produced a wrong measurement first: `invokespecial` is
**3 bytes** (opcode + u2 index — the JVMS "countbyte 0" of the old verifier is not
emitted; verified against javac's `2a b7 00 01 b1`); `ACC_SUPER` is a class flag and is
illegal on `<init>`; test methods must be `public` for `getMethod`; and **`max_stack` is
only required to be an upper bound** — declaring 16 when the post-dup peak is 19 produced
an `ArrayIndexOutOfBoundsException` inside JNode that looked like a backend bug.

### 11.6 Two process lessons that cost the most time this session

**(a) The host gate is blind to every runtime-JIT-L2 defect.** Both `regress` bootimage
builds pass `-Djnode.jit.compiler=L1A` (`regress.sh:171`, `:305`). A defect that only
manifests when the *runtime* JIT is L2 is therefore **inert under the host gate** — the
preheader of §5.7 is exactly such a defect. Practical rule: for anything in this area,
`isobuild` + `oracle` is mandatory and the value probe is the only instrument that sees
it. I skipped `oracle` after a revert, saw `build PASS / FAILED=0`, and reported the tree
restored — while the fix I had just deleted was the thing making the boot work.

**(b) `git checkout -- <file>` is a nuke, not an edit.** Stripping a diagnostic from
`IRControlFlowGraph.java` with `git checkout --` **discarded the uncommitted
ANCHOR-L2-193 preheader** in that file. I checked `IRGenerator.java` (correctly reported
identical to HEAD) and did not check `IRControlFlowGraph.java`, so I stated the tree was
restored. The boot milestone was lost and had to be reconstructed from the conversation.
The guard for that fix sat in `Probes.java`/`OracleDriver.java` the whole time, unused.

### 11.7 Open, and what to do next

1. **D3 first** (§11.3) — make a runtime compile failure fall back to the interpreter
   instead of throwing. Converts ~93 gaps from "VM dies" to "one method runs interpreted".
2. **`dup2_x2` branch 2** (§5.8) — unresolved, and the evidence points at a possible
   *latent miscompile* on javac's `a[i] = <wide>` path rather than a missing feature.
   Worth more than the 13 unimplemented layouts.
3. **The 13 layouts** (§5.8) — implement via `emitDup`, but **additively**: keep the
   existing branches (they are what 67,838 methods validate) and put the new layouts
   behind an `else`. §9 lists the three wrong attempts.
4. **`OPEN-BUGS.md` needs rows** for ANCHOR-L2-193 (fixed, guarded) and the `dup` family
   (open). I was asked to touch only this file, so they are not written yet.
5. **Unresolved anomaly:** guest Java stack traces report line numbers that do not match
   the shipped source — `GenericX86CodeGenerator:6558` where the source throws at 6570,
   `X86Level2Compiler:149` where `q.generateCode(cg)` is at 182, and `IRGenerator:6533` in
   a **1701-line** file. Stale artifact ruled out (one class copy on disk, class newer
   than source, ISO newer than class, classlib md5-identical), and a rebuild reproduced
   the identical failure, so the *shapes* hold — but the mismatch is unexplained and it
   undermines any line-number-based reading of a guest trace. Treat guest line numbers
   as unreliable; trust the frames and the message text.

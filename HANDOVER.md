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

## 2. Current state

- Branch `L2-SpaceBunny`, **tree clean**, tracking `origin/L2-SpaceBunny`.
- **The branch is currently in sync with the remote.** I was instructed never to push
  without being asked and did not; the push happened outside this session. Verify with
  `git ls-remote origin L2-SpaceBunny` before assuming anything about what is published.
- Host gates green at the last run: `build`, `anchors`, **T0 18/18, T3 19/19, T1 38/38,
  all-junit 253/253**, census `OK=11607 SKIP=1511 HANDLERS=524 FAILED=0`, all five
  lints at 0.
- Census, wide corpus (must be chunked — see §7): **67,836 methods verified, FAILED=2**.

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

### 5.2 NEW-2 (P1) — `throw` in jsr/finally reads a non-dominated exception variable

Two methods of 59,122: `gnu.testlet.java.nio.channels.FileChannel.lock#test` and
`gnu.testlet.java.io.File.security#test`. The throw block contains *only* the throw,
and the phi that defines the variable is the first quad of the **next** block, with
`UndefinedVariable` sources on the entering paths. `dominates()` walks the idom chain,
so this is not an address-ordering artefact. Open question: are the undefined-source
paths reachable? Narrow repros are in `CENSUS-FAILURES.md` (both `FAILED=1`, seconds).

**Next action:** either a reachability argument, or a hand-built jsr/finally probe.
Note `java.awt.geom.AffineTransform#setToIdentity` shows the IR generator has no
`visit_dup2_x1` — the jsr probe machinery may already exist under `tests/l2oracle/jsr/`.

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

### 5.5 Unmeasured queue — measure before fixing

`P11–P19, H6, M2, M5` plus `C4, C5, C6, D1, D2, A8`. **None has been measured.** The
C2 precedent: instrumenting it showed 4,352 coalescing sites with all three hazards at
**0** — i.e. latent — and the fix was correctly reverted rather than shipped. Measure
each the same way before touching it.

### 5.6 Bootimage — LAST

`F1` `Integer.stringSize` null `sizeTable` read — an NPE *inside the array
bounds check*, after a class-init barrier that was emitted correctly
(`$$cbtest`/`$$cbfailed` are `checkBounds`, not a barrier; mechanism corrected
2026-09-27 — see `OPEN-BUGS.md` F1 for the evidence and for the AOT-reachable
probe point), `B2/F2` `allocObject` (result 16), `F4` AOT coverage, `F5/F6`.
Do not start until §5.1–§5.5 are done.

## 6. Environment facts that will otherwise cost you hours

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
- Another worktree (`jnode_ai_2`) shares this VM and the `/tmp` serial paths. Its
  `serial_mux.py` was running for ~30h and its tooling power-offs the same VM, which
  looked exactly like a compiler crash. The user stopped it; if guest instability
  reappears, check `ps -eo pid,cmd | grep serial_mux` first.
- This mauve build nests testlets as **directories with dot-named class files**:
  `gnu/testlet/java/io/File/security.class` is the class
  `gnu.testlet.java.io.File.security`, not `…io.File`. Getting this wrong yields a
  mangled FQCN or a silent `FAILED=0`.
- The census derives a class's FQCN from its path under the scanned directory, so a
  narrow repro must stage the class at its exact package path, and must pass the extra
  roots (`local/classlib`, `/tmp/jars/mauve`, `/tmp/opencode/cl`) or the method is
  skipped instead of compiled.

## 7. The wide census must be chunked

A single sweep over all 11,495 classlib classes reports `OK=59,085 FAILED=22` because a
**cumulative `0x20000` bound** (`ArrayIndexOutOfBoundsException: 131072`) silently
aborts methods and truncates a third of the corpus. Chunked by package prefix the same
tree verifies `OK=67,836` (+15%) with `FAILED=2`.

```sh
tests/l2oracle/census-wide.sh                 # default disjoint prefix set
tests/l2oracle/census-wide.sh gnu. java.      # or an explicit subset
```

The core corpus (`core/build/classes`, 11,607 methods) is the default gate and needs no
chunking. **Do not "simplify" the wide census back to one sweep.**

## 8. Commands

```sh
# host gate: build + anchors + T0/T3/T1 + all-junit + core census
sh tests/l2oracle/regress.sh --label <label> host

# live oracle (rebuilds the L1A oracle ISO, boots, diffs host vs L2-force guest)
sh tests/l2oracle/regress.sh --label <label> oracle

# mauve: default v1; --full for v1..5; digits select levels
sh tests/l2oracle/regress.sh --label <label> --full mauve 2 3 4 5

# one testlet in isolation (fresh boot per mode)
bash tests/l2oracle/one-testlet.sh <testlet-fqcn> <noforce|force>

# wide census
sh tests/l2oracle/census-wide.sh

# IR / native dumps for one method
sh -c '/home/levente/ext/prg/java/bin/java -Djnode.root=$PWD \
  -cp core/build/testclasses:core/build/classes:local/classlib \
  org.jnode.vm.compiler.ir.L2Dump <fqcn> <method> [extraURLs] --ir|--pre|--ranges'

# bisect driver for java.util.Properties under L2 (self-checking, no host compare)
#   modes: noforce | forceprops | forcereader | forceone <fqcn> <method> | disasm <fqcn> <method> <out>
#   staged on the ISO; compile in-guest with:
#   javac -d /jnode/tmp/ox /devices/sg0/ox/PropsForce.java
```

## 9. Approaches that were tried and failed — do not repeat them

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

## 10. Reading the register honestly

`OPEN-BUGS.md` distinguishes **LANDED / CLOSED-NB / OPEN / measured-latent**, and records
why each was reverted. Two things in it are *measurements, not fixes* and must not be
mistaken for either: C2 (latent, 4,352 sites, 0 hazards) and A6 (closed as
not-reproducible after 14 FP probe rows matched the host bit-for-bit). If you add an
entry, keep that distinction — the register's value is that nothing is claimed without a
guard.

# Open bugs and coverage gaps — consolidated register

Start here. This is the deduplicated union of every review's findings; the
per-item detail stays in the source documents named below.

Single deduplicated list across every review: the deep review
(`../../local/docs/L2-DEEP-REVIEW.md` — H1-H7, M1-M5, P6-P19 status, and its
section-4 structural observations), the deep review **report**
(`../../local/docs/L2-DEEP-REVIEW-REPORT.md`, Parts 0-10), the sibling-bug
queue in `SIBLING-BUGS-PLAN.md` (P0-P19), and the 2026-09-26/27 sessions.
Items that exist in a review but not in this register are the defect; this
file is the checklist to keep that true. Status
vocabulary: **LANDED** (fixed + guarded), **OPEN** (believed real, no fix),
**CODE-READ** (claimed from reading, no repro), **GUARD GAP** (no test/lint
covers the shape), **CLOSED-NB** (investigated, not a bug).

Guard rule: every fix must ship with a guard that fails when the fix is
reverted (see `AGENTS.md`). The census now runs `SSAVerifier` pre- and
post-deSSA on every method, so structural SSA regressions are corpus-wide.

## A. Codegen / emitter (report Part 1)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| A1 | P0 | `_qb_0` never bound -> every branch to bci 0 ships an unpatched rel32 (wild jump) | **LANDED** L2-161 | unbound-label census over all classes, 0 expected. **GUARD GAP**: no test emits a branch to address 0 (report invariant 2) |
| A2 | P0 | `flushCopy` places a deSSA edge copy before its own source's definition | **LANDED** L2-162 | corpus SSA verifier (was: T1 synthetic only) |
| A3 | P1 | `arraylength` clobbers EBX, an allocated register, with no push/pop | **LANDED** L2-163 | census lint `ARRAYLENGTHREG` (105 corpus sites -> 0) + T3 test, red on the pre-fix overlay |
| A4 | P1 | shift arms destroy the destination when it is ECX (12 arms) | **LANDED** L2-165 | T3 test for all three shift ops with dst=ECX, red on the overlay; also closes invariant 5 for this shape |
| A5 | P1 | `isCallLike` omits quads that really emit a `CALL` (`ConstantClassAssignQuad`, barriers) -> live ranges not force-spilled | **LANDED** L2-164 | census lint `CALLNOTCALLLIKE`, 243 sites in 161 methods -> 0; both predicate copies extended |
| A6 | P2 | `FREM` with a constant left operand emits `FSUB` on an empty x87 stack | **CLOSED-NB** (2026-09-27): all six FREM arms use FPREM with the operands loaded in the right order, and the 14 new FP oracle rows (L2-167, incl. the constant-LEFT shape the report names) match the host EXACTLY under L2 force | closed by measurement, not by reading; the probes are the guard and also close invariant 6 on the oracle side |
| A7 | P2 | `writeParameters` signature gate never fires for `invokestatic` (the `- 1` assumes a receiver) | **LANDED** L2-166 | T1 test with a stale-typed static argument, red on the overlay; `WIDTHMISMATCH` extended to static calls (invariant 7) |
| A8 | P3 | constant-null `getfield` never writes lhs, `putfield` emits a load (latent under the null-trap model) | **OPEN** | none |

## B. IR construction (report Part 2)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| NEW-3 | P1 | **Two new candidate L2 codegen defects, surfaced only by the chunked wide census** (they were invisible while the cumulative 0x20000 bound truncated the corpus). (a) `java.awt.font.TextMeasurer#<clinit>` fails inside codegen: captured stack `GenericX86CodeGenerator.generateCodeFor(6889)` <- `StaticRefStoreQuad.generateCode(69)`. A static-field store in a static initialiser is exactly the `$$ic` bootstrap barrier family (F1), so this may be the same defect reachable from ordinary code rather than only from the bootimage. (b) `java.util.concurrent.ConcurrentSkipListMap#insertIndex` fails in the L2 pipeline. Both need triage; neither is value-confirmed yet | census evidence only, 1 chunk each, no guard. Highest-value next measurement: dump the IR/codegen for each and see whether it reduces to a known shape |
| NEW-2 | P1 | **A `throw` in a jsr/finally shape reads an exception variable whose only definitions live in a block that does not dominate it**, and the phis that would supply it have UNDEFINED sources on the entering paths. Found by the classlib census: 2 methods of 59,122. `gnu.testlet.java.nio.channels.FileChannel.lock#test` -> `SSA-POST: read of l12_3 at 128: throw l12_3 in B539 is not written on every path; defs: [129...]`; `gnu.testlet.java.io.File.security#test` -> `read of l25_3 at 690: throw l25_3 in B1379 ... defs: [717: l25_3 = l25_2]`. Structure (`--ir`): `B1379` contains ONLY `691: throw l25_3`, and the next block starts `1382: l25_3 = phi(u25_0,u25_0,l25_2)` -- i.e. the phi DOES lead its block; the throw block is entered from paths the phi marks undefined. `dominates()` walks the idom chain, so the report is not an address-ordering artifact, and the odd `B-2147483645` block names are cosmetic (a block name is `"B" + startPC`). Open question: are the undefined-source paths reachable? de-SSA cannot tell, and no structural lint can either | verifier evidence only (over 59,122 methods); NOT value-confirmed -- neither testlet class is in the guest plugin set (`ClassNotFoundException: gnu.testlet.java.io.File`), so the value route is blocked. A `PHINOTATHEAD` lint was written for this and reverted: it does not fire on either method (0 over both corpora), because the phi is correctly placed. Needs a reachability argument or a hand-built jsr/finally probe |
| NEW-1a | P0 | **Fix attempt for NEW-1, reverted.** Two designs, both NPE in the bootimage compile, so the change needs a real design, not a one-liner. (1) `MethodArgument.clone()` returns an unlocated clone: every version of an argument slot then has no home, and dead defs of that slot reach codegen with a null Location -> NPE in `GenericX86CodeGenerator.generateCodeFor`. (2) `clone()` returns a plain `LocalVariable` so the allocator treats it as an ordinary local: `removeUnusedVars:144` NPEs on `getKey().getAssignQuad().isDeadCode()` because a version with no assign quad is not a `MethodArgument` and so no longer short-circuits. Hardening that to `aq == null -> keep` builds, but then the corpus collapses (`OK` 11607 -> 1556, `MAGIC=81`) with `SSA-PRE: use without def: ... reads l0_1`: the def quad attaches to the ORIGINAL argument object (`visit_iinc` uses `variables[index]` as the BinaryQuad LHS), so the clone is a def-less variable that DCE now keeps and the verifier then flags. Both attempts reverted. Conclusion for the next attempt: the fix belongs in SSA construction, not in `clone()` -- the version that receives the def must be the version that is read, and every emitted version needs a home while the incoming argument keeps its frame slot (handler entries and deopt read the frame) | both attempts reverted with full evidence; `clone()`-level fixes are provably the wrong layer |
| NEW-1 | P0 | **`a[i++]` reads the incremented index.** `AcuniaPropertiesTest` (mauve v2) passes unforced, throws under force with `StringIndexOutOfBoundsException: index 847332096` (0x32814300 = a JNode heap ADDRESS). Bisected to the single method `java.util.Properties.loadConvert` (forcing is sticky per boot, so each bisect step needs a fresh boot): every parsed key/value loses its FIRST character -- `key1` -> NUL+"ey1", `value1` -> NUL+"alue1". Mechanism: `a[i++]` is `aload a; iload i; iinc i,1; iaload`, index pushed BEFORE the iinc. A live local's home is its INDEX (`getEbpOffset`) and SSA clones copy their original's `Location`, so every version cloned from an ARGUMENT inherits the argument's one fixed frame home (arguments are never register-allocated). When the older version is still live at the redefinition, the definition clobbers it: L2 emits `qb_13: add dword[ebp-20],1` then `qb_14: mov ecx,dword[ebp-20]`. The SSA IR is CORRECT at both `--pre` and `--ir`, which is why no structural gate saw it. de-SSA lowers phis only and `setSpilledVariables` de-aliases only SPILLED variables, so nothing splits an argument slot | **GUARDED (value-level, red proof)**: `postIncrRead_aii` host `I:32`=50 vs guest `I:46`=70; `postIncrStore_aiii` host `I:2c4`=708 vs guest `I:7`=7. Plus `PropsForce` self-check (noforce OK / forced broken) and the live mauve v2 testlet. **NOT FIXED** -- the fix is to stop a clone inheriting an argument's fixed home while the old version is live (needs a de-SSA home split, and the last version must keep the JVM slot for deopt) |
| B1 | P1 | handler-entry phi never written on the exceptional dispatch for **non-self-edge** handlers (ordinary try/catch) | **OPEN, and still uninstrumented** (L2-159 covers the self-edge shape) | verifier carves it out (invariant 3). A structural census lint was attempted and REMOVED: it could not identify handler blocks reliably (the flag is not set on the blocks that hold the phis, and post-fixup startPCs do not match the exception table), so it examined 0 phis -- a blind instrument is worse than none. The way in is a value-level probe: an ordinary try/catch whose handler reads a try-modified local, run through the oracle. The existing try/catch probes pass, which is weak evidence the common shapes are fine. |
| B2 | P1 | synthetic critical-edge blocks appended at layout end -> inverted live intervals -> register aliasing at the join (the `allocObject` #PF CR2=8) | **OPEN** (naive fix tried and reverted) | `interferesWith` never checked against execution order (invariant 4) |
| B3 | P2/P3 | smaller construction items (report 2.4) | **OPEN**, unitemised | none |

## C. deSSA / live ranges / regalloc (report Part 3)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| C1 | P2 | `LiveRange.compareTo` is not a valid total order | **OPEN** | none |
| C2 | P2 | `removeDefUseChains` coalescing unguarded (= plan P10) | **OPEN** | none |
| C3 | P1 | `handlerEntryTops` snapshot skipped when the exception slot has no SSA stack -> self-referential phi copy (found in my own L2-159) | **LANDED** L2-168 | corpus SSA verifier over every compilable method (a missed snapshot surfaces as a post-deSSA violation) |
| C4 | P2 | deSSA floor ignores phi source tags: when no usable edge is found, the floor copy lands on the def block of `sources.get(0)` regardless of the edge it arrived on (earlier review S4) | **OPEN**, partially mitigated by L2-159's handler-tag routing, floor itself unchanged | corpus SSA verifier catches the value shape, not the edge choice |
| C5 | P2 | handler-entry idom heuristic: a handler block with no computed idom inherits the idom of the block at its range START, not the closest predecessor (earlier review S4; `IRControlFlowGraph.doComputeDominance`) | **OPEN** | none |
| C6 | P2 | M4: CAS with a spilled offset operand fails to compile (`loadEffectiveAddress` refuses a STACK offset into EDX, the CAS arm always passes EDX) -- loud, not silent, but it hides in the same census bucket as real bugs | **OPEN** (disabled-capability noise) | none; census FAILED would list it once the class is reachable |
| C7 | note | the never-popped `ExceptionArgument` on the SSA stack is deliberate (ANCHOR-L2-128); L2-159 additionally re-arms the exception SLOT in the IR generator | by design | corpus SSA verifier |

## D. IR generator / quad semantics (report Part 4)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| D1 | P2 | jsr/ret entry depth depends on translation order | **OPEN** | post-deSSA verification of the jsr probe deliberately skipped (invariant 11) |
| D2 | P3 | latent quad-semantics items (report 4.3) | **OPEN**, unitemised | none |

## E. Interop / structural (report Part 5)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| E1 | - | L2 images are **larger** than L1A everywhere: register pool is ECX/EBX/ESI only, plus a mandatory ECX frame. Compactness goal unmet | **OPEN** (structural) | none (perf/size, not correctness) |
| E2 | - | 64-bit paths stubbed (`writeInitializeClass` throws, interface dispatch commented out) | **OPEN** by design; blocks `cd-x86_64-lite` | none |
| E3 | - | any reasoning about EBX across a call must use the `forcedSpills` model, not callee-saved (L1A pushes are commented out) | **OPEN** as a documentation risk; **GUARD GAP** (invariant 8) | none |

## F. Boot blockers

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| F1 | P0 | `$$ic` class-init barrier dereferences the statics holder before testing it -> null holder faults instead of reaching the init call (the `Integer.stringSize` `mov eax,[eax]`/EAX=0 panic) | **OPEN**, next target | none |
| F2 | P1 | see B2 (`allocObject`) | **OPEN** | none |
| F3 | - | boot signature is layout/timing dependent (0x18E11B -> 0x18E243 -> 0x18E5E3 -> 0x18EC43 -> 0x18EC3B); the same image is not byte-reproducible | recorded | `tests/l2oracle/baselines/boot-signatures.txt` |
| F4 | - | AOT-path coverage: oracle, mauve and census all describe a VM that **booted with L1A** and force-compiles at runtime; nothing observes AOT-emitted L2 code (invariant 14) | **OPEN** (structural gap) | none |
| F5 | - | clinit ordering at AOT boot: `doInitialize` never runs on the early AOT path (invariant 15) | **OPEN** | runtime-forced clinit only |
| F6 | - | builder-side hypothesis, never confirmed nor refuted: `AbstractBootImageBuilder.copyStaticFields` skips every `java.*` class, so java.* static VALUES are never seeded into the image while their AOT `<clinit>`s run on whichever boot thread gets there first; each thread's statics table is a separate image object | **OPEN as a hypothesis** -- the two competing theories (AOT/runtime statics-index mismatch, per-thread isolated statics) are dead by measurement, and the sentinel-store experiments show the store lands, which weakens this one too | none; needs an AOT-path print that the early boot actually executes (the first attempt produced no output because it hooked a path AOT boot bypasses) |

## G. Plan-doc queue not in the report

| # | Defect | Status |
|---|---|---|
| G1 | P11 fall-through / ret-resume edges never explicit | **OPEN** |
| G2 | P12 `IRGenerator.fixType` shared-slot mutation | **OPEN** |
| G3 | P13 `typePhiResults` single-wide-source upgrade | **OPEN** |
| G4 | P14 `pruneDeadPhis` undefined-source drop | **OPEN** |
| G5 | P15 loop classification still mixes flows | **OPEN** |
| G6 | P16 switch `retarget` stale `defaultAddress` (verification only) | **OPEN** |
| G7 | P17 `RetQuad` rename carve-out (doc + test, no code) | **OPEN** |
| G8 | P18 inter-block over-pop (instrument first) | **OPEN** |
| G9 | P19 shared throwing predicate + leftovers | **OPEN** |
| G10 | H6 dup2 form-1 transposition | **OPEN**, needs hand-built bytecode infra |
| G11 | M2 `forcedSpills` omissions incl. class-init | **OPEN**, boot suspect |
| G12 | M5: comparator, INT stamps, keep-list get/setfield, PhiAssign hashCode | **OPEN**, low |
| G13 | P7/P8 (phi primaries bypass usability; foldConstants2 shared-lhs re-type) | **CLOSED-NB** unless a repro appears |
| G14 | P0-1 hardcoded `[rewrite]` debug print, P0-2 ungated `[ssatag] DISAGREE` stderr | **LANDED** (both merge blockers closed early in the queue) | gate: the ssatag stderr census line |
| G15 | P1-P6, P9 (`propagate` kills with live uses, DF-only kill, `Variable.equals` without `hashCode`, terminator guard/flush order, throwing-twins keep-list, `isCallLike` missing LDIV/LREM, always-executed defs) | **LANDED** (P0-P6 + P9 were the base of this branch) | regression suite + census |

## H. Coverage gaps with no bug attached (report Part 7)

Invariant list, with what changed since: 1 label binding **now covered**
(A1's census); 2 branch-to-address-0 **still open**; 3 handler-entry copy
value correctness **open**; 4 register/address correspondence under
synthetic layout **open**; 5 shift destination ECX value **open**; 6 any FP
arithmetic emission test **open**; 7 static-call push widths **open**
(A7); 8 EBX/ESI across calls **open** (E3); 9 `disp1 == disp2` FP aliasing
**open**; 10 `#DE` semantics **open**; 11 post-deSSA jsr **open** (D1); 12
wide phi homes across critical-edge copies **open**; 13 `ExceptionArgument`
as a phi source at non-handler joins **open**; 14 AOT path **open** (F4);
15 AOT clinit ordering **open** (F5).

Additionally, not in the report: the census covers `core/build/classes`
(1,402 classes / 11,603 methods) but **not** `local/classlib` (25,901
classes / 63,720 methods), which is the bulk of the bootimage. The
2026-09-26 verifier census covered both (65,420 methods, ~3 min for the
classlib); its tools were throw-away and are not in the tree, but
`L2Census` now has the capability, so the classlib pass is a script change.

## I. Closed tonight (do not re-chase)

- The 169 census FAILED methods: harness loader never saw the repo's own
  jars. Fixed in the harness; FAILED 169 -> 0 with +206 real methods.
- classLiteral / `Class.getModifiers()` = 1: JNode classlib semantics, not
  L2 (guest L1A and L2-forced values identical).
- mauve `Class.*` force-only diffs: cross-testlet state; isolated runs are
  identical (noforce 14/1, force `force=9` 14/1).
- M3 float-const CCE / FREM arm: not reproducible on this branch.
- NEW-1 root cause (L2-171): argument-slot versions share one frame home;
  `a[i++]` reads the incremented index. Guard = value probes (red proof
  50 vs 70). Two structural lints were tried and BOTH removed: INDEXALIAS
  reported 0 (after de-SSA there is one Variable object per index, so no
  distinct pair exists), and ARGSPLIT reported 7 -- the 2 reproducers plus
  5 known-good methods the oracle passes daily (tryFinally, syncThrow,
  loopLongTryFinally, sync_add, finallyThrowsLong), because its
  address-interval test ignores block structure. Do not re-add either
  without fixing the false positives first.
- Wide census must be CHUNKED (`tests/l2oracle/census-wide.sh`): one sweep
  over 11,495 classes reports OK=59,085 / FAILED=22 because a cumulative
  0x20000 bound (ArrayIndexOutOfBoundsException(131072)) aborts methods and
  truncates the corpus. Chunked by package prefix: OK=93,313 (+58%),
  FAILED=6. Do not "simplify" this back to one sweep.
- Non-L2 census failures are now separated and each carries its evidence:
  SKIP_MISSING_DEP=77 (CORBA absent from this tree, optional JNode classes),
  SKIP_ENV (native method not resolvable in the harness, IR scope gaps such
  as visit_dup2_x1, recursive class prepare, classlib version mismatch),
  both listed per method. Of the 6 that remain, 2 are NEW-2 and 3 are NEW-3;
  only those are candidate compiler defects.
- Classlib census baseline (2026-09-27, the 3.5x-wider corpus):
  `OK=59122 SKIP=9186 SKIP_CLASSES=2147 SKIP_METHODS=7039 HANDLERS=4976
  FAIL_64=0 FAILED=108`. All five existing lints 0. 106 failures are
  `NoClassDefFoundError` (CORBA, java.management, nanoxml are not on the
  census classpath -- the same lesson as the retired 169) and 2 are the
  real SSA-POST violations in NEW-2. The corpus was previously only
  11,607 methods, so this is the first look at 5x the surface.
- Three structural lints were written and discarded today (B1 handler-phi,
  ARGSPLIT, PHINOTATHEAD): each was 0 or noisy on 70,000+ methods,
  including on the very defect it targeted. The corpus SSA verifier caught
  NEW-2 by itself, and the value probes caught NEW-1. Treat hand-rolled
  lints as hypotheses, not guards -- prove they fire on the known case
  before keeping them.
- Tooling gap found while doing the above: `regress.sh`'s ORACLE DIFF does
  not report rows present in the host reference but MISSING from the guest.
  Three loop-probe rows vanished from the guest and the diff still read
  clean; only the value comparison caught it. A missing row is a failure.
- A6 FREM-constant-left "FSUB on an empty stack": all six arms use FPREM
  in the right order, and 14 FP oracle rows (add/sub/mul/div/rem, constant
  left and right) match the host bit-for-bit under L2 force. The claim was
  from reading only; the probes now hold the ground.

| NEW-1a | P0 | **Fix attempt for NEW-1, reverted.** Two designs, both NPE in the bootimage compile, so the change needs a real design, not a one-liner. (1) `MethodArgument.clone()` returns an unlocated clone: every version of an argument slot then has no home, and dead defs of that slot reach codegen with a null Location -> NPE in `GenericX86CodeGenerator.generateCodeFor`. (2) `clone()` returns a plain `LocalVariable` so the allocator treats it as an ordinary local: `removeUnusedVars:144` NPEs on `getKey().getAssignQuad().isDeadCode()` because a version with no assign quad is not a `MethodArgument` and so no longer short-circuits. Hardening that to `aq == null -> keep` builds, but then the corpus collapses (`OK` 11607 -> 1556, `MAGIC=81`) with `SSA-PRE: use without def: ... reads l0_1`: the def quad attaches to the ORIGINAL argument object (`visit_iinc` uses `variables[index]` as the BinaryQuad LHS), so the clone is a def-less variable that DCE now keeps and the verifier then flags. Both attempts reverted. Conclusion for the next attempt: the fix belongs in SSA construction, not in `clone()` -- the version that receives the def must be the version that is read, and every emitted version needs a home while the incoming argument keeps its frame slot (handler entries and deopt read the frame) | both attempts reverted with full evidence; `clone()`-level fixes are provably the wrong layer |
| NEW-1 | P0 | **`a[i++]` reads the incremented index.** `AcuniaPropertiesTest` (mauve v2) passes unforced, throws under force with `StringIndexOutOfBoundsException: index 847332096` (0x32814300 = a JNode heap ADDRESS). Bisected to the single method `java.util.Properties.loadConvert` (forcing is sticky per boot, so each bisect step needs a fresh boot): every parsed key/value loses its FIRST character -- `key1` -> NUL+"ey1", `value1` -> NUL+"alue1". Mechanism: `a[i++]` is `aload a; iload i; iinc i,1; iaload`, index pushed BEFORE the iinc. A live local's home is its INDEX (`getEbpOffset`) and SSA clones copy their original's `Location`, so every version cloned from an ARGUMENT inherits the argument's one fixed frame home (arguments are never register-allocated). When the older version is still live at the redefinition, the definition clobbers it: L2 emits `qb_13: add dword[ebp-20],1` then `qb_14: mov ecx,dword[ebp-20]`. The SSA IR is CORRECT at both `--pre` and `--ir`, which is why no structural gate saw it. de-SSA lowers phis only and `setSpilledVariables` de-aliases only SPILLED variables, so nothing splits an argument slot | **GUARDED (value-level, red proof)**: `postIncrRead_aii` host `I:32`=50 vs guest `I:46`=70; `postIncrStore_aiii` host `I:2c4`=708 vs guest `I:7`=7. Plus `PropsForce` self-check (noforce OK / forced broken) and the live mauve v2 testlet. **NOT FIXED** -- the fix is to stop a clone inheriting an argument's fixed home while the old version is live (needs a de-SSA home split, and the last version must keep the JVM slot for deopt) |
| B1 | P1 | handler-entry phi never written on the exceptional dispatch for **non-self-edge** handlers (ordinary try/catch) | **OPEN, and still uninstrumented** (L2-159 covers the self-edge shape) | verifier carves it out (invariant 3). A structural census lint was attempted and REMOVED: it could not identify handler blocks reliably (the flag is not set on the blocks that hold the phis, and post-fixup startPCs do not match the exception table), so it examined 0 phis -- a blind instrument is worse than none. The way in is a value-level probe: an ordinary try/catch whose handler reads a try-modified local, run through the oracle. The existing try/catch probes pass, which is weak evidence the common shapes are fine. |
| B2 | P1 | synthetic critical-edge blocks appended at layout end -> inverted live intervals -> register aliasing at the join (the `allocObject` #PF CR2=8) | **OPEN** (naive fix tried and reverted) | `interferesWith` never checked against execution order (invariant 4) |
| B3 | P2/P3 | smaller construction items (report 2.4) | **OPEN**, unitemised | none |

## C. deSSA / live ranges / regalloc (report Part 3)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| C1 | P2 | `LiveRange.compareTo` is not a valid total order | **OPEN** | none |
| C2 | P2 | `removeDefUseChains` coalescing unguarded (= plan P10) | **OPEN** | none |
| C3 | P1 | `handlerEntryTops` snapshot skipped when the exception slot has no SSA stack -> self-referential phi copy (found in my own L2-159) | **LANDED** L2-168 | corpus SSA verifier over every compilable method (a missed snapshot surfaces as a post-deSSA violation) |
| C4 | P2 | deSSA floor ignores phi source tags: when no usable edge is found, the floor copy lands on the def block of `sources.get(0)` regardless of the edge it arrived on (earlier review S4) | **OPEN**, partially mitigated by L2-159's handler-tag routing, floor itself unchanged | corpus SSA verifier catches the value shape, not the edge choice |
| C5 | P2 | handler-entry idom heuristic: a handler block with no computed idom inherits the idom of the block at its range START, not the closest predecessor (earlier review S4; `IRControlFlowGraph.doComputeDominance`) | **OPEN** | none |
| C6 | P2 | M4: CAS with a spilled offset operand fails to compile (`loadEffectiveAddress` refuses a STACK offset into EDX, the CAS arm always passes EDX) -- loud, not silent, but it hides in the same census bucket as real bugs | **OPEN** (disabled-capability noise) | none; census FAILED would list it once the class is reachable |
| C7 | note | the never-popped `ExceptionArgument` on the SSA stack is deliberate (ANCHOR-L2-128); L2-159 additionally re-arms the exception SLOT in the IR generator | by design | corpus SSA verifier |

## D. IR generator / quad semantics (report Part 4)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| D1 | P2 | jsr/ret entry depth depends on translation order | **OPEN** | post-deSSA verification of the jsr probe deliberately skipped (invariant 11) |
| D2 | P3 | latent quad-semantics items (report 4.3) | **OPEN**, unitemised | none |

## E. Interop / structural (report Part 5)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| E1 | - | L2 images are **larger** than L1A everywhere: register pool is ECX/EBX/ESI only, plus a mandatory ECX frame. Compactness goal unmet | **OPEN** (structural) | none (perf/size, not correctness) |
| E2 | - | 64-bit paths stubbed (`writeInitializeClass` throws, interface dispatch commented out) | **OPEN** by design; blocks `cd-x86_64-lite` | none |
| E3 | - | any reasoning about EBX across a call must use the `forcedSpills` model, not callee-saved (L1A pushes are commented out) | **OPEN** as a documentation risk; **GUARD GAP** (invariant 8) | none |

## F. Boot blockers

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| F1 | P0 | `$$ic` class-init barrier dereferences the statics holder before testing it -> null holder faults instead of reaching the init call (the `Integer.stringSize` `mov eax,[eax]`/EAX=0 panic) | **OPEN**, next target | none |
| F2 | P1 | see B2 (`allocObject`) | **OPEN** | none |
| F3 | - | boot signature is layout/timing dependent (0x18E11B -> 0x18E243 -> 0x18E5E3 -> 0x18EC43 -> 0x18EC3B); the same image is not byte-reproducible | recorded | `tests/l2oracle/baselines/boot-signatures.txt` |
| F4 | - | AOT-path coverage: oracle, mauve and census all describe a VM that **booted with L1A** and force-compiles at runtime; nothing observes AOT-emitted L2 code (invariant 14) | **OPEN** (structural gap) | none |
| F5 | - | clinit ordering at AOT boot: `doInitialize` never runs on the early AOT path (invariant 15) | **OPEN** | runtime-forced clinit only |
| F6 | - | builder-side hypothesis, never confirmed nor refuted: `AbstractBootImageBuilder.copyStaticFields` skips every `java.*` class, so java.* static VALUES are never seeded into the image while their AOT `<clinit>`s run on whichever boot thread gets there first; each thread's statics table is a separate image object | **OPEN as a hypothesis** -- the two competing theories (AOT/runtime statics-index mismatch, per-thread isolated statics) are dead by measurement, and the sentinel-store experiments show the store lands, which weakens this one too | none; needs an AOT-path print that the early boot actually executes (the first attempt produced no output because it hooked a path AOT boot bypasses) |

## G. Plan-doc queue not in the report

| # | Defect | Status |
|---|---|---|
| G1 | P11 fall-through / ret-resume edges never explicit | **OPEN** |
| G2 | P12 `IRGenerator.fixType` shared-slot mutation | **OPEN** |
| G3 | P13 `typePhiResults` single-wide-source upgrade | **OPEN** |
| G4 | P14 `pruneDeadPhis` undefined-source drop | **OPEN** |
| G5 | P15 loop classification still mixes flows | **OPEN** |
| G6 | P16 switch `retarget` stale `defaultAddress` (verification only) | **OPEN** |
| G7 | P17 `RetQuad` rename carve-out (doc + test, no code) | **OPEN** |
| G8 | P18 inter-block over-pop (instrument first) | **OPEN** |
| G9 | P19 shared throwing predicate + leftovers | **OPEN** |
| G10 | H6 dup2 form-1 transposition | **OPEN**, needs hand-built bytecode infra |
| G11 | M2 `forcedSpills` omissions incl. class-init | **OPEN**, boot suspect |
| G12 | M5: comparator, INT stamps, keep-list get/setfield, PhiAssign hashCode | **OPEN**, low |
| G13 | P7/P8 (phi primaries bypass usability; foldConstants2 shared-lhs re-type) | **CLOSED-NB** unless a repro appears |
| G14 | P0-1 hardcoded `[rewrite]` debug print, P0-2 ungated `[ssatag] DISAGREE` stderr | **LANDED** (both merge blockers closed early in the queue) | gate: the ssatag stderr census line |
| G15 | P1-P6, P9 (`propagate` kills with live uses, DF-only kill, `Variable.equals` without `hashCode`, terminator guard/flush order, throwing-twins keep-list, `isCallLike` missing LDIV/LREM, always-executed defs) | **LANDED** (P0-P6 + P9 were the base of this branch) | regression suite + census |

## H. Coverage gaps with no bug attached (report Part 7)

Invariant list, with what changed since: 1 label binding **now covered**
(A1's census); 2 branch-to-address-0 **still open**; 3 handler-entry copy
value correctness **open**; 4 register/address correspondence under
synthetic layout **open**; 5 shift destination ECX value **open**; 6 any FP
arithmetic emission test **open**; 7 static-call push widths **open**
(A7); 8 EBX/ESI across calls **open** (E3); 9 `disp1 == disp2` FP aliasing
**open**; 10 `#DE` semantics **open**; 11 post-deSSA jsr **open** (D1); 12
wide phi homes across critical-edge copies **open**; 13 `ExceptionArgument`
as a phi source at non-handler joins **open**; 14 AOT path **open** (F4);
15 AOT clinit ordering **open** (F5).

Additionally, not in the report: the census covers `core/build/classes`
(1,402 classes / 11,603 methods) but **not** `local/classlib` (25,901
classes / 63,720 methods), which is the bulk of the bootimage. The
2026-09-26 verifier census covered both (65,420 methods, ~3 min for the
classlib); its tools were throw-away and are not in the tree, but
`L2Census` now has the capability, so the classlib pass is a script change.

## I. Closed tonight (do not re-chase)

- The 169 census FAILED methods: harness loader never saw the repo's own
  jars. Fixed in the harness; FAILED 169 -> 0 with +206 real methods.
- classLiteral / `Class.getModifiers()` = 1: JNode classlib semantics, not
  L2 (guest L1A and L2-forced values identical).
- mauve `Class.*` force-only diffs: cross-testlet state; isolated runs are
  identical (noforce 14/1, force `force=9` 14/1).
- M3 float-const CCE / FREM arm: not reproducible on this branch.
- NEW-1 root cause (L2-171): argument-slot versions share one frame home;
  `a[i++]` reads the incremented index. Guard = value probes (red proof
  50 vs 70). Two structural lints were tried and BOTH removed: INDEXALIAS
  reported 0 (after de-SSA there is one Variable object per index, so no
  distinct pair exists), and ARGSPLIT reported 7 -- the 2 reproducers plus
  5 known-good methods the oracle passes daily (tryFinally, syncThrow,
  loopLongTryFinally, sync_add, finallyThrowsLong), because its
  address-interval test ignores block structure. Do not re-add either
  without fixing the false positives first.
- Classlib census baseline (2026-09-27, the 3.5x-wider corpus):
  `OK=59122 SKIP=9186 SKIP_CLASSES=2147 SKIP_METHODS=7039 HANDLERS=4976
  FAIL_64=0 FAILED=108`. All five existing lints 0. 106 failures are
  `NoClassDefFoundError` (CORBA, java.management, nanoxml are not on the
  census classpath -- the same lesson as the retired 169) and 2 are the
  real SSA-POST violations in NEW-2. The corpus was previously only
  11,607 methods, so this is the first look at 5x the surface.
- Tooling gap found while doing the above: `regress.sh`'s ORACLE DIFF does
  not report rows present in the host reference but MISSING from the guest.
  Three loop-probe rows vanished from the guest and the diff still read
  clean; only the value comparison caught it. A missing row is a failure.
- A6 FREM-constant-left "FSUB on an empty stack": all six arms use FPREM
  in the right order, and 14 FP oracle rows (add/sub/mul/div/rem, constant
  left and right) match the host bit-for-bit under L2 force. The claim was
  from reading only; the probes now hold the ground.

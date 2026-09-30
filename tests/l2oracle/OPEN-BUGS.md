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
covers the shape), **CLOSED-NB** (investigated, and the code is *structurally* correct -- a probe
row agreeing with the host is never the reason).

**Standing rule (2026-09-27, user).** Every bug I identify gets fixed. A census is
not evidence and never discharges anything: "0 occurrences across 67836 methods" says
only that a shape is rare in *today's* corpus, never that the code is right, and the
compiler has to be right for code that does not exist yet. So the words "measured
latent", "guarded", "0 occurrences", "no production call sites" and "CLOSED on
measurement" are **not** resting states -- an identified defect is either fixed or,
if the report is simply wrong, refuted by reading the code, never by counting hits.
Three defects (A8, C6, A9) were near-paragraphed with exactly that reasoning and two
of them were real; A9 was in fact fixed only after the point was made.

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
| A8 | P3 | constant-null `getfield` never wrote its lhs, `putfield` emitted a load instead of a store (both latent under the null-trap model, no diagnostic) | **LANDED (L2-181)**. Reproducer: `((Holder) null).f` and `((Holder) null).f = v` -- javac emits `aconst_null; getfield/putfield` with no null check, so L2Dump shows the getfield value discarded and the putfield store dropped. Fix: getfield writes the destination the way the normal path does (register or frame home, both halves for a wide field, via `writeConstNullFieldResult`); putfield emits the store the quad means via `writeMOV(size, base, disp, src)`, staging the value through a pushed scratch when it is also the base. Guard: the `CONSTREFFIELD` emission census lint (reverting the fix gives 2 hits, 0 with it) plus permanent `Probes#nullFieldRead`/`#nullFieldWrite`, and `regress.sh` now censuses the compiled probe classes so this lint can never again read 0 merely because its shape is absent. Live: both sides throw NPE on both rows, no divergence. Note this and C6 are both report items that said "no call sites" / "no occurrences" and were nearly skipped for it -- that is how two real codegen defects survived a full day of compiler work |
| A9 | P2 | the `SEG_SHARED_STATICS` / `SEG_ISOLATED_STATICS` address used EDX as its LEA index with SR1 as its base, so `SR1 == EDX` would compute `idx*4 + idx + dataOff` -- a silently wrong address with no diagnostic of any kind | **LANDED (L2-184)**. Rewritten so correctness no longer depends on the allocation at all: a REGISTER-mode index is used directly as the LEA index (which also removes the pointless EDX copy the old code made, so 14 pressure variants now emit `lea eax,[eax+esi*4+4]` instead of `mov edx,[ebp+d]` + `lea`), and a memory index is materialised into a register that is never the base -- ECX, pushed before and popped after the LEA -- which is provably free. No throw, no assertion, no reliance on which register the allocator picks |
| A10 | P1 | **Two defects in the ANCHOR-L2-181 constant-null field arms, introduced/left by that fix and found by constructing the shapes rather than by any gate.** (1) `RefStoreQuad`'s CONSTANT-value arm called `writeMOV_Const(constBits32(val), SR1, offset, constBits32(val))` -- it passed the VALUE where the OPERAND SIZE belongs -- so EVERY constant-valued putfield through a constant null failed to compile: `((H) null).f = 5` -> `IllegalArgumentException: Invalid operand size 5`, `((H) null).o = null` -> `Invalid operand size 0`, `long`/`double` -> `ClassCastException LongConstant/DoubleConstant`. Worse than the A8 behaviour it replaced (which emitted a wrong but compiling load). The guarded probe took an `int` parameter and so only ever exercised the REGISTER/STACK arms. (2) `RefAssignQuad`'s wide arm read the high half AFTER SR1 had been overwritten by the low load, so `[SR1 + fieldOffset + 4]` indexed off the VALUE instead of off null (`mov eax,[eax+8]; mov ebx,[eax+12]`) | **LANDED (L2-186, guarded, red proof)**. (1) sizes now mirror the normal path (Z/B -> BITS8, S/C -> BITS16, I/F -> BITS32, J/D -> two BITS32 halves from LongConstant/DoubleConstant). (2) the high half is read while SR1 still holds the base, and the destination halves use this frame's **high-base spill convention** (ANCHOR-L2-075/082: the location's displacement is the MSB, the LSB lives at `disp - SLOTSIZE`) -- the first cut used `disp + 4` for the MSB and wrote `[EBP+0]`, which `X86BinaryAssembler.testDst` rejected during the bootimage AOT compile. Guard: T1 `testNullConstFieldStores` over three new `PrimitiveTest` shapes + four new `Probes` methods (`nullFieldWriteConst`/`nullFieldWriteWideConst`/`nullFieldWriteRefConst`/`nullFieldReadWide`) feeding the now-working probe census. Red proof: fix reverted -> T1 `Tests run: 40, Failures: 1`, alljunit `Errors: 1`, bootimage `build` FAIL, probe census `failed=3` (the three putfield shapes; the wide GETFIELD compiles pre-fix and is caught by the assertion `read the high half AFTER clobbering the base register (offsets 4/8)`); fix restored -> T1 40, alljunit 256/0, census `OK=11618 FAILED=0`, probe census `constreffield=0 failed=0`, live oracle 196 rows / 3 retired divergences, mauve v2 force-only DIFF empty |

## B. IR construction (report Part 2)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| NEW-3 | P1 | **`putstatic` of a 32-bit FLOAT constant did not compile at all.** `java.awt.font.TextMeasurer#<clinit>` is `ldc 2.1f; putstatic F; iconst_0; putstatic Z; return`, and `EST_LINES` is a **float** (signature `F`), so `fieldRef.isWide()` is false and the code took the NARROW `generateCodeFor(StaticRefStoreQuad)` path -- whose CONSTANT arms tested only `instanceof IntConstant`, while `ldc <float>` yields a `FloatConstant`. The value fell through to `throw new IllegalArgumentException()` at GenericX86CodeGenerator:6889, so the method produced no code at all (census `FAILED=1`, OK 18->19). Same shape ANCHOR-L2-094 (int arm), ANCHOR-L2-095 (wide arm) and the `constBits32` helper for putfield/array stores had already covered; the 32-bit float case was the one gap, and NOT the `$$ic`/F1 barrier family the original note suspected. NOT the same as F1 (a runtime null read after the barrier) -- plain emitter coverage | **LANDED (guarded, value-level, red proof)**. `GenericX86CodeGenerator.generateCodeFor(StaticRefStoreQuad)`: both narrow CONSTANT arms (shared and isolated) now accept `FloatConstant` and emit through `constBits32`, so a float immediate is a raw 32-bit move of `Float.floatToRawIntBits` (verified: `mov eax,0x40066666` + `mov dword[edx+152],eax`). Wide statics already handled `Long`/`Double`. Guards: T1 `testFloatConstPutStaticStores` (pinned to `PrimitiveTest`'s new `static float floatConstStatic = 2.1f` in the same `<clinit>` as L2-149's `wideConstStatic`, asserting the immediate AND the following store) plus a value probe `staticFloatBits` + `CASES` row (read-back, so a materialize-without-store regression also fails). Red proof with the fix reverted: T1 `Tests run: 39, Failures: 2`; census over `Probes` `FAILED=2` (`Probes#<clinit>`, `Probes#staticFloatBits`); the classlib narrow repro back to `FAILED=1 java.awt.font.TextMeasurer#<clinit>`. Evidence: `regress.sh --label new3a host isobuild oracle mauve 2` green (t1 39, alljunit 255/0, census `OK=11611 FAILED=0 lints=0 rangegap=0`), live oracle rows=194 = only the 3 retired divergences (the new row matches the host), mauve v2 force-only DIFF empty; `census-wide.sh` FAILED 3 -> 2 (the remainder is NEW-2's two SSA-POST methods) |
| NEW-2 | P1 | a phi with an `UndefinedVariable` source left its join with an incoming edge that carried no definition, so the post-deSSA SSA verifier rejected the method: `read of l12_3 at 129: throw l12_3 in B539 is not written on every path; defs: [180: l12_3 = l12_2 in B-2147483643 @180]`. jsr/finally shapes, two corpus sites: `gnu.testlet.java.nio.channels.FileChannel.lock#test` (phi l12_3 in join B542, two UndefinedVariable sources tagged with the synthetic critical-edge blocks B-2147483645 / B-2147483644, so two of its three incoming edges were undefined) and `gnu.testlet.java.io.File.security#test` (l25_3 / B1379) | **LANDED (L2-185)**. The copy was built and then marked `setDeadCode(true)` on the stated theory that it was "a real SSA def for verification but must never be read by the code generator" -- both halves wrong: a dead quad is invisible to the SSA verifier *and* to liveness, so it was not a def at all. Three lowerings were measured before one worked: (1) live copy of the UndefinedVariable -> the complaint moves to its own operand, `read of u12_0 at 176: l12_3 = u12_0 in B-2147483645 is not written on every path`; (2) self-copy `x = x` -> a real definition, but elided as a no-op and the original failure returns; (3) **define the phis result to the type default on that edge** -- `ConstantRefAssignQuad` with `Constant.getInstance(0)`, which is how this IR spells a null reference (`IRGenerator.NULL_CONSTANT`; a `ReferenceConstant` reaches the emitter as "Non-int constant def: null"). That is the JVM rule for an uninitialized local, it survives to the verifier, and it gives liveness a real def. Guard: both corpus methods are now `FAILED=0` and the **wide census is clean for the first time, `OK=67838 FAILED=0`** (was `OK=67836 FAILED=2`), plus the SSA-POST verifier itself. Non-reference bottoms have no constant-assign quad in this IR (the index-based `BinaryQuad` ctor clones its lhs, which would break the shared-lhs invariant ANCHOR-L2-137) so they keep the self-copy; no instance is known |
| NEW-1a | P0 | **Fix attempt for NEW-1, reverted.** Two designs, both NPE in the bootimage compile, so the change needs a real design, not a one-liner. (1) `MethodArgument.clone()` returns an unlocated clone: every version of an argument slot then has no home, and dead defs of that slot reach codegen with a null Location -> NPE in `GenericX86CodeGenerator.generateCodeFor`. (2) `clone()` returns a plain `LocalVariable` so the allocator treats it as an ordinary local: `removeUnusedVars:144` NPEs on `getKey().getAssignQuad().isDeadCode()` because a version with no assign quad is not a `MethodArgument` and so no longer short-circuits. Hardening that to `aq == null -> keep` builds, but then the corpus collapses (`OK` 11607 -> 1556, `MAGIC=81`) with `SSA-PRE: use without def: ... reads l0_1`: the def quad attaches to the ORIGINAL argument object (`visit_iinc` uses `variables[index]` as the BinaryQuad LHS), so the clone is a def-less variable that DCE now keeps and the verifier then flags. Both attempts reverted. Conclusion for the next attempt: the fix belongs in SSA construction, not in `clone()` -- the version that receives the def must be the version that is read, and every emitted version needs a home while the incoming argument keeps its frame slot (handler entries and deopt read the frame) | **SUPERSEDED by NEW-1 (LANDED, ANCHOR-L2-171).** The aliasing defect this row describes *is* NEW-1's, and NEW-1 landed with a guard at a third layer -- `LinearScanAllocator.allocate()` gives a *defined* `MethodArgument` version its own spill home, so neither `clone()` nor SSA construction turned out to be the answer and the "conclusion for the next attempt" below is moot. Both attempts stay reverted; keep this row only for the negative result that `clone()`-level fixes are provably the wrong layer | none of its own -- the defect is guarded by NEW-1: the ANCHOR-L2-171 allocator rule plus the 7 `postIncr*` oracle rows |
| NEW-1 | P0 | **`a[i++]` reads the incremented index.** Two independent defects, both now fixed. (1) **Argument-slot home aliasing**: `a[i++]` is `aload a; iload i; iinc i,1; iaload` (index pushed BEFORE the iinc), a live local's home is its INDEX (`getEbpOffset`), SSA clones copy their original's `Location`, and a clone of a `MethodArgument` is itself a `MethodArgument` -- so the allocator skipped it too and **every SSA version of an argument slot shared one frame slot**; a definition clobbered an older live version (L2 emitted `qb_13: add dword[ebp-20],1` then `qb_14: mov ecx,dword[ebp-20]`). (2) **de-SSA edge-copy placement**: the back-edge copy of a phi was flushed at the FIRST in-block read of the phi result (ANCHOR-L2-159 bound (b)), but in a PREDECESSOR block every existing read of the phi result wants the PREVIOUS visit's value, so the copy must follow the LAST such read -- in `Probes#postIncrLoop_aii`'s latch it landed between `l2_3 = l2_2 + 1` and `a[l2_2]`, handing the load the incremented index; and `removeDefUseChains` copy coalescing then retargeted the def's LHS to the copy's LHS with nothing between them | **LANDED (guarded, value-level, red proof)**. Fixes: `LinearScanAllocator.allocate()` ANCHOR-L2-171 -- a DEFINED `MethodArgument` version gets its own spill home, only the incoming value (`getAssignQuad()==null`) keeps the JVM slot; `IRControlFlowGraph.flushCopy` ANCHOR-L2-176 -- edge copy (block != phi's block) placed at `max(source-def end, last-in-block-read+1)`, clamped before the terminator, join-block copies keep the L2-159/162 rule; `IRControlFlowGraph.removeDefUseChains` ANCHOR-L2-171 -- refuse the coalescing when any live quad between def and copy reads the copy's LHS. Guard = the 7 `postIncr*` rows in `OracleDriver.CASES`, all RED before and all GREEN now (`postIncrLoop_aii` `10,20,30,40,3` = `I:3c`, `1,2,3,4,5,5` = `I:f`, `postIncrLoopStore_aii` `0,0,0,0,3` = `I:cb`, plus the 4 straight-line rows); `regress.sh --label new1-fix2 host` green (anchors/t0 19/t3 19/t1 38/alljunit 254-0/census `OK=11608 FAILED=0`/lints=0); live oracle `host_only=3 guest_only=3` = only the 3 retired divergences (div_iii #DE, classLiteral x2). mauve v2 no longer reports the first-character loss; the residual crash was NEW-1b, now fixed |
| NEW-1b | P0 | **Residual `java.util.Properties` force defect: the `\\uXXXX` result was too long (stale `convtBuf` tail) and mauve v2 still crashed. Two halves of ONE live-range bug -- both ends of a range were derived from a linear walk of addresses instead of the CFG. **(1) END**: a value that must survive a COLD failure-path `call` (the bounds-check failure block, laid out after the reader) never had that call in its range, so ANCHOR-L2-107's forced spill never fired and the digit scratch reused the register holding the converted length (`--ranges s11_14: 8-11` stopped before the branch). **(2) START** (the mauve headline): a loop-carried phi is de-SSA'd into copies in the LATCH blocks, which lay out AFTER the blocks that read it, so `getFirstDefAddress()` is a high address -- `AcuniaPropertiesTest#test_store` defines `start` (`l5_4`) at 163/168 and reads it at 137/141/151, giving range `[164,169]` with a hole at the start. The IR was right, the frame reserved enough slots, and the allocator only COMPARES ranges, so the hole was invisible: EBX went to the inner-loop temporaries too (`s10_67` 139, `s10_71` 145, `s9_68` 147) and the guest called `new String(ba, <ba.length>, ...)` -> `StringIndexOutOfBoundsException: 892382384` at check 54 | **LANDED (guarded, value-level, red proof)**. Fix: `IRControlFlowGraph.extendRangesAcrossBackEdges()` ANCHOR-L2-177 -- one backward dataflow over the CFG: live-OUT raises `lastUseAddress` (also makes the value span the cold call, which is what the forced spill keys on), live-IN pulls the start back to the block top through the new `Variable.noteLiveFrom(blockStart-1)` (keeps the minimum, like `noteDef`); straight-line methods untouched. Guard: census lint `RANGEGAP` ANCHOR-L2-178 (`L2Census.checkRangeCoverage` -- every use must sit inside its variable's FINAL range, checked against the result rather than the rule that built it), wired into the census gate as its own verdict (`rangegap=0`, no longer just an informational count). Red proof: `noteLiveFrom` disabled prints `RANGEGAP ...AcuniaPropertiesTest.test_store var=5 use=137 range=[164,169]` and `...test_save var=5 use=148 range=[175,180]` -- a second, latent victim the crash had hidden; enabled, `rangegap=0`. Evidence: `regress.sh --label new1b7 host isobuild oracle mauve 2` green (build/anchors/t0 19/t3 19/t1 38/alljunit 254-0/census `OK=11610 FAILED=0 lints=0 rangegap=0`/isobuild), live oracle rows=193 = only the 3 retired divergences, **mauve v2 force-only DIFF empty** (base=52 forced=52); guest `AcuniaPropertiesTest` `DONE checks=81 failed=0` (was `THREW after 54`), `forceprops` `forced 24 of 24 ... OK`, ConvProbe CLEAN/DIRTY/ASCII/ESC all correct |
| B1 | P1 | handler-entry phi takes the **pre-try** version of each slot on a non-self-edge handler (ordinary try/catch), so a store executed inside the try block before the throw is invisible to the handler -- the row the previous session called "never written on the exceptional dispatch"; the actual defect is the opposite sign: the phi source is *always* written, just far too early | **LANDED (L2-189, value-level guard, red proof).** Root-cause chain, every link measured: (a) raw IR is right -- `Probes#b1HandlerPhi` emits `6: s4_0=7` BEFORE `12: s4_0 = s4_0/s5_0` (`L2Dump … --raw`); (b) after `constructSSA` the handler phi is already `41: l1_7 = phi(l1_1,l1_3,l1_5)`, sourced from the **end-of-block** version `l1_3` rather than the throw-point version `l1_2`, because `rewritePhiParams` reads `SSAStack.peek()` (`--ssa0`); (c) constant propagation therefore folds the store into `s4_7 = 7 + s5_2`, `removeUnusedVars` DCEs `l1_2 = 7`, and the handler's `bci_41: mov eax,dword[ebp-16]` reads the pre-try 1 with **no phi copy on the dispatch at all**. Both available extremes were wrong: the pre-try source (the `placeHandlerPhis` / ANCHOR-L2-125 model) loses every in-try store, and the end-of-block source the code actually used names homes the throw never wrote -- which is exactly the ANCHOR-L2-125 unwritten-home crash, so neither can be chosen outright. Fix is the third: `IRControlFlowGraph.snapshotThrowTops` records each predecessor's slot tops at its **first throwing quad** (`!q.isDeadCode() && isCallLike(q)`, first throw wins) and `rewritePhiParams` uses that snapshot for a `pred != succ` handler edge. A throw-point top is always written on the path that reaches the throw, so it can never be an unwritten home, and where nothing has been stored yet it degenerates to the pre-try version -- L2-125 stays fixed by construction rather than by luck. Guard: `Probes#b1HandlerPhi(int)` + two `OracleDriver.CASES` rows, host reference `0 -> I:7`, `5 -> I:9`. Red proof: fix reverted -> guest `b1HandlerPhi\|0\|I:1`, `\|5\|I:1`, `ORACLE DIFF rc=1 host_only=5 guest_only=5`; fix restored -> both rows match the host and the diff is the 3 retired rows only. Evidence: `regress.sh --label b1fixgreen host isobuild oracle mauve 2` all PASS (build / anchors / t0 19 / t3 19 / t1 40 / alljunit 256 / census / isobuild; probe census `constreffield=0 failed=0`), live oracle `rows=198 host_only=3 guest_only=3`, mauve v2 force-only DIFF empty `base=52 forced=52`. The old "still uninstrumented" caveat was correct as far as it went: the value probe is the instrument that worked, the structural lint never could |
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
| C6 | P2 | M4: a four-operand magic CAS with a STACK offset fails to compile: `loadEffectiveAddress` hardcoded its displacement scratch to EDX and refused when the destination was also EDX ("Offset temp collides with dst"), and the CAS arm always passes EDX | **LANDED (L2-182)**. **My "measured latent" conclusion here was wrong and I have recorded why**: absence from the corpus shows a shape is RARE, not that the codegen is right. The reproducer is one legal call -- `org.vmmagic.unboxed.Address.attempt(int, int, Offset)` (BaseMagicHelper `ATTEMPTINT_OFS`) with enough live locals to spill the Offset -- i.e. `Probes#casOfs_iio`, whose register pressure puts it in a frame slot. Fix: `loadEffectiveAddress` takes the scratch register as a parameter; the array-store callers keep EDX, and the CAS arm passes **ECX**, which that arm already pushes before the address computation and pops after. Emission is now `lea edx,[edx+ecx]` + `cmpxchg [edx],ecx`. Guard: the probe-census `FAILED==0` gate -- pre-fix `Probes#casOfs_iio` throws `IllegalArgumentException: Offset temp collides with dst`, post-fix the probe corpus is `OK=129 FAILED=0`. No value row is possible (the oracle driver cannot construct an `Address`/`Offset` argument, and C6 is a refusal to compile rather than a wrong value) |
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
| F1 | P0 | **`java.lang.Integer.sizeTable` reads NULL at boot.** `Integer.stringSize` #PFs *inside the array bounds check* (`mov eax,[eax]`, EAX=0, `CR2=0`, EIP `0018EC3B`); panic stack words: arrayref `[ebp-0x14]`=0, index `[ebp-0x10]`=0, argument `[ebp-0x0c]`=6. **Mechanism CORRECTED 2026-09-27**: `$$cbtest`/`$$cbfailed` are `checkBounds` (`GenericX86CodeGenerator.java:5849-5850`; the `failed` arm ends in `invokeJavaMethod(getThrowArrayOutOfBounds())` -> `call [edi+1420]` = `SoftByteCodes.throwArrayOutOfBounds(Object,int)`, `SoftByteCodes.java:371`) -- **not** a class-init barrier, so the earlier "null-unsafe barrier test, make the test tolerate a null holder" reading (report row 14 / §11.3 / §11.4 / §11.5) is **withdrawn**. The barrier *is* emitted for the getstatic and in the right order (`bootimage.txt`, `stringSize`: `test [ebx+12],0x100` -> `test [fs:12+11860],0x100` -> `call $$init_java.lang.Integer` -> `mov esi,[ecx+11888]`), so "no barrier / elided at compile time" is dead too. Open question: why the slot is null *after* the barrier -- (i) runtime `state&ST_ALWAYS_INITIALIZED` or `IST_INITIALIZED` set while the slot is null, (ii) `<clinit>` never entered / stuck `IST_INITIALIZING`, (iii) F6 (read and write reach different isolated tables) | **OPEN -- mechanism unexplained; symptom not reproducible as of 2026-09-30, which the standing rule says is NOT closure.** Re-measured on a pure-L2 AOT image (`-Djnode.compiler=L2 -Djnode.jit.compiler=L2`, `Compiler union X86-Stub X86-L2`, 13706 optimized methods): boots to a shell in 15s, `gc` runs 4x and reclaims cleanly each time, `ls /` works, and the serial log has 0 matches for `COMPILE FALLBACK`, `Error in compilation` or `CompileError` -- so the crash family of §11 no longer manifests on the image it was found on. That refutes nothing: the open question below (why the slot is null *after* the barrier) is still unanswered, and a boot that happens to work is a census in disguise. Next action is ONE probe build (below), then bootimage stays last per `HANDOVER.md` §5.6 | none. AOT-proven probe point: `MemoryBlockManager.initialize` (`MemoryBlockManager.java:299` -- its `Unsafe.debug("end of initialize.")` is the last line printed before the panic); dump `state`, isolated state and the slot value for `VmSystem.getSystemClassLoader().findLoadedClass("java.lang.Integer")` (no clinit triggered). **Do not instrument `VmType.initialize()`**: it is `@Inline` and sits on the barrier path -- doing so moved the boot to an unrelated earlier crash (`int 0x31` stack-overflow trap, EIP `0010B807`), and reverting restored `0018EC3B` byte-for-byte |
| F2 | P1 | see B2 (`allocObject`) | **OPEN** | none |
| F3 | - | boot signature is layout/timing dependent (0x18E11B -> 0x18E243 -> 0x18E5E3 -> 0x18EC43 -> 0x18EC3B); the same image is not byte-reproducible | recorded | `tests/l2oracle/baselines/boot-signatures.txt` |
| F4 | - | AOT-path coverage: oracle, mauve and census all describe a VM that **booted with L1A** and force-compiles at runtime; nothing observes AOT-emitted L2 code (invariant 14) | **OPEN** (structural gap) | none |
| F5 | - | clinit ordering at AOT boot: `doInitialize` never runs on the early AOT path (invariant 15) | **OPEN** | runtime-forced clinit only |
| F6 | - | builder-side hypothesis, never confirmed nor refuted: `AbstractBootImageBuilder.copyStaticFields` skips every `java.*` class, so java.* static VALUES are never seeded into the image while their AOT `<clinit>`s run on whichever boot thread gets there first; each thread's statics table is a separate image object | **OPEN as a hypothesis** -- the two competing theories (AOT/runtime statics-index mismatch, per-thread isolated statics) are dead by measurement, and the sentinel-store experiments show the store lands, which weakens this one too | none; needs an AOT-path print that the early boot actually executes (the first attempt produced no output because it hooked a path AOT boot bypasses) |

**F1 — cheapest discriminator, ZERO boots.** `writeClassInitialize` gates
*emission* on `cls.isAlwaysInitialized()` at compile time, and the barrier was
emitted, so `state & 0x100` was **clear in the compiler**. But the emitted code
tests `state` again *at runtime*, on the VmType object that is serialized into
the image. Two places can flip that bit between codegen and emission, and both
do it on a *null* `getInitializerMethod()`: `VmType.link()`
(`VmType.java:1769`) and `VmType.verifyBeforeEmit()` (`VmType.java:2232`) — and
`getInitializerMethod()` returns null whenever `methodTable` is null
(`VmType.java:2314`). So the first thing to check is the `state` word of
`java.lang.Integer`'s VmType **in the image** (or logged at emit time: flag any
class whose class file has a `<clinit>` but whose emitted `state` carries
`ST_ALWAYS_INITIALIZED`). If that bit is set, cause (i) is confirmed without a
single boot, and the fix is in the builder/prepare path, not in the barrier.
If it is clear, the barrier's fast path must have been taken via
`IST_INITIALIZED` and the probe build of the row above decides between (i-iso)
and (iii).

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
value correctness **now covered by a value probe** (`Probes#b1HandlerPhi` +
the L2-189 throw-point phi sources; the self-edge half is L2-159 and the
structural lint that was meant to cover both examined 0 phis and was removed); 4 register/address correspondence under
synthetic layout **open**; 5 shift destination ECX value **open**; 6 any FP
arithmetic emission test **open**; 7 static-call push widths **open**
(A7); 8 EBX/ESI across calls **open** (E3); 9 `disp1 == disp2` FP aliasing
**open**; 10 `#DE` semantics **open**; 11 post-deSSA jsr **open** (D1); 12
wide phi homes across critical-edge copies **open**; 13 `ExceptionArgument`
as a phi source at non-handler joins **open**; 14 AOT path **open** (F4);
15 AOT clinit ordering **open** (F5).

Additionally, not in the report: the default **gate** corpus is
`core/build/classes` (1,402 classes / 11,603 methods). The bulk of the
bootimage lives in `local/classlib` (25,901 classes / 63,720 methods) and is
now covered by the chunked wide census (`tests/l2oracle/census-wide.sh`:
67,836 methods, FAILED=2) -- so "the classlib pass is a script change" is
done; it must simply never be run as a single sweep (the cumulative 0x20000
bound, see §I).

Additionally, a gate-level gap found and fixed 2026-09-30: **`regress.sh`
could not FAIL.** `run()` printed `FAIL <phase> rc=N` into the status file
and returned 0, and the script's last line was an unconditional `say`, so
the process exited 0 whatever the phases did -- measured, a deliberately
red `t3` produced `FAIL t3 rc=1` with script exit status 0, and the same
held for that day's `policy` failure. Anything chaining
`regress.sh ... && ...`, or CI reading `$?`, therefore sailed past a red
gate, and the "gate order before committing" rule was satisfied only by a
human reading the status lines. **LANDED (ANCHOR-L2-200)**: `FAILURES` is
incremented by every `run()` and, through a new `fail()` helper, by the
inline live-phase failures (`javac FAILED`, `run FAILED/STALLED`, both
`BOOT FAILED` sites); the script exits 1 when the tally is non-zero, and
the per-phase detail stays in `$ST`. Red proof: the red `t3` exits 1, the
same phase on a good tree exits 0. Third instance of "a check that cannot
fail looks exactly like a check that found nothing", after ANCHOR-L2-187.

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
  truncates the corpus. Chunked by package prefix: OK=67,836 (+15%),
  FAILED=2 after NEW-3 landed (3 before it). Do not "simplify" this back to one sweep. (An earlier copy of this
  bullet said FAILED=6, from before c502ca1ee retired the second NEW-3
  candidate; the current number is 3, matching `HANDOVER.md` §2/§7 and the
  chunk-boundary bullet below.)
- Non-L2 census failures are now separated and each carries its evidence:
  SKIP_MISSING_DEP=77 (CORBA absent from this tree, optional JNode classes),
  SKIP_ENV (native method not resolvable in the harness, IR scope gaps such
  as visit_dup2_x1, recursive class prepare, classlib version mismatch),
  both listed per method. After the chunk-boundary fix the wide corpus
  reports FAILED=2 in total: NEW-2's two SSA-POST methods, re-measured after NEW-3
  landed on 2026-09-27 (it was 3 while TextMeasurer#<clinit> still failed). Everything
  else is a classified, listed skip.
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
- Two gate checks could NOT fail, found while adding A10's guards (ANCHOR-L2-187). The probe census ran `"$HJ"` inside `sh -c`, where `HJ` and `LABEL` are unset (they are not exported), so java never started, its stderr went to `/dev/null`, the report file was never written, and the awk that counted its FAILED section then read a nonexistent file -- which is 0. It printed `constreffield=0 failed=0` on every run, including the runs cited as the guard for A8 and C6. And `t0`/`t3`/`t1` ended on `| tail -n 3`, so the status `run` saw was tail's: measured, `t1` printed `Tests run: 40, Failures: 1` on a PASS line. Both are fixed; the lesson is the one already written for `grep -c 'error:'` -- a check that cannot fail looks exactly like a check that found nothing, so **prove the gate goes red on a known bad tree before trusting it green.**
- Tooling gap found while doing the above: `regress.sh`'s ORACLE DIFF does
  not report rows present in the host reference but MISSING from the guest.
  Three loop-probe rows vanished from the guest and the diff still read
  clean; only the value comparison caught it. A missing row is a failure.
- A6 FREM-constant-left "FSUB on an empty stack": all six arms use FPREM
  in the right order, and 14 FP oracle rows (add/sub/mul/div/rem, constant
  left and right) match the host bit-for-bit under L2 force. The claim was
  from reading only; the probes now hold the ground.


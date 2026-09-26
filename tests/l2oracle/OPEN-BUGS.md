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
| A3 | P1 | `arraylength` clobbers EBX, an allocated register, with no push/pop | **OPEN** (CODE-READ, highest interop risk: L1A cannot produce it) | none; no `arraylength` value probe |
| A4 | P1 | shift arms destroy the destination when it is ECX (12 arms) | **OPEN** (same shape as landed H1/L2-150 SAL slip - confirm whether identical) | **GUARD GAP**: matrix pins the mnemonic, no probe asserts a shift *value* (invariant 5) |
| A5 | P1 | `isCallLike` omits quads that really emit a `CALL` (`ConstantClassAssignQuad`, barriers) -> live ranges not force-spilled | **OPEN** | none |
| A6 | P2 | `FREM` with a constant left operand emits `FSUB` on an empty x87 stack | **OPEN** | **GUARD GAP**: no FP emission test at all (invariant 6: no FADD/FSUB/FDIV/FMUL/FREM assert) |
| A7 | P2 | `writeParameters` signature gate never fires for `invokestatic` -> the L2-155 class on the static path | **OPEN** | **GUARD GAP**: `checkCallPushWidths` skips non-instance quads, so `WIDTHMISMATCH` cannot fire for statics (invariant 7) |
| A8 | P3 | constant-null `getfield` never writes lhs, `putfield` emits a load (latent under the null-trap model) | **OPEN** | none |

## B. IR construction (report Part 2)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| B1 | P1 | handler-entry phi never written on the exceptional dispatch for **non-self-edge** handlers (ordinary try/catch) | **OPEN** (L2-159 covers the self-edge/tagged shape only) | verifier carves this shape out (invariant 3); no try/catch probe |
| B2 | P1 | synthetic critical-edge blocks appended at layout end -> inverted live intervals -> register aliasing at the join (the `allocObject` #PF CR2=8) | **OPEN** (naive fix tried and reverted) | `interferesWith` never checked against execution order (invariant 4) |
| B3 | P2/P3 | smaller construction items (report 2.4) | **OPEN**, unitemised | none |

## C. deSSA / live ranges / regalloc (report Part 3)

| # | Sev | Defect | Status | Guard today |
|---|---|---|---|---|
| C1 | P2 | `LiveRange.compareTo` is not a valid total order | **OPEN** | none |
| C2 | P2 | `removeDefUseChains` coalescing unguarded (= plan P10) | **OPEN** | none |
| C3 | P1 | `handlerEntryTops` snapshot skipped when the exception slot has no SSA stack -> self-referential phi copy (found in my own L2-159) | **OPEN** | corpus SSA verifier would catch it if a corpus shape triggers it |
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

# L2 sibling-bug implementation plan (audit of fixes L2-127 .. L2-139)

Branch: `L2-Nex-N2.5-Pro`. Method: for each of the 10 recent codegen fixes,
the same pattern was grepped across `core/src/core/org/jnode/vm/compiler/ir/`
and `core/src/core/org/jnode/vm/x86/compiler/l2/`.

Ordering below is by **impact = likelihood of occurrence** (most likely
first), not by the original anchor numbers. P0 items are merge blockers
(remove regardless). Code quotes were verified against the tree on
2026-09-22 unless marked `[confirm]`.

Per-item gates: (1) focused host suite, (2) full suite (236+ tests),
(3) census FAILED-list identity (or reviewed delta), (4) guest
`force|79` oracle + mauve v1-v5 `noforce`-vs-`force` `rc=0` where marked.

Conventions: host repro = new `PrimitiveTest` probe + `L2PipelineTest`
assertion (style of `twoCatches` / `testSiblingHandlerReadsPreTryVersion`);
guest repro = `tests/l2oracle/Probes.java` + `OracleDriver.CASES` row.

---

## P0-1 (merge blocker): delete hardcoded `[rewrite]` debug print

`IRControlFlowGraph.java` (deSSA phi-rewrite loop, ~line 1828):

```java
if (succ.getStartPC() == 164) {
    System.err.println("[rewrite] pred=" + pred + " dead="
        + aq.isDeadCode() + " lhs=" + aq.getLHS());
}
```

Fires on **any** method containing a block at BCI 164. Hunk: delete the
4 lines, keep the surrounding `for (Quad<T> q : succ.getQuads())`
`instanceof PhiAssignQuad` loop intact. Same hunk may delete the dead
write-only `handlerTops/handlerPres` (`~1694-1695`, `~1817-1818`, zero
readers). Gate: `grep -rn "getStartPC() == 164" core/` empty + host suite.

## P0-2 (merge blocker): gate `[ssatag] DISAGREE` stderr

`IRControlFlowGraph.java` (`deconstructOnePhi` assertion loop, ~1012):

```java
tagDisagreements++;
if (tagDisagreements <= 20) {
    System.err.println("[ssatag] DISAGREE phi for " + lhs ...);
}
```

Hunk:

```java
static final boolean SSATAG_LOG = Boolean.getBoolean("jnode.l2.ssatag");
...
tagDisagreements++;
if (SSATAG_LOG && tagDisagreements <= 20) { ... }
```

Repro/gate: census with `-Djnode.l2.ssatag=true` reproduces the 144/646
counters; default run prints nothing; counters unchanged.

---

## P1 (highest likelihood): `UnaryQuad`/`BinaryQuad.propagate` kill with live uses

Same class as fixed L2-132 (`ConstantRefAssignQuad`), still live in both
twins. Current code (verbatim):

```java
// UnaryQuad.java:77-85
public Operand<T> propagate(Variable<T> operand) {
    Quad<T> quad = foldConstants();
    if (quad instanceof ConstantRefAssignQuad) {
        setDeadCode(true);              // <-- kills def for ALL other uses
        ConstantRefAssignQuad<T> cop = (ConstantRefAssignQuad<T>) quad;
        return cop.getRHS();
    }
    return operand;
}
// BinaryQuad.java:366-374 — identical shape
```

Why most likely: constant folding fires in nearly every method; the first
`doPass2` simplification of any use kills the def while a second reader
(`dcmpl`, `ret`, another arithmetic use, a `phiMove`) still references it
-> codegen emits no write -> garbage read. Note `BinaryQuad:400-413`
`maybeKeepVariable` pins wide-const defs, but `UnaryQuad` has no such guard,
and neither covers the second-use case.

Hunk (both files, L2-132 pattern — reference: `ConstantRefAssignQuad:91-101`
which now just `return rhs;`):

```java
public Operand<T> propagate(Variable<T> operand) {
    Quad<T> quad = foldConstants();
    if (quad instanceof ConstantRefAssignQuad) {
        // ANCHOR-L2-14x: no kill here; removeUnusedVars decides (cf. L2-132).
        ConstantRefAssignQuad<T> cop = (ConstantRefAssignQuad<T>) quad;
        return cop.getRHS();
    }
    return operand;
}
```

Repro — host probe (`PrimitiveTest`, `remPiOver2` reduced):

```java
static double twoUses(double d) {
    double h = 0.5;          // folds into first use...
    double a = d + h;        // ...but must survive for second use
    double b = d * h;
    return a + b;
}
```

Host test: `--ir` shows `h = 0.5` present with both readers wired (no
reader on a dead def). Guest: FP oracle rows (existing `double` rows cover
it if the fold triggers; else add `foldTwoUses`). Gate: census may show an
OK gain (previously-killed defs survive) — review the delta, expect zero
new failures.

## P2: `VariableRefAssignQuad.propagate` DF-only kill

Current (`VariableRefAssignQuad.java:72-102`, verbatim): scans only
`getDominanceFrontier()` blocks for a phi use of `operand`, else
`setDeadCode(true)` (`:94`). Same-block second uses, return/throw/ret
readers, switch keys, branch conditions, monitor operands and
handler-resume uses outside the DF are uncounted. Copy propagation runs on
every method, so the trigger is common; the window is narrower than P1
(only copy chains).

Hunk (mirror L2-132; DCE owns the decision):

```java
public Operand<T> propagate(Variable<T> operand) {
    if (!(refs[0] instanceof ExceptionArgument)) {
        return refs[0];   // removeUnusedVars decides the kill
    }
    return operand;
}
```

Repro — host: copy with a same-block second use plus a `ret`/switch-key
use; assert in `--ir` post-`doPass2` the def survives. Gate: census-diff
(expected: identical or small OK gain), full suite, oracle green.

## P3: `Variable.equals` without `hashCode` undercounts DCE uses

Current (`Variable.java:179-186`, verbatim): `equals` on
`(index, ssaValue)`, **no `hashCode` override** (grepped: no `hashCode` in
`Variable`/`LocalVariable`/`StackVariable`/`PhiOperand`). `removeUnusedVars`
/ `getVariableUsage` count into `HashMap<Variable,Integer>`, so
equal-but-not-identical clones (`SSAStack:54-60` location-preserving
clones) land in different buckets -> use count too low -> a def used on a
handler/switch/jsr arm can be killed. Fires wherever clones meet DCE.

Hunk (fields verified: `private int index; private int ssaValue;`):

```java
public int hashCode() {
    return index * 31 + ssaValue;
}
```

[confirm]: subclasses (`LocalVariable`, `StackVariable`) override `clone()`
but not `equals`, so the base hash is consistent — re-verify while editing.
Repro — host unit test: two equal-but-not-identical clones as map keys
must collapse to one entry; census-diff (possible OK gain). Guest: oracle
must stay green.

## P4: `IRBasicBlock.add` terminator guard + deSSA flush order

Current guard (`IRBasicBlock.java:181-189`, verbatim):

```java
public void add(Quad<T> q) {
    addDef(q);
    int n = quads.size();
    if (n < 1 || q instanceof BranchQuad || !(quads.get(n - 1) instanceof BranchQuad)) {
        quads.add(q);
    } else {
        quads.add(n - 1, q);
    }
}
```

Only `BranchQuad` (conditional + unconditional) is recognized. The deSSA
flush (`IRControlFlowGraph.java:1250,1260`, `b.add(move)`) into a
switch-pred or `ret` block therefore lands the edge copy **after** a
`Tableswitch`/`Lookupswitch`/`RetQuad` terminator — dead, edge value lost.
Switch-phi joins are common; def-in-switch-pred is the narrow trigger.

Hunk:

```java
if (n < 1 || isTerminator(q) || !isTerminator(quads.get(n - 1))) {
    quads.add(q);
} else {
    quads.add(n - 1, q);
}

private static boolean isTerminator(Quad<T> q) {   // [confirm] generic bounds + VoidReturnQuad presence
    return q instanceof BranchQuad || q instanceof TableswitchQuad
        || q instanceof LookupswitchQuad || q instanceof ThrowQuad
        || q instanceof RetQuad || q instanceof JsrQuad
        || q instanceof VarReturnQuad;
}
```

Quad names verified against `ir/quad/` listing (`VarReturnQuad`,
`RetQuad`, `ThrowQuad`, `JsrQuad` all exist). Repro — host probe:

```java
static int switchDefUse(int n) {
    int v;
    switch (n) { case 0: v = 10; break; case 1: v = 20; break; default: v = 30; }
    return v + 1;
}
```

Assert in `--ir` the edge copies precede the switch quad (table + lookup
variants). Guest: extend the `switchLongLoop` family with a def-in-switch
case. Gate: census identity, oracle green.

## P5: `removeUnusedVars` keep-list misses throwing twins

Current (`IRControlFlowGraph.java:142-152`, verbatim): keeps
`CallAssignQuad`, 4x `New*`, `JsrQuad` only. Missing: `ArrayAssignQuad`
(bounds/NPE), `ArrayLengthAssignQuad` (NPE), `BinaryQuad`
`IDIV/IREM/LDIV/LREM` (ArithmeticException) — while `isCallLike`
(class P6) says they throw. DCE deletes an apparently-unused but
throwing def; precise exceptions silently vanish. Trigger needs a dead
throwing def inside a try — medium-high across a corpus.

Hunk: extend the keep-list with the twins above (decide `MemLoad`/`MagicOp`
trap-capable with a comment), and share one predicate with P6 (see P12).
Repro — host: dead `arr[i]` / `arr.length` / `x/0` inside try with a handler
asserting the throw; assert the quad survives DCE in `--ir`. Guest: throw-
observation probe (add `deadThrowObserved` to `Probes` + `CASES`).

## P6: IR `isCallLike` misses `LDIV/LREM` (drift from its mirror)

IR version (`IRControlFlowGraph.java:1854-1862`, verbatim) lists 12 quad
kinds; X86 version (`X86Level2Compiler.java:445-461`, verbatim) lists the
same 12 **plus** `BinaryQuad LDIV/LREM`. So a def after an `ldiv`/`lrem`
throw is treated as always-executed by `isDefUnwrittenOnExceptionalEdge`
(`:1832-1852`, verbatim) -> handler/resume-phi reads a never-written home.
Divide-in-try is common enough to rank high.

Hunk (add `BinaryQuad`/`BinaryOperation` imports if absent):

```java
    || q instanceof ArrayAssignQuad || q instanceof ArrayStoreQuad
    || isThrowingBinary(q);
}
private static boolean isThrowingBinary(Quad q) {
    if (q instanceof BinaryQuad) {
        final BinaryOperation op = ((BinaryQuad) q).getOperation();
        return op == BinaryOperation.LDIV || op == BinaryOperation.LREM;
    }
    return false;
}
```

Same-hunk audit (document keep vs add): `IDIV/IREM` (absent both sides;
backend emits trapping `IDIV`), `CheckcastQuad` (failure path calls
`classCastFailed`, `GenericX86CodeGenerator.java:5840`), and P5's
`ArrayLengthAssignQuad`/`MemLoad`/`MagicOp` decision.
Repro — host probes (twins of `nestedCatchLong`, one per direction):

```java
static long divInTry(long a, long b) {
    long acc = 0;
    try {
        acc = acc + (a / b);          // LDIV may throw: handler must see 0
    } catch (ArithmeticException e) {
        return acc;
    }
    return acc + 100;
}
static long divAfterAdd(long a, long n) {
    long acc = 0;
    try {
        acc = acc + n;                // always executes: handler must see n
        acc = acc + (a / (n - n));    // throws
    } catch (ArithmeticException e) {
        return acc;
    }
    return -1;
}
```

Host test asserts the handler-bound SSA version per case (`--ssa`). Guest:
both to `Probes.java` + `CASES` (`divInTry(10,0)`, `divAfterAdd(10,3)`),
expect L1==L2.

## P7: `deconstructOnePhi` primaries bypass usability

Current (`IRControlFlowGraph.java:1062-1065`, verbatim):

```java
if (defBlock != null && preds.contains(defBlock)
    && !claimed.contains(defBlock)) {
    primaries.add(new PhiSource<T>(rhs, defBlock, tag));
```

A local phi at a handler-entry join claims the in-try `defBlock` as
primary, but the exceptional edge carries the **pre-try** version
(`popHandlerVersions`) — the L2-127 swap shape one level up. Handler-entry
joins are common.

Hunk: route primaries through `isUsableEdge`/`handlerFlowSet` like every
other source (fallback: demote unusable primary to ambiguous). Repro: the
`HashProbe5`/`test_keySet` shape reduced to a `PrimitiveTest` probe (null-
init + in-try redefine + try/catch fallthrough + use); assert `--ir` edge
copies (normal=in-try, handler=pre-try). Guest: existing `HashProbe5` 3/0
must hold + new probe rows.

## P8: `foldConstants2` shared-lhs re-type (Unary live, Binary dormant)

Current (`UnaryQuad.java:99-111`, verbatim): `Variable<T> lhs =
this.getLHS()` passed into `new ConstantRefAssignQuad(..., lhs, c2)`, whose
ctor (`ConstantRefAssignQuad.java:64-69`, verbatim) runs
`getLHS().setType(rhs.getType())` on the shared object. Verified live:
`UnaryQuad.generateCode :224,:245` calls `foldConstants2()` when the
operand is still a `Constant` at emission (“probably won't happen” fallback
— rare path, hence ranked below P1). `BinaryQuad.foldConstants2` (`:206`)
has **no** production caller (dormant copy of the defect).

Hunk: clone at the boundary (same clone API as P2/P9; `Variable implements
Cloneable`, subclass `clone()`s verified):

```java
Variable<T> lhs = (Variable<T>) this.getLHS().clone();  // [confirm] clone type-preservation
return new ConstantRefAssignQuad<T>(address, byteCodeAddress, basicBlock, lhs, c2);
```

Defense in depth (same commit): clone inside the two lhs-taking
`ConstantRefAssignQuad` ctors and audit all
`new VariableRefAssignQuad(block, lhs, ...)` callers. Repro: `I2L`/`L2I`/
negation of a constant feeding an `LSUB`-class op (L2-137 `NanoTime`
shape) — assert stack-homed result, no `MODE_SRS`/`Unknown operation:
LSUB`. Guest: `wideFoldSub` probe row if existing long rows don't trigger
the fallback.

## P9: deSSA `isUsableEdge` ignores always-executed defs

Current (`IRControlFlowGraph.java:1408-1424`, verbatim): handler-flow
branch tests only `!epreds.contains(defBlock)`. An always-executed in-try
def (P6's counterpart) is deemed unusable on handler flow while SSA says
the handler sees it -> misrouted resume-phi copy via
`routeCandidates:1334` / `taggedUsableEdge:1373`. Same trigger as P6 but
needs ambiguous routing on top — ranked just below it.

Hunk (schematic, confirm identifiers):

```java
if (hflow.contains(p)) {
    ...
    // A def preceding every call-like quad in defBlock DID execute on the
    // exceptional edge (L2-139): it stays usable here.
    if (epreds == null || !epreds.contains(defBlock)
        || !isDefUnwrittenOnExceptionalEdge(version, defBlock)) {
        return true;   // [confirm] exact version variable in scope
    }
    return false;
}
```

Repro/gate: P6's `divAfterAdd` `--ir` (handler edge routes post-add
version) + guest rows.

## P10: `removeDefUseChains` lhs aliasing

Current (`IRControlFlowGraph.java:188-189`, verbatim):

```java
vq.setDeadCode(true);
var.getAssignQuad().setLHS(vq.getLHS());
```

Two quads share one lhs object afterwards; any later `setType` corrupts
both (P8 mechanism). Single-use same-block coalescing is less frequent
than folding — medium-low.

Hunk: clone `vq.getLHS()` before aliasing (P8 clone API). Repro: single-use
wide-def coalescing followed by a narrow use — assert both lhs types
post-splice in `--ir`.

## P11: fall-through / ret-resume edges never explicit

`isExplicitEdge`/`retargetEdge` (`:602-653`, per sweep) skip quad-less
fall-through and `RetQuad` dynamic edges (ret→all-resumes over-approx from
`IRBasicBlockFinder.java:122`). Ambiguous copies placed in a fall-through
predecessor execute on its *other* successor too; ret→resume critical edges
never split and jsr-resume phis never get tags (`rewritePhiParams`
`:1727-1731` walks successors only). Staged plan: (1) split fall-through
critical edges carrying ambiguous copies (sink copies per-successor);
(2) ret→resume: split or document-and-pin with verifier coverage. Repro:
phi fed from a fall-through predecessor with an observing second successor
— assert per-edge placement in `--ir`.

## P12: `IRGenerator.fixType` shared-slot mutation

Per sweep (`IRGenerator.java:492-505`): `rhs.setType(...)` mutates an
object aliased to `block.getVariables()[slot]` (via
`VariableRefAssignQuad:43`, `Quad:57-58`, no rhs clone), and
`getVariables()` returns the idominator array by reference
(`IRBasicBlock:141-149`) shared into synthetic edge blocks
(`IRControlFlowGraph:561`). Every `dup*` runs it, but corruption needs
type divergence — low-medium.

Hunk: copy-on-write (fresh `Variable` written back to the slot instead of
mutating the shared object); re-check `typePhiResults`/idominator sharing.
Repro: host unit assertion on `fixType` input/output slot arrays (identity
must not be mutated).

## P13: `typePhiResults` single-wide-source upgrade

Current (`IRControlFlowGraph.java:453-486`, verbatim): `lhs.setType(found)`
upgrades to LONG/DOUBLE if **any** source is wide — the inverse
single-source-driven decision vs L2-137. Runs pre-deSSA so ordering is safe
today, but the decision itself mis-homes narrow-majority phis. Mixed-type
phis are uncommon — low-medium.

Hunk: require unanimity of live sources (all wide → wide, else narrow) +
census-diff review; keep the L2-137 restore. Repro: int/long-mixed phi
(`NanoTime`-style) — assert result type + edge-copy homes.

## P14: `pruneDeadPhis` undefined-source drop

Per sweep (`:1057-1058`): `def==null && !MethodArgument` sources are
silently `continue`d away (jsr/ret-resume, never-renamed L2-113 nulls)
instead of kept live. Low trigger.

Hunk: retain the phi (pin an `UndefinedVariable` bottom with a real dead
edge copy, per the `:1028-1050` bottom idiom) + log the site. Repro:
jsr/ret-resume value merge and exceptional merge of a never-renamed var —
assert phi retained with defined lowering.

## P15: loop classification still mixes flows

`routeCandidates:1346,1356` `inLoop`/`internal` and the L2-131 assertion
loop (`:1225`, full-edge `blockDominates:934`) still use full-edge
reachability; L2-127 fixed only `isUsableEdge`. Needs handler-into-loop
flow — low.

Hunk: thread `blockReachesNormal` into the classification; keep the
over-approx only for documented ret edges. Repro: handler fallthrough into
a loop flipping entry/back-edge — assert classification vs normal-flow
truth in dumps.

## P16: switch `retarget` stale `defaultAddress` (verification only)

`LookupswitchQuad:74,84` / `TableswitchQuad:73,80` rewrite `targetBlocks[]`
but not the `defaultAddress` ints. Task: read `X86CodeGenerator` switch
emission (`~6102-6289`, always ends `JMP default`); block-links-followed →
document-only (cite L2-083 `JsrQuad:70` pattern); int-read → hunk updating
`retarget`. Repro only in the latter case (default arm post-split).

## P17: `RetQuad` rename carve-out (doc + test, no code)

`RetQuad:47` live refs are renamed while twin `JsrQuad:95` returns null —
single-rename/multiple-resume over-approx (documented; verifier also skips
`Ret`). No hunk: add `SSAVerifier` documentation + `L2PipelineTest`
pinning test over a jsr/ret method; revisit on a real ret-local
miscompile.

## P18: inter-block over-pop (investigation, no hunk yet)

`popHandlerVersions:1794` pops any `defBlock ∈ exPreds`, even a
multi-block-try def dominating the throwing block (did execute); L2-139
fixed intra-block order only. Instrument-first: log popped
`(var, defBlock, throwingBlock)` triples over the oracle corpus; promote to
a `placeHandlerPhis`-style fix only on a hit. Repro sketch: two-block try
(`if` inside try, def before branch, throw after), handler reads the def.

## P19 (follow-up): shared predicate + leftover cleanup

- Single shared `Quads.isCallLike` (or equivalent) used by
  `IRControlFlowGraph`, `X86Level2Compiler.forcedSpills`, and the
  `L2PipelineTest:709` audit list — kills P6-class drift permanently.
  Include `IDIV/IREM`/`Checkcast`/`ArrayLength` decisions from P5/P6.
- Delete `handlerTops/handlerPres` leftovers (with P0-1).
- Reconcile `hasPhiFor:1924` break-on-first-non-phi vs `:759` full scan
  (currently saved by phi-prepend convention only).

## Execution order

P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8 → P9 → P10 → P11 → P12 → P13 →
P14 → P15 → P16 → P17 → P18 → P19. One item per commit; each gates on
focused suite → full suite → census FAILED-list review → guest oracle +
mauve where marked.

## Landed since audit (2026-09-22 evening, ahead of the queue)

- **L2-140** (I2B reg-reg wrong-register + silent AH/CH/DH/BH aliasing):
  the concrete instance of the "untested shape" gap behind P1–P3 — the
  emitter mode matrix only ever used ECX/EBX. Fixed + hotswapped live;
  StringTest 150/0. Regression test: `testI2BUnsuitableRegisters`.
- **L2-141** (narrow array load/store vs pushed ECX): the concrete
  instance of P4/P11 (ECX-scratch collisions). Fixed load + store twins +
  hotswapped live; decode 25/1 == noforce, force|79 green. Regression
  test: `baloadEcxLoop` + `testNarrowLoadEcxResult`. Full record:
  `ORACLE-RESULTS-2026-09-21.md` § "FIXED (2026-09-22 evening)".

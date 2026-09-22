# L2 execution oracle — differential results (2026-09-21, branch L2-Nex-N2.5-Pro @ 7bb7fdf62)

ISO: `local/mk-ox-iso.sh` (GRUB `default 1` = all plugins, `timeout 5`,
hands-off). Guest: VirtualBox JNode VM, serial via
`.opencode/skills/jnode-serial/scripts/serial_cmd.py`.
Host ref: `/tmp/l2oracle-ref/out-host.txt` (OracleDriver noforce on host JDK).

## Run
```
javac -d /jnode/tmp/ox /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java
classpath --add file:/jnode/tmp/ox
java OracleDriver out-l2.txt        # force|79
cat out-l2.txt > /tmp/out-l2.txt
tests/l2oracle/compare.sh <host-ref> /tmp/out-l2.txt
```

## Result: force|79, 79 methods force-compiled with L2 and executed.

## Diffs (host vs L2) — 5 rows

| method | args | host | L2 | verdict |
|--------|------|------|----|---------|
| div_iii | -2147483648,-1 | I:80000000 | EX:ArithmeticException | KNOWN pre-existing (int MIN/-1 -> #DE) |
| nestedCatchLong | 5 | J:69 | J:118200000000 | **L2 bug** |
| nestedCatchLong | -3 | J:60 | J:3c1724a800000000 | **L2 bug** |
| lookupLongTry | 0 | J:2 | J:118200000000 | **L2 bug** |
| lookupLongTry | 2 | J:8 | J:118200000002 | **L2 bug** |

## Pattern (narrowed 2026-09-21)
Every failing case is a LONG return through an exception handler or a
switch. Low half wrong/zero; high half a stale pointer
(0x11820000, 0x3c1724a8). lookupLongTry(2) returns low=2 (the switch
discriminant n) instead of 8 (acc) -- the post-switch/exception phi for
acc reads the wrong source.

This is the exception-flow / switch phi-copy approximation
(ANCHOR-L2-136, S6.4): copies on tagged normal edges into handler blocks do
not execute on the exceptional dispatch, so a long phi merging a switch
value and an exception value loses a source. Proven differentially.

## Narrowing by shape (2026-09-21)
Green vs failing shapes isolate the trigger:

| shape | long? | catch? | finally? | switch? | result |
|-------|-------|--------|----------|---------|--------|
| switchLongLoop | yes | no | no | table | GREEN |
| loopLongTryFinally | yes | no | yes | no | GREEN |
| finallyThrowsLong | yes | no | yes | no | GREEN |
| loopSwitchLong | yes | no | no | labelled | GREEN |
| nestedCatchLong | yes | YES | no | no | FAIL |
| lookupLongTry | yes | YES | no | lookup | FAIL |

=> Trigger is LONG + CATCH handler specifically. finally-only and switch
do not fail. So the defect is in the catch-handler path (exceptional
dispatch), not in finally, not in switch, not in plain long arithmetic.

## Host vs guest compile identically
The host test helper (L2PipelineTest.compileMethod) and production
doCompile (X86Level2Compiler) share the same pipeline: IRGenerator ->
constructAndOptimize -> optimizeOnce -> deSSAAndFixup -> allocateRanges
-> generateCode, all over the same X86CodeGenerator. Host disassembly of
lookupLongTry shows correct wide moves (STACK/STACK push/pop pairs for the
synthetic-edge copies, e.g. qb_17: push [ebp-20]; push [ebp-24];
pop [ebp-48]; pop [ebp-44]). So the compiled bytes match; the corruption
is at RUNTIME in the guest.

Likely culprit: the catch handler's stack frame. ex_handler does
`lea esp,[ebp-56]` then `push eax` and jumps into bci_70, where the long
return value l1_12 lives at [ebp-56]/[ebp-52]. The handler-relative
addressing of a long that straddles the handler's restored ESP is the
suspected site (the S6.4 exceptional-dispatch approximation, which does
not actually execute the tagged normal-edge copies).

## FIX (2026-09-21): ANCHOR-L2-138 synthetic-edge terminators
Root cause: splitCriticalEdges created a synthetic single-edge block with
`edge.getSuccessors().add(join)` but NO terminator quad, so the linear
layout fell through to whatever followed it instead of reaching the merge.
Guest disassembly of lookupLongTry showed it directly: the case blocks
jump to their synthetic edge blocks, which then fall through past bci_67
(where l1_12 = l1_7 and eax/edx are loaded) into the footer, so the
normal-path long return reads uninitialized registers -> low half = the
switch discriminant, high half = a stale pointer (0x11820000).
switchLongLoop (no catch => no critical edge split) is unaffected and
stayed green, which is what isolated it.

Fix: add `UnconditionalBranchQuad` to each synthetic edge block (wired
BEFORE the quad, since BranchQuad's ctor resolves the target against the
block's successors). 236 tests green.

Result after fix (force|79): lookupLongTry fully fixed, nestedCatchLong(5)
fixed, nestedCatchLong(-3) improved from a garbled pointer to off-by-3
(0x63 vs 0x60). div_iii MIN/-1 remains the known pre-existing #DE mapping.

## Batch v5 (2026-09-22): 65 new `java.*` mauve testlets, v1-v5

Corpus extension beyond v1-v4 (88 testlets): 1,766 `gnu.testlet.java.*`
Testlet classes exist in `/tmp/jars/mauve`; 65 compiler-relevant ones
(scalar/collection/text/reflect, no GUI/net/io) selected, verified via
`javap` to implement `gnu.testlet.Testlet` with no same-package sibling
CONSTANT_Class refs. Staged on the CD via `local/mk-ox-iso.sh` (added
`mauve-subset-v5.txt` + v5 chunk lists g1-g5). Guest reads classes from
`/devices/sg0/ox/mauve`; list files go to RAMFS `/jnode/tmp/mauve/`
(JFAT corrupts list writes — verified).

Run per chunk: cold boot (fresh heap + FS cache), `java MauveDriver force
/jnode/tmp/mauve/gN.txt` vs `noforce` on a separate cold boot. Driver
forces the whole testlet class with L2 (`VmType.compileRuntime(0,true)`);
boot image is L1A (`java.lang.String` etc. run as L1A), so a force-only
regression is an L2 bug in the testlet's own compiled code.

### g1 (12 testlets): force vs noforce

| testlet | noforce | force | verdict |
|---------|---------|-------|---------|
| MathTest | 148/0 | 148/0 | green |
| StrictMath.cbrt | 24/0 | 24/0 | green |
| IntegerTest | 61/0 | 61/0 | green |
| DoubleTest | 107/1 | 108/0 | force-only FIX (flaky `test_remainder 11`) |
| Double.toHexString | 8/0 | 8/0 | green |
| FloatTest | 74/0 | 74/0 | green |
| **StringTest** | 150/0 | 148/2 | **L2 REGRESSION** (`test_Basics` #14, #17) |
| **String.decode** | 25/1 | 19/7 | **L2 REGRESSION** (6 force-only fails) |
| String.equals | 6/0 | 6/0 | green |
| String.replaceAll | 1/0 | 1/0 | green |
| String.startsWith | 8/0 | 8/0 | green |
| String.to | 2/0 | 2/0 | green |

Both regressions land in the **byte[] String constructor / decode path**:
`String.decode` uses `new String(byte[], String)` + `String.getBytes(String)`;
`StringTest` #14/#17 are `new String(byte[], int, int)` checks. The L2-
compiled testlet passes wrong bytes/offsets into the L1A constructor, or
mis-handles the byte[] load (old `String(byte[],int,int)` interprets bytes
as unsigned 0-255). Not yet root-caused.

### Infrastructure fixes this batch

- `local/mk-ox-iso.sh`: the driver was compiled straight into `$STAGE/ox/mauve`
  then `cp`'d onto itself — a "same file" error that aborts the script under
  `set -e`, so **the CD shipped with no `gnu/` tree at all** (verified via
  `isoinfo`: 0 mauve entries). Now compiles to `/tmp/mauve-build` and copies.
- `local/run_v5.sh` created: per-chunk cold-boot runner (CD classes + RAMFS
  list). The host-side `mkdir -p /jnode/tmp/mauve` was a no-op printing
  "Permission denied"; guest dir is made via serial_cmd.
- **JFAT list corruption**: pushing the list file to `/devices/hdb1` is
  unreliable; RAMFS `/jnode/tmp/mauve` works (verified byte-exact).
- **Heap discipline**: forcing whole testlet classes (incl. static strings)
  on a warm guest OOMs; cold-boot per chunk is required.
- **noforce on a warm guest after force** crashes the kernel
  (`ArrayIndexOutOfBoundsException` in `VmStaticsAllocator.alloc`, then
  `NoClassDefFoundError: org.apache.log4j.spi.VectorWriter` in the L1A
  compile thread) — likely heap exhaustion. Always cold-boot noforce.

## Residual (2026-09-21): nestedCatchLong(-3) — FIXED by L2-139
Host 0x60 (96), guest 0x63 (99). 99 = 0 - 1 + 100, i.e. the guest behaves
as if the inner try's `acc = acc + (long)n` did not take effect before the
inner catch ran (acc stayed 0, then -1, then +100).

Guest disassembly of nestedCatchLong(-3) traces it exactly: the inner
catch handler (ex_handler1 -> bci_36) does
    mov dword[ebp-56],0x00000000   ; acc = 0  <-- pre-try version
    mov dword[ebp-52],0x00000000
instead of reading the post-inner-try-body phi value (-3, which lives at
[ebp-40]/[ebp-36] after qb_2). So the nested handler reads the OUTER try's
pre-try version rather than the INNER try's post-body version.

This is the ANCHOR-L2-129 handler-entry versioning approximation (S6.4),
now specifically for NESTED handlers: the inner catch's acc phi source on
the exceptional dispatch is the entry version, not the post-inner-body
version. Same bug class as the lookupLongTry fall-through, but deeper
(nested handler scopes).

### Fix (L2-139): isDefUnwrittenOnExceptionalEdge
Only pop a def that is UNWRITTEN on the exceptional edge, i.e. a
call-like (potentially-throwing) instruction could have fired before it in
the def block. A def that precedes every call-like quad in its block always
executes and must be kept on the stack for the handler to read.

### FINAL RESULT (force|79): FULLY GREEN
The only remaining diff is div_iii MIN/-1 -> EX, the known pre-existing
x86 #DE mapping (documented as NOT an L2 bug). All 79 force-compiled
methods match the host reference.

## IN PROGRESS (2026-09-22): v5 StringTest / String.decode regression root-cause

### Status
- Branch: `L2-Nex-N2.5-Pro` @ 053faba0f (force|79 fully green; v5 batch
  is the only new delta since the 2026-09-21 run).
- v5 g1 (12 testlets) re-run: StringTest 150/0 noforce -> 148/2 force
  (**L2 REGRESSION**, checks #14 and #17 in `test_Basics`), String.decode
  25/1 -> 19/7 (6 force-only fails). All other g1 testlets green.
- **No code change made yet.** Root cause identified (see DEFINITIVE MECHANISM):
  an L2 register-allocation/location-tracking bug in the L2-compiled
  `test_Basics` itself (the check computation, not `java.lang.String`).
  `String.decode`'s 6 force-only fails are still uninvestigated; it likely
  shares the same root cause (its `String(byte[],String)` + `getBytes(String)`
  family uses the same `& 0xff` / `I2B` decode loop), but this is not yet
  confirmed on the guest.
- Analysis artifacts on disk:
  - `/tmp/strtest-ir.txt` — IR of `test_Basics` (422 lines).
  - `/tmp/strtest-l2.txt` — L2 disassembly of `test_Basics` (1750 lines).
  - `/tmp/cls/java/lang/String.java` — extracted classlib `String` source.
  - `gnu.testlet.java.lang.String.StringTest` decompiled via
    `javap -c -p` from `/tmp/jars/mauve`.
  - `/jnode/tmp/probe/StrProbe.java` + `ForceRun.java` — force-compiled
    guest reproducer (StrProbe noforce AND force both pass the hibyte
    constructors; the testlet-shaped version reproduces the ECX/ESI
    mismatch).

### Narrowing (SUPERSEDED — see DEFINITIVE MECHANISM below)
Both failing checks land in the `byte[]` String constructor path:
- #14: `new String(byte[], int hibyte)` with hibyte=10, byte[] =
  {100,97,110,99,105,110,103} -> expects `charAt(1)` == 'a' (0x0a|0x61).
- #17: `new String(byte[], int, int, int)` with hibyte=10, off=1, len=1
  -> same 'a' expectation.
`String.decode` uses the same `String(byte[],String)` + `String.getBytes(String)`
family, so a single shared defect covers both regressions.

### DEFINITIVE MECHANISM (2026-09-22 13:45, confirmed by guest probe)
The hibyte constructors themselves are CORRECT — verified force-compiled on
the guest (`/jnode/tmp/probe/StrProbe.java` + `ForceRun.java`):
`new String(b,10).charAt(1)` == 0xa61 and `new String(b,10,1,1).charAt(0)` ==
0xa61 both PASS under L2. The regression is a **register-location mismatch in
the L2-compiled testlet's own check computation**, NOT in `java.lang.String`.
The testlet computes `c = s.charAt(1)` ONCE (stored at `[ebp-116]`) and reuses
it for BOTH #14 and #17, recomputing `c & 0xff00`, `>>8`, `c & 0xff`, `I2B`
for each. In `/tmp/strtest-l2.txt`:

- **#14 (qb_136-139, consistent):**
  `mov esi,[ebp-116]; and esi,0x000000FF`  (s20_31&0xff -> ESI = 0x61 = 97)
  `movsx ecx,byte esi`                     (I2B reads ESI = 97)  -> PASS
- **#17 (qb_162-169, BROKEN):**
  `mov ecx,[ebp-116]; and ecx,0x000000FF`  (s20_49 -> ECX)
  `movsx eax,byte esi`                     (I2B reads ESI, NOT ECX)
  `mov esi,dword eax`

ESI is NOT rewritten between #14's block and qb_168, so at qb_168 it still
holds #14's `s20_31 & 0xff` (=97). But the intervening `new String(...)`
calls (qb_148, qb_150, qb_152, ... -- all `[edi+2568]`/`[edi+5300]`/
`[edi+5356]` allocators) **clobber ESI as a caller-saved register**, so by
qb_168 `movsx eax,byte esi` sign-extends garbage -> `lo != 97` -> #17 FAILS.

So: the SAME SSA variable `s20_49 = s20_31 & 255` (IR line 303) is in **ECX
at its def** (the `and`, qb_167) but in **ESI at its use** (the `I2B`, qb_168),
with no spill/fill between them. The linear-scan allocator handed two
different physical registers to one variable across its interval (which
spans the call sites), and the codegen emitted no reload. This is an L2
**register-allocation / location-tracking bug**, not a String bug.

### Reproducer (force-compiled, guest)
`/jnode/tmp/probe/StrProbe.java` + `ForceRun.java` (VmType.compileRuntime
0,true). Minimal `new String(b,10)` + `new String(b,10,1,1)` passes; the
full testlet-shaped version (same charAt(1) reused across intervening
`new String` calls in try/catch) reproduces the ECX/ESI mismatch.

### Next move
1. Host reproducer (core is built at `core/build/classes`): construct the
   exact IR -- a spilled int var whose interval spans a call, used by an
   `and` then an `I2B` -- drive it through `X86Level2Compiler` +
   `LinearScanAllocator` + `GenericX86CodeGenerator`, and assert the I2B's
   operand register == the `and`'s LHS register. This is the regression
   test that would have caught it at compile time.
2. Root cause is in how a variable's Location is resolved at a use that
   lies past a call site where its register was clobbered. Prime suspects:
   the forced-spill path (LinearScanAllocator.spillRange, L2-089/090/134),
   the deSSA edge copies (IRControlFlowGraph.deconstrucSSA /
   rewritePhiParams), and the exceptional-edge version handling (L2-137/
   138/139 -- the commit area this delta sits in). Add a compile-time
   assertion: for every Variable, its location at every use must equal its
   location at its def, or be reached through an explicit spill/fill quad.
3. Fix, rebuild `cd-x86-lite`, re-run g1 cold-boot, update this file.

## FIXED (2026-09-22 evening): L2-140 (I2B) + L2-141 (narrow array ECX)

Two backend bugs, one v5 batch. Both fixed, hotswapped live, guest-proven.
(The report's "ECX at def / ESI at use, no spill/fill" framing was the text-
disasm view; the machine-level causes are below.)

### L2-140: reg-reg I2B reads the wrong register (StringTest #14 + #17)
`GenericX86CodeGenerator.java:653` reg-reg `I2B` else-branch (lhs not
8-bit-suitable) emitted `writeMOVSX(SR1, lhsGpr, ...)` — the DESTINATION
instead of `rhsReg` (the SOURCE). Worse, the 3-arg reg-reg movsx with a
non-8-bit-suitable source (ESI/EDI/EBP have no byte alias in 32-bit mode)
silently encodes an AH/CH/DH/BH read (`write2bOpcodeModRR` checks dst
suitability only), so BOTH #14 (`movsx ecx,byte esi` = `movsx ecx,dh`=0)
and #17 failed. Fix: check source suitability; suitable source goes via
SR1, unsuitable source bounces via PUSH/SR1,[ESP]/ADD (new shapes only —
suitable/suitable still direct). Sibling sites audited: mem/reg I2B forms
(:744/:836/:951) read the right operand; I2C/I2S use WORDSIZE (16-bit
aliases always exist); no 3-arg BITS8 MOVZX in L2. Regression test:
`L2ModeMatrixTest.testI2BUnsuitableRegisters` (the matrix only ever used
ECX/EBX — both suitable — which is why this escaped).

### L2-141: narrow array load/store vs the pushed ECX (decode #1-6, StrProbe)
`loadWideOrNarrowArray` preserves live ECX with push/pop around its index
temp, then wrote a REGISTER-homed result straight into ECX — the POP
restored the stale value over it (guest BALOAD loop: `sum 9 instead of
294`, each iteration adding the pushed length 3). CALOAD twin broke
decode's `caload;i2b;bastore` loop the same way. Fix: ECX-homed results
stage via SR1 (`MOVSX EAX,[EDX]; POP ECX; MOV ECX,EAX`), other homes
unchanged. Store-side twin fixed too: an ECX-homed stored VALUE was read
after ECX became the index temp — now read from the PUSH slot (`MOV
SR1,[ESP+0]`, no pushes intervene on the narrow path) for ECX-homed values
only; other homes keep the direct shape. Regression test:
`PrimitiveTest.baloadEcxLoop` + `L2PipelineTest.testNarrowLoadEcxResult`
(asserts the killer `movsx/zx ecx,[edx]`+`pop ecx` adjacency is gone).

### Verification (all green)
- Host: T0 17/17, T3 15/15 (incl. new I2B test), T1 28 tests with only the
  known `testSSAVerifierCorpus`/finallyThrowsLong anomaly (pre-existing,
  untouched by this fix).
- Census: FAILED 169 methods identical, OTHER 29, MAGIC 7; OK +1 (the new
  probe). SSATAG 0/92 (L2-135/136 tag-preferring effect, expected).
- Guest (both fixes hotswapped live, no reboot): StrProbe3
  checkPath/byteLoop(294)/stringPlain/stringSub ALL PASS; StringTest
  **150/0** (was 148/2); decode **25/1 == noforce** (the 6 force-only
  fails gone; #25 fails identically under L1A = library cancel, not L2);
  full oracle **force|79 green** (sole diff the known MIN/-1 #DE).
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

## Files
- `tests/l2oracle/mauve-L2-forced.txt` — guest L2 output
- `tests/l2oracle/mauve-L1A-baseline.txt` — host reference
- `tests/l2oracle/Probes.java` — probes incl. ANCHOR-L2-137 complex-shape set
- `tests/l2oracle/OracleDriver.java` — driver (disasm mode useful)
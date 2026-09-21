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

## Pattern
Every failing case is a **long return through an exception handler or a
switch**. The low half is often 0 or wrong; the high half is a stale
pointer (0x11820000, 0x3c1724a8). lookupLongTry(2): L2 low half = 2 (the
switch discriminant n) instead of 8 (acc) -- the post-switch/exception phi
for acc is reading the wrong source.

This is the exception-flow / switch phi-copy approximation
(ANCHOR-L2-136, S6.4): copies on tagged normal edges into handler blocks do
not execute on the exceptional dispatch, so a long phi that merges a
switch value and an exception value loses a source. Proven differentially.

## Green under L2 (subset)
add_iii, sub_iii, mul_iii, rem_iii, shl_iii, add_jjj, mul_jjj, div_jjj,
rem_jjj, add_ddd, mul_ddd, id_d, ret15_d, addCC_d, dstoreVar_d, tryCatchDiv,
tryCatchOob, tryFinally, baIOB, caIOB, saIOB, blnArr, castStr, instStr,
swTable, swLookup, swBig, multiArr, syncThrow, istoreVar_aiii, dld_d,
newDlen_d, sumA_aji, virt_base, virt_sub, virt_fin, iface_add, all w*
word ops, wtol_j, altoi_ji, aadd_iii, loopLongTryFinally, switchLongLoop,
finallyThrowsLong, loopSwitchLong, sync_add (L1 fallback).

## Files
- `tests/l2oracle/mauve-L2-forced.txt` — guest L2 output
- `tests/l2oracle/mauve-L1A-baseline.txt` — host reference
- `tests/l2oracle/Probes.java` — probes incl. ANCHOR-L2-137 complex-shape set
- `tests/l2oracle/OracleDriver.java` — driver (disasm mode useful)
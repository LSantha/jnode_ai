# L2 regression testing — curated corpus for the tight bugfix loop

Purpose: the single reliable record of which L2 regression machinery an
agent may trust in a tight bugfix loop, what "green" means for each piece,
and how to extend the corpus. Every entry below was validated on
2026-09-22 (see §7); anything not validated is marked as such.

Stage-1 goal: **L2 production-ready = bootable 32-bit boot image**
(`sh build.sh -Djnode.compiler=L2 cd-x86-lite` boots to a working shell,
L1A-control parity). Until then, no L2 change lands without the gates in §6.

## 1. The tight loop (host, minutes, no VM)

Run after every backend edit, in this order. Java 1.6, from repo root:

```bash
# T0 foundations (pure Java, ~seconds) — must be 17/17
java -cp core/build/testclasses:core/build/classes:local/classlib:core/lib/junit-4.5.jar \
  org.junit.runner.JUnitCore org.jnode.vm.compiler.ir.L2HostTest
# T3 emitter modes (~seconds) — must be 14/14
java -Djnode.root=. -cp core/build/testclasses:core/build/classes:local/classlib:core/lib/junit-4.5.jar \
  org.junit.runner.JUnitCore org.jnode.vm.compiler.ir.L2ModeMatrixTest
```

Notes:

- `local/classlib` on the classpath is load-bearing (`MagicPermission`
  lives only there); without it T3 fails with `ClassNotFoundException`
  before running a single test.
- `core/build/testclasses` must be rebuilt after test-source edits
  (stale classes silently test old code). Rebuild via the `assemble`
  path, never by hand-copying.
- T1 (`L2PipelineTest`, 27 tests) is run the same way with
  `distr/build/classes` added to `-cp`, but see §7 anomaly: currently
  26/27 with a deterministic `testSSAVerifierCorpus` failure on
  `finallyThrowsLong`. Until triaged, T1 is run-and-inspect, not a gate.

## 2. Canonical gates (slower, authoritative)

| Gate | Command | Green definition |
|---|---|---|
| Full unit gate | `sh build.sh -f core/build-tests.xml all-junit` | `CoreTestSuite` green incl. all 58 L2 tests (27+14+17) |
| Census gate | `java … L2Census core/build/classes [out]` (see `L2Census` javadoc) | `classes=1402 OK=11343 SKIP=1522 MAGIC=7(all deferred-MMTk) HANDLERS=450 FAIL_64=0`; **FAILED 169-method list byte-identical** to baseline; `SSATAG 144/646` |
| textui/shell/fs/net/distr census | same harness per subproject | `MAGIC=0 FAIL_64=0` everywhere outside core (gui census deadlocks the host loader — harness issue, out of scope) |

Census rule: any FAILED-list delta must be reviewed line-by-line (new OK
entries from new probes are fine; any new failure mode blocks the change).
`OTHER=29` is harness noise (`NoClassDefFoundError` log4j/mmtk/JDWP/junit).

## 3. Guest loops (VBox, reliable-only)

### 3a. Mauve via the `gnu.mauve` plugin (preferred, validated 2026-09-22)

The user's hypothesis is confirmed: mauve ships on the CD, no per-testlet
staging needed. `all/conf/tests-plugin-list.xml` includes `gnu.mauve` +
`gnu.mauve.plugin`; the ISO's `tests.jgz` contains `gnu.mauve_0.2.9-dev.jar`
(verified host-side: **4268** `gnu/testlet` classes incl. `StringTest`,
`decode`, `TestHarness`, `Testlet`). `full.jgz`/`default.jgz` contain no
mauve. GRUB entry `JNode tests (all plugins + tests)` = index 4 exists in
the ISO menu (active via the `local/l2oracle/conf-x86` overlay used by
`mk-ox-iso.sh`; still commented out in tracked
`all/conf/x86/menu-cdrom.lst` — upstreaming it is a separate decision).

Preferred flow (validated live, see §7):

```bash
# boot the tests entry (hands-off needs default 4; stock ISO default is 1)
cd /devices/sg0/ox/mauve   # only MauveDriver*.class staged here (~9KB)
# list file in RAMFS (small --write is fine):
echo 'gnu.testlet.java.lang.String.StringTest' | serial_cmd.py --write /jnode/tmp/mauve/g.txt
java MauveDriver noforce /jnode/tmp/mauve/g.txt   # L1A baseline
java MauveDriver force /jnode/tmp/mauve/g.txt     # L2 differential
```

Also validated: the bundled `mauve-single <class>` shell alias runs a
testlet straight from the plugin (no driver at all; `BooleanTest` 12/12).
`Class.forName` inside `MauveDriver` resolves plugin testlets with no
`classpath --add` (CD class loading via `classpath --add file:/devices/…`
does NOT work; `cd` into the driver dir does).

What the plugin path eliminates: per-testlet `cp --parents` staging,
`$`-inner-class hunts, sibling `CONSTANT_Class` audits, the
`Proxy.class` vs `Proxy/` ISO9660 collision workaround, and `isoinfo`
extent checks for testlet content. Subset `.txt` files stay as pure name
lists. CD staging (`ox/mauve/`) remains as fallback for default/full
entries, which lack the plugin.

Open work item: hands-off tests boots need a `default 4` remaster (done
once as scratch `/tmp/jnode-tests.iso`, since removed) — propose a
`tests-default` variant in `mk-ox-iso.sh`; until then, boot entry 4 via a
throwaway remaster of the current ISO (extract with `xorriso -osirrox`,
drop `rr_moved`/`boot.catalog`, edit `boot/grub/menu.lst`, `mkisofs -R
-J -b boot/grub/eltorito.s2 -no-emul-boot -boot-load-size 4
-boot-info-table`), never by editing the attached ISO in place.

### 3b. Execution oracle (Probes + OracleDriver + compare.sh)
`OracleDriver.java` (126 CASES rows) + `compare.sh`. Status: **`force|79`
fully green**; only accepted diff is `div MIN/-1 → EX` (x86 `#DE`).
Known non-bugs (never file): `parseDouble` 1-ULP, `signExtend→toLong`,
`zeroExtend→rsha`, unsigned `Word.LT` (use `Offset.sLT/sLE`).

Mauve differential: see §3a (plugin path, preferred). `MauveDriver
force|noforce <list>` + `mauve_diff.sh` (exit 0 = no regressions).
Reliable corpora (name lists only — no staging):

| Subset | Testlets | Status |
|---|---|---|
| v1 | 20 | green |
| v2 | 52 | green |
| v3 (e1/e2/e3) | 17 | green |
| v4 (f1/f2/f3) | 19 runnable, 1 quarantined (`ProcessBuilder.simple` kills the driver, GH#649) | green modulo env-identical cancels |
| v5 (g1–g5) | 63 in file | **NOT reliable yet**: g1 `StringTest` 148/2 + `String.decode` 19/7 open (ECX/ESI mismatch under analysis) |

Bisection probes (all validated shapes, reuse before inventing):
`Run1` (single-method force), `HashProbe5` (keySet SSA shape, must hold
3/0), `ConsProbe` (reflect shapes), `MauveBug` (134 `line` checks),
`MiniProbes/MiniRun` (9-probe store matrix), `jsr/` (`force|1`,
`loopdone|42`).

Guest-loop reliability rules (VBox is shared; violations poison results):

1. **Ownership check first**: `VBoxManage showvminfo JNode` — if `running`
   under another session, do NOT poweroff/start/attach. Coordinate.
2. One pipe client at a time: `serial_cmd.py` mux OR legacy agent, never
   both; never `pkill -f` with a self-matching pattern.
3. Cold boot after every ISO rebuild; snapshot-restore only on unchanged
   ISOs (`isoinfo -R -x` must show the new files before booting).
4. RAMFS (`/jnode/tmp/...`) for lists/results; JFAT corrupts list writes.
5. `mkdir -p /tmp/oracle` on the host before any `compare.sh`, else silent
   false-PASS. Cold-boot per mauve chunk (warm-guest heap OOMs / statics
   crashes).
6. Capture serial stdout for must-survive results (outFile is best-effort,
   GH#649).

## 4. Hotswap loop — no reboot for body-only compiler changes (validated 2026-09-22)

The L2 backend runs from the boot image, but `VmVirtualMachine.redefineClass`
(`JDIVirtualMachine.java:589`: decode fresh `VmType`, copy new **bytecode**
per method, `resetOptLevel()` + `recompile()`) is fully implemented, and
`sh build.sh hotswap` speaks JDI to it. Proven live: touched (content-
identical) `GenericX86CodeGenerator` → ant recompiled → hotswap pushed
`GenericX86CodeGenerator` + `$1` to the running VM (`BUILD SUCCESSFUL`),
guest survived, and the redefined (L1A-compiled, same logic) backend then
force-compiled `one add_iii` bit-matching the host ref. **Boot-image
classes ARE hotswappable**; only schema changes (add/remove methods or
fields, layout changes) are unsupported.

Prerequisites: VM networked (`dhcp eth-pci(0,3,0)` is simplest; static
`ifconfig … .10` + `route` also works; bridged NIC required for LANCE),
JDWP listener (`debug -p 2000`), `jnode.debugger.host/port` (already in
tracked `jnode.properties`: 192.168.1.10:2000 — no local file needed).

```bash
sh build.sh hotswap   # (~2 min for compile; push itself is seconds)
```

Then rerun probes on the live guest — no ISO rebuild, no reboot.

Hard-won constraints (paid for live):

1. **Single JDWP connection**: stop every `jdb`/`jdb_agent` session BEFORE
   hotswap. A concurrent session wedges the attach in `socketRead`
   handshake with zero output (jstack showed `HotSwapHelper.connect`
   parked; had to `kill` the ant job and restart the listener).
2. **Never pipe through `tail`**: run in background with a log file
   (`nohup sh build.sh hotswap > /tmp/hotswap.log 2>&1 &`) and poll —
   `assemble-projects` prints in bursts and a pipe looks exactly like a
   hang (this caused a false "blocked" abort).
3. Always `debug --stop` + `debug -p 2000` for a fresh listener after any
   stuck attach before retrying.

## 5. Diagnostic-only tools (never gates)

`local/l2boot-tools/`: `BinDump <cls> <mth> <out> resolve [sig]`
(**always pass `resolve`** — unresolved host compiles lack class-init
checks and never match image code), `L1aDump`, `XTableDump`, `TIBDump`,
`BulkDump/BulkMatch`, `Rep`, `SeqDump`, `CensusDump`, `Probe`,
`local/kdb_eip.sh`, `diag.patch`, qemu/gdb scripts. Methodmap:
`-Djnode.dump.methodmap=true` → `[methodmap]` EIP→method map for KDB logs.

## 6. Landing gates (every L2 change)

Inner loop: edit → host §1 → `sh build.sh hotswap` (§4) → rerun probes
live. Land only after:

1. §1 host loop green (T1: inspected-green per §7 until anomaly clears).
2. §2 unit + census gates green (FAILED-list identity reviewed).
3. Guest oracle `force|79` + mauve v1–v4 `rc=0`, plugin path (§3a,
   tests GRUB entry) preferred; CD-staging fallback only on default/full
   entries. Cold-booted, content-verified ISO built via `local/mk-ox-iso.sh`.
4. No new stderr/diagnostic prints in production code; no `local/`
   dependence in tracked files.

## 7. Extension protocol (how the corpus grows)

1. Host: add probe method to `PrimitiveTest` + assertion to the matching
   suite (`L2PipelineTest` for pipeline shapes, `L2ModeMatrixTest` for
   emitter modes, verifier corpus via `testSSAVerifierCorpus`). One
   behavior per probe; name it after the bug (`twoCatches` pattern).
2. Guest: add static to `Probes.java` + `{name, args}` row to
   `OracleDriver.CASES` (no objects in signatures); or a mauve testlet name
   to the matching subset file (plugin path needs no staging and no
   sibling audit; the CD-staging fallback still needs the `javap -v`
   `CONSTANT_Class` check first).
3. Record here: row counts, new expected-failure (if any), and the fix
   commit that turns it green. Quarantine by subset-file comment + reason,
   never by silent deletion.
4. Keep `tests/l2oracle/AGENTS.md` (flows) and this file (corpus status)
   in sync; per-machine notes stay in `local/` (gitignored).

## 8. Validation log (2026-09-22, curator run)

- `L2HostTest`: **OK 17/17** via §1 command. `L2ModeMatrixTest`: **OK
  14/14** (needs `local/classlib` — verified load-bearing).
- `L2PipelineTest` via direct `java` (+`distr/build/classes`):
  **26/27**, deterministic `testSSAVerifierCorpus` failure
  (`finallyThrowsLong`: post-deSSA read of `s4_8` flagged unwritten-on-path;
  identical with `local/classlib` and `all/lib/classlib.jar`).
  Suspect: verifier false positive on throw-from-finally, or stale
  11:30 build outputs — **open, triage before T1 gates again**.
- Scripts `compare.sh`, `mauve_diff.sh`, `run_oracle.sh`, `run_v5.sh`,
  `run_chunk.sh`, `run_mauve.sh`, `mk-ox-iso.sh`: `bash -n` clean.
- Counts verified by grep: `@Test` 27/14/18(raw, 17 effective);
  `PrimitiveTest` 139 `public static` (138 probes + `main`); `Probes` 72
  statics; `CASES` 126 rows; subsets 20/52/17/19/63.
- VBox: `VBoxManage` present, `JNode` VM **running under another session
  — guest loop not executed** (would disturb it); reliability rules in §3
  derived from the documented loop + live state.

## 9. Live guest validation (2026-09-22, VBox handed over for this run)

Vehicle: `startvm JNode --type headless`, `serial_cmd.py` answered on
first poll; on-guest `javac` clean (2 annotation warnings only).

- Oracle: `one add_iii` L2-forced 3/3 rows bit-match host ref
  (`I:3`, `I:80000000`, `I:7fffffff`). (`one` mode prints `mark|` lines —
  eyeball only; scoreboard runs use default mode.)
- Plugin path: `mauve-single BooleanTest` 12/12 from the plugin, zero
  staging. Staged `MauveDriver.class` + plugin testlets: `noforce`
  12/12; `force` on `StringTest` → `force=27`, **148/2 with the exact v5
  signature** (#10 test_Basics-14, #11 test_Basics-17) — the open
  regression reproduces via the plugin path, ruling out CD-staging
  artifacts as its cause.
- Afterwards: VM powered off, original ISO
  (`all/build/cdroms/jnode-x86-lite.iso`) reattached — state as handed
  over. Scratch remaster (`/tmp/jnode-tests.iso`, `/tmp/isoedit`,
  `/tmp/jgzchk`) removed or in volatile `/tmp`.
- Follow-up same session (JDWP/hotswap): bridged NIC + static `.10`
  validated (`ping` clean), `debug -p 2000` + `jdb version/threads` green,
  `sh build.sh hotswap` pushed redefined boot-image L2 backend to the live
  VM and the guest still force-compiled `add_iii` bit-exact. Full record
  in §4. VM left **running** on the tests ISO with the listener up for the
  next fix cycle.
- v5 g1 **fully green** after L2-140 (I2B) + L2-141 (narrow array ECX),
  both hotswapped live with zero reboots: StringTest 150/0, decode 25/1 ==
  noforce, force|79 green. New regression tests: `testI2BUnsuitableRegisters`
  (T3 15/15), `baloadEcxLoop` + `testNarrowLoadEcxResult` (T1 28 tests, sole
  failure the known verifier anomaly). Census FAILED 169 identical, OK +1.
  Full record in `tests/l2oracle/ORACLE-RESULTS-2026-09-21.md`.

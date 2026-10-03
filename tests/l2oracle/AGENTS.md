# L2 Execution Oracle — Agent Instructions

Differential testing for the L2 compiler backend: the same probe methods run
on the host JDK (reference) and on live JNode with methods force-compiled by
L2. Any diff beyond known pre-existing divergences is an L2 codegen bug.

## Files

| File | Role |
|------|------|
| `Probes.java` | Corpus: ~20 pure static methods (int/long/double/array). Guest + host. |
| `OracleDriver.java` | Harness: forces L2 (`VmType.compileRuntime(...,0,true)`), invokes cases reflectively, writes `method\|args\|hex-or-EX:Class` lines to a file. Same source runs on host (forcing auto-skips). |
| `compare.sh` | Scoreboard: strips serial CR, drops `force\|` proof, reduces `EX:Class:msg` to `EX:Class`, diffs. |
| `run_oracle.sh` | One-command end-to-end run (see below). |
| `kdb_mux.py` | KDB-over-pipe mux for hang diagnosis (holds the single pipe client, drains to log, forwards FIFO commands). |

## Prerequisites

- ISO built from a tree with the L2 backend: `sh build.sh cd-x86-lite`
- **Stub gate** (IDE language server used to clobber `cli/build/classes` with ECJ stubs): `grep -rl "Unresolved compilation problem" cli/build/classes` must print nothing before trusting an ISO.
- JNode booted with **full plugins** (GRUB entry 1 — on-VM `javac` lives there), serial agent up (`echo alive` answers).
- Host JDK for the reference run (`HOST_JAVA`/`HOST_JAVAC`, default the 1.6 tree).

## Quick Start

```bash
cd tests/l2oracle
./run_oracle.sh            # full loop into /tmp/l2oracle-ref
./run_oracle.sh /devices/hdb1/ox   # persistent disk (survives reboots/crashes)
```

( historical script; the live flow below via `serial_cmd.py` is preferred —
`run_oracle.sh` still uses the legacy one-shot agent, which must never run
while the mux holds the pipe. )

## Persistent oracle disk (this machine)

/devices/hdb1 (512MB VDI at `local/oracle-disk.vdi`, gitignored) persists
across reboots and crashes — push once, and post-mortem result files survive.
Prefer it over RAMFS `/jnode/tmp/ox` for all runs here.
STATUS 2026-09-09: disk JFAT is corrupt (`free entry in chain` on
truncate-writes; hard poweroff after a failed push likely caused it).
Falls back to `/jnode/tmp/ox` until someone reformats it (reformat =
data loss of old outputs, all of which are pulled to host anyway).

## Hotswap loop (no reboot for body-only compiler changes)

The L2 backend runs from the boot image, but `VmVirtualMachine.redefineClass`
is implemented and `sh build.sh hotswap` speaks JDI to it (validated live).
Prerequisites: VM network (`ifconfig eth-pci(0,3,0) 192.168.1.10
255.255.255.0`), JDWP listener (`debug -p 2000`), `jnode.debugger.host/port`
in local `jnode.properties` (gitignored). Then: edit → `sh build.sh hotswap`
(~2 min) → rerun probes. No-op redefines are safe; schema changes are not
covered. New compiler code runs L1A-compiled (same logic).

## local/ inventory (gitignored, per-machine)

| Path | Purpose |
|------|---------|
| `local/mk-ox-iso.sh` | oracle ISO builder: stub gate, touch hygiene, `cd-x86-lite`, stage `ox/`, mkisofs re-master, `javap` verify. Re-run after any backend or probe change. |
| `local/l2oracle/conf-x86/` | grub menu copy, `default 1` (all plugins). Use with `sh build.sh -Dmy-conf.dir=<abs path>/local/l2oracle/conf-x86 <target>` for hands-off boots. |
| `local/l2oracle/oracle-disk.vdi` | persistent JFAT oracle disk (see above). |
| `local/classlib/` | unpacked classlib for test bootstrapping (populated at env setup). |

## Manual loop (CD-staged sources — preferred)

Big `--write` pushes reliably OOM the guest heap (~440 echo lines =
483-cycle oom/mark/sweep storm, deaf shell, `--interrupt` can't revive).
Stage sources onto the ISO instead:

```bash
sh local/mk-ox-iso.sh   # build + stage ox/ + re-master + backend verify
# boot full plugins (GRUB entry 1: pause-trick + screenshot + scancodes)
S=~/.config/opencode/skills/jnode-serial/scripts
python3 $S/serial_cmd.py --timeout 900 "mkdir /jnode/tmp/ox" \
  "javac -d /jnode/tmp/ox /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java"
python3 $S/serial_cmd.py --timeout 1200 "java OracleDriver out-l1.txt noforce" "java OracleDriver out-l2.txt"
python3 $S/serial_cmd.py "cat out-l2.txt" > /tmp/out-l2.txt   # strip "[batch OK N]" lines
bash compare.sh <host-out> /tmp/out-l2.txt
```

Filenames arrive intact on the CD (no 8.3 mangle); ~10 guest spawns per
boot. Keep `ox/` file set in sync with `mk-ox-iso.sh` when adding probes.

## Manual loop (serial_cmd.py --write — SMALL files only)

Small files only (~150 lines max; `MiniProbes.java`/`MiniRun.java` size).
Anything bigger goes on the ISO (see above).

```bash
S=~/.config/opencode/skills/jnode-serial/scripts
python3 $S/serial_cmd.py "mkdir /jnode/tmp/ox" "cd /jnode/tmp/ox"
cat MiniProbes.java | python3 $S/serial_cmd.py --write /jnode/tmp/ox/MiniProbes.java
cat MiniRun.java | python3 $S/serial_cmd.py --write /jnode/tmp/ox/MiniRun.java
python3 $S/serial_cmd.py --timeout 900 "javac MiniProbes.java MiniRun.java"
python3 $S/serial_cmd.py --timeout 300 "java MiniRun noforce" "java MiniRun"
```

One shell command per legacy `$JAC` call is obsolete with the mux
(multi-command batches work); never run legacy `jnode_agent_cmd.py` or
raw sockets while the mux holds the pipe. Strip `[batch OK N]` marker
lines from pulled files before comparing.

## ISO hygiene (learned 2026-09-10)

40s incremental `cd-x86-lite` builds can skip the core recompile, baking
a STALE backend into a fresh-looking ISO (symptom: VM traces show old
line numbers, e.g. backend:385 instead of :505). Before every ISO build
that must carry backend changes: `touch` the edited sources, rebuild,
then verify the baked classes, e.g.
`javap -classpath core/build/classes -c ...GenericX86CodeGenerator |
grep -c TopStackLocation` (expect nonzero after 103).

## Legacy loop (jnode_agent_cmd.py — only with the mux STOPPED)

```bash
JAC=~/.config/opencode/skills/jnode-serial/scripts/jnode_agent_cmd.py
python3 $JAC "mkdir /jnode/tmp/ox"
cat Probes.java | python3 $JAC --write /jnode/tmp/ox/Probes.java
cat OracleDriver.java | python3 $JAC --write /jnode/tmp/ox/OracleDriver.java
python3 $JAC "cd /jnode/tmp/ox"
python3 $JAC "javac Probes.java OracleDriver.java"
python3 $JAC "java OracleDriver out-l1.txt noforce"
python3 $JAC "java OracleDriver out-l2.txt"
python3 $JAC "cat out-l2.txt" > /tmp/out-l2.txt
bash compare.sh <host-out> /tmp/out-l2.txt
```

One shell command per `$JAC` call (no chains around silent-long steps);
`--write` needs the parent dir to exist.

## QEMU loop (when VBox is down)

Same guest flow over QEMU (repo skill `.opencode/skills/jnode-interact`;
VBox pipe and mux stay out of it — stop the mux first):

```bash
bash .opencode/skills/jnode-interact/scripts/start_qemu.sh simple all/build/cdroms/jnode-x86-lite.iso 1
# wait for "Serial console available" in /tmp/qemu_serial.log, then:
ln -sf /tmp/jnode.serial2 /tmp/jnode_com2
JAC=.opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py
python3 $JAC "javac -d /jnode/tmp/ox /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java"
python3 $JAC "java OracleDriver out-l2.txt"   # separate calls; TCG is slow
python3 $JAC "cat out-l2.txt" > /tmp/out-l2.txt
```

One command per call (legacy one-shot agent, no batching, no `--write`
of big files); TCG needs generous timeouts. Identify QEMU by PID before
killing (shared host); never `pkill -f` with a self-matching pattern.
`pgrep -f "<pattern>"` self-matches too (the pattern string sits in your
own `bash -c` line) — append `.*JNode`, pipe through `grep -v pgrep`,
or match `mk-ox-iso\.sh` with the escaped dot. A "STILL-ALIVE" after a
successful kill is almost always your own command line.

## Magic probes (host support set + semantic rules)

`Probes.java` magic section calls `org.vmmagic.unboxed.{Word,Address,Offset}`
(real bodies, host-executable). Host `javac` needs them plus two empty
stubs (`org.jnode.vm.VmAddress`, `org.jnode.vm.classmgr.VmType` — opaque
signature types in `Address.java`):

```bash
HJ=/home/levente/ext/prg/java/bin/javac; HR=/home/levente/ext/prg/java/bin/java
mkdir -p /tmp/mghost/src/org/jnode/vm/classmgr /tmp/mghost/classes
printf 'package org.jnode.vm;\npublic class VmAddress {\n}\n' > /tmp/mghost/src/org/jnode/vm/VmAddress.java
printf 'package org.jnode.vm.classmgr;\npublic class VmType {\n}\n' > /tmp/mghost/src/org/jnode/vm/classmgr/VmType.java
$HJ -d /tmp/mghost/classes -sourcepath core/src/vmmagic:core/src/classlib:/tmp/mghost/src \
  /tmp/mghost/src/org/jnode/vm/VmAddress.java /tmp/mghost/src/org/jnode/vm/classmgr/VmType.java \
  core/src/vmmagic/org/vmmagic/unboxed/{Word,Address,Offset,Extent,UnboxedObject,ObjectReference}.java \
  core/src/classlib/org/jnode/annotation/{KernelSpace,Uninterruptible}.java \
  core/src/vmmagic/org/vmmagic/pragma/Uninterruptible.java
$HJ -cp /tmp/mghost/classes -d /tmp/l2oracle-ref /tmp/l2oracle-ref/Probes.java /tmp/l2oracle-ref/OracleDriver.java
$HR -cp /tmp/mghost/classes:/tmp/l2oracle-ref OracleDriver /tmp/l2oracle-ref/out-host.txt noforce
```

Host/guest-lib divergences that are NOT L2 bugs (never probe these):
- `signExtend->toLong` (host sign-extends the `long v` field, guest
  zero-extends the word) — toLong probes use zeroExtend only.
- `zeroExtend->rsha` (host `long>>` vs guest word SAR) — rsha probes
  use signExtend only.
- `Word.LT/LE/GT/GE` are UNSIGNED by design (name-resolves to mcode
  `LT->JB`; the host body is signed). Signed compare goes through
  `Offset.sLT/sLE` (mcode `SLT->JL`); the S-variants are reachable only
  via methods literally named `sLT/sLE/sGT/sGE`.
Status 2026-09-12: 56 magic rows green under L1 AND L2 (force|72), modulo
the 4 pre-existing divergences.

## Driver modes

`java OracleDriver <out> [mode]`:
- (none) — per-item force (CASES methods + nested callee classes) with L2, run all cases + FALLBACK_CASES (L1 fallback, value coverage)
- `noforce` — L1 baseline (also the host mode)
- `one <method>` — force + run a single method (bisect hangs)
- `forceonly <method>` — force, don't invoke (isolates compile vs run)
- `forceall` — force each Probes method individually, one `forceone|name|n` row each (isolates batch failures)
- `disasm <method>` — L2 disassembly of one method to the file (needs `setAccessible`-friendly reflection)
- `direct <method>` — non-reflective static call (isolates reflection; `dstoreVar_d` only)

## Reading the scoreboard

- `mkdir -p /tmp/oracle` FIRST — `compare.sh` writes temp files there;
without it the CR-strip fails silently and diffs vanish (false PASS).
- `force|N` first line, N > 0 on JNode = forcing worked (count includes one extra slot; `-1` host, `-2` forcing threw).
- Known pre-existing divergences (NOT L2 bugs): int `MIN/-1` → `EX` (x86 `#DE` mapped by the runtime) and `parseDouble` 1-ULP (library).
- Everything else in the L2 diff is an L2 bug; file it with method + inputs + expected vs actual bits.

## Gotchas (paid for in full)

- **Pipe wedge**: rapid `reset` cycles wedge the UART2 pipe server (conn-reset on every attach). Recover with full `poweroff` + `startvm`, never reset.
- **UART1 pipe must be drained continuously** or the VM blocks on logging. File mode for normal runs; `kdb_mux.py` when KDB is needed: `nohup python3 kdb_mux.py &`, then `echo "W" > /tmp/kdb_cmd.fifo`, read `/tmp/kdb_resp.log`. Canonical copy in the `jnode-kdb-serial` skill (this one mirrors it); kill it by PID captured at launch (`... & echo $!`), never by `pkill -f` with a pattern that also appears bare in your own command (file paths, class names) — that kills your shell.
- **RESOLVED 2026-09-11**: `dstoreVar_d` wedge is gone on the current tree
(x3 clean L2 passes of a 9-probe store matrix incl. `lstoreVar`,
`dsaVoid`, `dsaLocal`; case re-enabled in `CASES`). Prime suspect is a
stale-ISO artifact (Sep-9 repros predate the ISO-hygiene fix) or an
incidental fix in 097-103. Regression-guarded by `CASES` now.
- **Deferred**: virtual/interface dispatch ECX frames (SP-math shapes), jsr runtime probe (needs hand-built bytecode), `FREM`/`DREM` non-SSS shapes, 64-bit (CG-5).

## Adding probes

Add a static to `Probes.java` (try/catch/finally supported since 104 — see `tryCatchDiv`, `tryCatchOob`, `tryFinally`; no objects in signatures keeps reflection simple) + a `{name, args...}` row in `OracleDriver.CASES`. Re-run host ref + `run_oracle.sh`.

## jsr/ret subroutines (`jsr/`)

javac never emits handler-free jsr (only finally, which implies a table),
so the subroutine probe is a hand-built class: `jsr/mkjsr.py` writes
`JsrProbe.class` (jsr/ret, no handlers) + `JsrGen.java` (byte-exact
re-emitter, since binary pushes mangle class files). Guest flow:
push `JsrGen.java` + `jsr/JsrForce2.java`, `javac`, `java JsrGen`,
`java JsrForce2` → `force|1` (L2 compiled `run`) + `loopdone|42`
(1000 subroutine calls). Status: green (103).

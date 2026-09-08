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

## Persistent oracle disk (this machine)

`/devices/hdb1` (512MB VDI at `local/oracle-disk.vdi`, gitignored) persists
across reboots and crashes — push once, and post-mortem result files survive.
Prefer it over RAMFS `/jnode/tmp/ox` for all runs here.

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
| `local/l2oracle/conf-x86/` | grub menu copy, `default 1` (all plugins). Use with `sh build.sh -Dmy-conf.dir=<abs path>/local/l2oracle/conf-x86 <target>` for hands-off boots. |
| `local/l2oracle/oracle-disk.vdi` | persistent JFAT oracle disk (see above). |
| `local/classlib/` | unpacked classlib for test bootstrapping (populated at env setup). |

## Manual loop (when the script needs babysitting)

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

## Driver modes

`java OracleDriver <out> [mode]`:
- (none) — force whole `Probes` class with L2, run all cases
- `noforce` — L1 baseline (also the host mode)
- `one <method>` — force + run a single method (bisect hangs)
- `forceonly <method>` — force, don't invoke (isolates compile vs run)
- `disasm <method>` — L2 disassembly of one method to the file (needs `setAccessible`-friendly reflection)
- `direct <method>` — non-reflective static call (isolates reflection; `dstoreVar_d` only)

## Reading the scoreboard

- `force|N` first line, N > 0 on JNode = forcing worked (count includes one extra slot; `-1` host, `-2` forcing threw).
- Known pre-existing divergences (NOT L2 bugs): int `MIN/-1` → `EX` (x86 `#DE` mapped by the runtime) and `parseDouble` 1-ULP (library).
- Everything else in the L2 diff is an L2 bug; file it with method + inputs + expected vs actual bits.

## Gotchas (paid for in full)

- **Pipe wedge**: rapid `reset` cycles wedge the UART2 pipe server (conn-reset on every attach). Recover with full `poweroff` + `startvm`, never reset.
- **UART1 pipe must be drained continuously** or the VM blocks on logging. File mode for normal runs; `kdb_mux.py` when KDB is needed: `nohup python3 kdb_mux.py &`, then `echo "W" > /tmp/kdb_cmd.fifo`, read `/tmp/kdb_resp.log`. Canonical copy in the `jnode-kdb-serial` skill (this one mirrors it); kill it by PID captured at launch (`... & echo $!`), never by `pkill -f` with a pattern that also appears bare in your own command (file paths, class names) — that kills your shell.
- **Quarantined**: `dstoreVar_d` (variable double store into a double[] param) wedges the shell under L2 — loop-free disassembly, cause open. Case commented out in `CASES`; probes retained.
- **Deferred**: virtual/interface dispatch ECX frames (SP-math shapes), jsr runtime probe (needs hand-built bytecode), `FREM`/`DREM` non-SSS shapes, 64-bit (CG-5).

## Adding probes

Add a static to `Probes.java` (no try/catch — L2 has no handler tables yet; no objects in signatures keeps reflection simple) + a `{name, args...}` row in `OracleDriver.CASES`. Re-run host ref + `run_oracle.sh`.

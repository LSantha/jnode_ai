# L2 Execution Oracle — Agent Instructions

Differential testing for the L2 compiler backend: the same probe methods run
on the host JDK (reference) and on live JNode with methods force-compiled by
L2. Any diff beyond known pre-existing divergences is an L2 codegen bug.

## RULE: every fix ships with a regression guard (settled 2026-09-27)

A fix without a guard is not done. The guard must be able to FAIL when the
fix is reverted, and it must be recorded in the commit message.

Acceptable guards, strongest first:

| Guard | Where | Red proof required |
|---|---|---|
| Whole-corpus census lint | `L2Census` (own process, not JUnit) | lint fires on the pre-fix overlay and is 0 after: e.g. `NOYIELDPOINT` 1954 -> 0, `FISTPMISMATCH` 22 -> 0 |
| SSA / deSSA structural guard | the census now runs `SSAVerifier.verifyPreDessA` + `verifyPostDessA` on every method | a violating method appears in FAILED when the fix is reverted |
| Synthetic emitter / pipeline test | `L2ModeMatrixTest` (T3), `L2PipelineTest` (T1), `L2HostTest` (T0) | test fails on the pre-fix class overlay (`javap`-verify which class is in the run) |
| Value-level probe | `Probes.java` + `OracleDriver.CASES` | guest-vs-host diff appears pre-fix; host reference recorded |
| Label/emit invariant census | e.g. L2-161 `countUnboundInstrLabels` | non-zero before the fix, 0 after |

Not a guard, on its own:

- "it compiled" (a forward-slot deSSA misordering compiles cleanly — that is
  why the census runs the SSA verifier),
- a mauve list diff without single-testlet isolation (cross-testlet state
  produced phantom `Class.*` regressions, and one "fixed" case was a
  host-JDK semantic difference),
- a probe added without a recorded pre-fix value,
- an observation with no reproducer.

Minimum bar for a corpus-wide fix (e.g. a whole class of methods): one
whole-corpus lint or verifier, not a synthetic test alone. Minimum bar for a
single-shape fix: one synthetic test plus a probe row when the shape is
value-visible.

Gate order before committing: `regress.sh host` (T0/T3/T1, all-junit,
census with `FAILED == 0`) then the live legs, and a boot attempt when the
fix can plausibly move the boot.

## Method contract (settled 2026-09-27; L2 is hard enough without methodology bugs)

Every one of these was learned from a wrong result, not from a hard problem:

1. **Verify the artifact, not the intention.** Before believing a guest
   result, assert which image is on the CD (a host-phase `build` passes
   `-Djnode.compiler=L2` and overwrites the same ISO path) and which
   plugin entry is default. `regress.sh` records this as an `ARTIFACT`
   status line.
2. **Verify the class, not the source.** `javap` an anchor per fix
   (`regress.sh anchors`); a stale class once produced a fake "red".
3. **A check's exit status is its verdict.** No phase may end on an
   incidental command (a trailing `grep` once turned a failing census
   into PASS).
4. **One workload per guest boot.** Superposition produced a phantom
   regression, a phantom "fix", and a GC loop in one evening.
5. **Measure the baseline before theorising.** A force-only diff is not a
   bug until the guest's own unforced value is recorded; that measurement
   retired the classLiteral/`getModifiers` "bug" as host semantics.
6. **Nothing lands unvalidated**, including work inherited from another
   session: full gate set, `FAILED == 0` census, live legs.
7. **No non-actionable signal in the default loop** (the L2 boot check is
   opt-in) and no fixed sleeps: probe, then act.
8. **Revert rather than leave the tree red**; after two failed attempts on
   a blocker, write down the evidence and move to the next item.

## Regression methodology (the short version; the two sections above are normative)

Order of work for any suspected defect. Each step exists because skipping one produced
a wrong conclusion.

1. **Reproduce before theorising.** Get it to fail on demand, in the cheapest harness
   that shows it. Record the exact command — see `CENSUS-FAILURES.md` for the shape.
   A reproduction you cannot re-run is not a reproduction.
2. **Measure before fixing.** Instrument the corpus and count occurrences. C2 looked
   like a live coalescing bug; it had 4,352 coalescing sites and **0** occurrences of
   all three hazards, i.e. latent, so the fix was reverted. A6 looked like a live FREM
   bug and 14 probe rows matched the host bit-for-bit, so it was closed as
   not-reproducible. Both saved work that would have been wasted.
3. **Choose the guard from the table above**, strongest first. For a value-visible bug
   that means a probe row in `Probes.java` + `OracleDriver.CASES` with the host
   reference recorded; the red proof is the guest-vs-host diff *before* the fix.
4. **Validate cheapest-first:** probe rows (seconds) → `regress.sh … host` (T0/T3/T1,
   all-junit, core census) → `regress.sh … oracle` → mauve. A fix that only reaches the
   mauve stage because the probe was skipped is unvalidated.
5. **If it is a structural claim, prove the instrument fires on the known case before
   you keep it.** Three hand-rolled lints were written and all three removed: one
   examined 0 phis, one fired on 5 known-good methods, one fired on 0 including the two
   methods it was written for. A blind or noisy lint is worse than none, because it
   reads like a clean bill of health. The SSA verifier and the value probes are the
   guards that actually work.
6. **Revert rather than ship a fix you cannot prove.** Two NEW-1 fix attempts were
   reverted with their failure modes written down, so the next attempt starts from
   evidence instead of repeating them.
7. **After two failed attempts on a blocker**, write down the evidence and move to the
   next item. That rule already existed; it is what kept the discarded lints from
   becoming three shipped lints.

### Census gate rules

- The **core** corpus (`core/build/classes`) is the default gate: 11,607 methods,
  `FAILED==0` is the pass condition.
- The **wide** classlib corpus **must be run in chunks**
  (`tests/l2oracle/census-wide.sh`). A single sweep reports `OK=59,085 FAILED=22`
  because a cumulative `0x20000` bound (`ArrayIndexOutOfBoundsException: 131072`) aborts
  methods and silently truncates part of the corpus; chunked it is `OK=67,836`,
  `FAILED=3`. Prefixes match on a package boundary and must be disjoint.
- A **missing dependency is a skip, not a failure** (`SKIP_MISSING_DEP`), and neither is
  a **harness limitation** (`SKIP_ENV`: unresolvable native method, IR scope gap,
  recursive class prepare, classlib version mismatch). Both are listed per method. A
  wall of environmental noise in `FAILED` is how the earlier 169-entry baseline hid a
  real defect.
- The census harness installs a naming service; without it
  `ClassDecoder.getNativeCodeReplacement` NPEs and ~90 methods are misreported as
  missing classes.

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
- The **L1 vs host** section (the `noforce` baseline) can show rows that never
  appear in the L2 diff. Measured 2026-10-01: `fArith_f|3.5,1.25` and
  `fArith_f|-7.25,2.5` differ by 1 ULP on L1 but match the host under L2.
  Not a bug: `fArith_f` is `(a % b) + (a / b) * (a - b)` and is not
  `strictfp`, so FP-non-strict evaluation (the rule until Java 17, JEP 306)
  lets each side round its intermediates at its own precision. Reproduced
  exactly both ways: the chain evaluated in `double` and rounded once gives
  40e9999a / 41d03333 (the JNode values), every operation rounded to `float`
  gives 40e99999 / 41d03334 (the host values). Both are conforming -- never
  chase L1A to parity with the host here.
- Everything else in the L2 diff is an L2 bug; file it with method + inputs + expected vs actual bits.

## Gotchas (paid for in full)
- **Do not gate a build on `grep -c "error:"`.** Ant prints `1 error` (no colon),
  so that check reports 0 on a FAILED build -- and the next census run then executes
  a STALE `L2Census.class`, quietly measuring the previous code. Hit on 2026-09-27:
  a check that never fired looked like a check that found nothing. Gate on the exit
  status, or grep for `BUILD SUCCESSFUL` / `Compile of .* failed`, and when a
  measurement is surprising, confirm the class is the one you just built.

- **Pipe wedge**: rapid `reset` cycles wedge the UART2 pipe server (conn-reset on every attach). Recover with full `poweroff` + `startvm`, never reset.
- **UART1 pipe must be drained continuously** or the VM blocks on logging. File mode for normal runs; `kdb_mux.py` when KDB is needed: `nohup python3 kdb_mux.py &`, then `echo "W" > /tmp/kdb_cmd.fifo`, read `/tmp/kdb_resp.log`. Canonical copy in the `jnode-kdb-serial` skill (this one mirrors it); kill it by PID captured at launch (`... & echo $!`), never by `pkill -f` with a pattern that also appears bare in your own command (file paths, class names) — that kills your shell.
- **RESOLVED 2026-09-11**: `dstoreVar_d` wedge is gone on the current tree
(x3 clean L2 passes of a 9-probe store matrix incl. `lstoreVar`,
`dsaVoid`, `dsaLocal`; case re-enabled in `CASES`). Prime suspect is a
stale-ISO artifact (Sep-9 repros predate the ISO-hygiene fix) or an
incidental fix in 097-103. Regression-guarded by `CASES` now.
- **Deferred**: virtual/interface dispatch ECX frames (SP-math shapes), jsr runtime probe (needs hand-built bytecode), `FREM`/`DREM` non-SSS shapes, 64-bit (CG-5).
- **`regress.sh` phase bodies are single-quoted** (`run census sh -c ' ... '"$LABEL"' ... '`): an apostrophe anywhere in a comment or echo inside a phase ends the string and the script dies at parse time with a confusing `Syntax error: ")" unexpected (expecting "fi")` pointing at the next `)`. Reword (`allocator's` -> `the picture the allocator builds`), never re-quote. Check with `sh -n tests/l2oracle/regress.sh` before a gate run.

- **A check that cannot fail looks exactly like a check that found nothing** (two
  measured instances, ANCHOR-L2-187). (1) The probe census ran `"$HJ"` inside
  `sh -c`, where `HJ` and `LABEL` are plain script variables and therefore
  *unset*, so the census never started, its stderr went to `/dev/null`, the report
  file was never written, and the awk counting its FAILED section then read a file
  that did not exist -- which is 0. It printed `constreffield=0 failed=0` on every
  run, including the runs cited as the A8 and C6 guards. (2) `t0`/`t3`/`t1` ended
  on `| tail -n 3`, so the status `run` saw was tail's and those three verdicts
  could never be FAIL: measured, `t1` printed `Tests run: 40, Failures: 1` on a
  PASS line (the failure was caught only by `alljunit`). So: when a check is
  green, ask what it reads and from where; **prove it goes red on a known bad
  tree before trusting it.** A report written to a file must be read from that
  file, and every variable a `sh -c` body needs must be interpolated from the
  outer shell (`'"$HJ"'`) or exported.
- **T1/T3 run `X86TextAssembler`, which does not run `X86BinaryAssembler.testDst`
  (and no other binary validation).** An emission T1 accepts can still be
  rejected when the same method is AOT-compiled into the bootimage -- the
  L2-186 wide-getfield half was caught exactly there
  (`IllegalArgumentException: Write to [EBP+0]`), and `sh tests/l2oracle/regress.sh
  … build` AOT-compiles *every* core class, so the `build` phase is a real
  emission-validity guard and not just a prerequisite. If a fix only touches
  emission, the `build` verdict matters as much as `t1`.

- **`guest up` + `TIMEOUT link down too long` = someone else owns
  `/tmp/jnode.serial2`, not a slow guest** (cost ~50 min on 2026-09-28). The mux
  (`serial_mux.py`) holds one connection to `/tmp/jnode.serial2`, which VirtualBox
  serves via `changeuartmode2 server` -- that bind **silently fails** if any other
  process already owns the path, and then the mux talks to the squatter instead of
  the VM. Two **QEMU** processes left over from an earlier session owned it; their
  guest had panicked two days earlier. `boot-wait.sh` still reports ready (its
  `echo alive` probe goes through the same wrong endpoint), then every batch ends
  `TIMEOUT link down too long`. Tell-tales: `gsh-*.out` contains only
  `[Ns still running: …]` lines and never output; `/tmp/jnode_serial_resp/*.done`
  reads `TIMEOUT link down too long` with a **0-byte** `.out` (a good run writes
  `OK 1` and kilobytes within seconds); `cat /tmp/jnode_serial_mux.status` says
  `link=down`; `ss -xlp | grep jnode.serial2` shows `users=(("qemu-system-x86",…))`
  instead of VirtualBox. Recover with `kill <pid>` on the stale PIDs and the mux
  (`pkill` is blocked; `pgrep -f` self-matches -- match `qemu-system-x86` by name).
  Do not debug the guest, the ISO or the probe until `ss` shows the right owner.
  A timed-out shell command may also leave an orphaned `regress.sh` behind --
  `ps -eo pid,etime,cmd | grep regress` before rerunning.

- **A value-level probe beat every structural instrument on B1 (L2-189).** A
  census lint for handler-entry phis was written, could not identify handler
  blocks (the flag is not on the blocks that hold the phis; post-fixup startPCs do
  not match the exception table), examined **0 phis**, and was removed -- keeping
  it would have read as a clean bill of health. What found the bug was
  `Probes#b1HandlerPhi` (ordinary try/catch, handler reads a try-modified local)
  run through the oracle, plus the `L2Dump --raw` / `--ssa0` / `--ssa` views, which
  show *where in the pipeline* an IR value changes meaning (`--raw` correct,
  `--ssa0` already wrong). For any IR-ordering suspicion, dump the stages first.

## Adding probes

Add a static to `Probes.java` (try/catch/finally supported since 104 — see `tryCatchDiv`, `tryCatchOob`, `tryFinally`, `b1HandlerPhi` (the try/catch local-read shape that found L2-189); no objects in signatures keeps reflection simple) + a `{name, args...}` row in `OracleDriver.CASES`. Re-run host ref + `run_oracle.sh`.

## jsr/ret subroutines (`jsr/`)

javac never emits handler-free jsr (only finally, which implies a table),
so the subroutine probe is a hand-built class: `jsr/mkjsr.py` writes
`JsrProbe.class` (jsr/ret, no handlers) + `JsrGen.java` (byte-exact
re-emitter, since binary pushes mangle class files). Guest flow:
push `JsrGen.java` + `jsr/JsrForce2.java`, `javac`, `java JsrGen`,
`java JsrForce2` → `force|1` (L2 compiled `run`) + `loopdone|42`
(1000 subroutine calls). Status: green (103).

## Standing rule: every identified bug is fixed

Set by the user on 2026-09-27, after A8, C6 and A9 were each left "measured latent"
on census evidence:

* **Every bug I identify gets fixed.** There is no "latent" resting state and no
  "guarded" resting state. If I have named a defect, it is fixed or the naming is
  withdrawn by reading the code -- not by counting occurrences.
* **A census is not evidence.** `0 occurrences across 67836 methods` establishes
  only that a shape is rare in today's corpus. It says nothing about correctness,
  and nothing about code that does not exist yet. A census may prioritise work; it
  may never close it.
* **Absence of call sites is not absence of a bug.** "No production caller" and "not
  in the corpus" both describe rarity. Two of the three defects above were real.
* **"Correct" must be shown structurally** -- by reading the emission path and
  arguing it cannot go wrong, or by fixing it so the bad case cannot be expressed.
  A probe row that matches the host shows the shape works today, nothing more.
* If the fix is available and cheap, measure afterwards, never before.

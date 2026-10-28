#!/bin/bash
# End-to-end L2 execution oracle. See AGENTS.md for the full runbook.
# Usage: ./run_oracle.sh [workdir-on-vm]   (default /jnode/tmp/ox)
# Prerequisites: JNode booted with full plugins (GRUB entry 1), the agent
#   shell answering (drives the guest via the repo's mux client
#   serial_cmd.py; set INSTANCE when more than one VM runs), host JDK 1.6+
#   for the reference run.
set -u
HERE="$(dirname "$0")"
ROOT="$(cd "$HERE/../.." && pwd)"
OX="${1:-/jnode/tmp/ox}"
SC="${SC:-$ROOT/.opencode/skills/jnode-serial/scripts/serial_cmd.py}"
# INSTANCE: forwarded to serial_cmd as --instance when set; unset lets
# serial_cmd resolve the instance itself (fits per-instance jnode-qemu).
INSTANCE="${INSTANCE:-}"
HOST_JAVA="${HOST_JAVA:-/home/levente/ext/prg/java/bin/java}"
HOST_JAVAC="${HOST_JAVAC:-/home/levente/ext/prg/java/bin/javac}"
REFDIR="${REFDIR:-/tmp/l2oracle-ref}"

step() { echo "=== $1"; }

fail() { echo "ORACLE ABORT: $1" >&2; exit 1; }

# Guest-command wrapper over the mux client. sc [serial_cmd flags] "cmd"...
sc() {
  [ -f "$SC" ] || fail "serial_cmd.py missing at $SC (repo skill jnode-serial)"
  python3 "$SC" ${INSTANCE:+--instance "$INSTANCE"} "$@"
}

# Pull a guest result file: strip the mux's host-side status lines
# ([waiting ...]/[batch OK N]) and CRs before writing to the host file.
pull() {
  _f="$1"; _dst="$2"
  _out="$(sc --wait 60 --timeout 120 "cat $OX/$_f" 2>/dev/null)" \
    || fail "pull $_f"
  printf '%s\n' "$_out" | grep -av "^\[" | tr -d '\r' > "$_dst"
}

step "host reference"
rm -rf "$REFDIR" && mkdir -p "$REFDIR" || fail "refdir"
cp "$HERE/Probes.java" "$HERE/OracleDriver.java" "$REFDIR/" || fail "copy"
# Probes.java has called org.vmmagic.unboxed.{Word,Address,Offset} since the
# magic section landed, but this reference compile ran with NO -cp at all:
# javac could not resolve Word/Address and reported 49 "cannot find symbol"
# errors, so the loop aborted before it ever reached the guest. AGENTS.md
# (Magic probes) documents the host support set and its classpath; honor it
# here, and refuse to start without it rather than silently degrade.
MAGIC_CP="${MAGIC_CP:-/tmp/mghost/classes}"
[ -d "$MAGIC_CP" ] || fail "magic host support set missing at $MAGIC_CP -- see AGENTS.md, Magic probes"
"$HOST_JAVAC" -cp "$MAGIC_CP" -d "$REFDIR" "$REFDIR/Probes.java" "$REFDIR/OracleDriver.java" || fail "host javac"
"$HOST_JAVA" -cp "$MAGIC_CP:$REFDIR" OracleDriver "$REFDIR/out-host.txt" noforce || fail "host run"

step "on-VM javac (CD-staged ox/)"
# The old body piped Probes.java through --write, which is ~950 lines of
# echo: a push that size reliably OOMs the guest heap (AGENTS.md, ISO
# hygiene / manual loop) and was why the preferred flow moved to CD-staged
# sources. local/mk-ox-iso.sh already puts these exact files on the CD, so
# compile them in place and let a missing ox/ say so instead of OOMing.
# mkdir+javac+cd in one mux batch; `cd` persists in the agent shell after
# it (needed by the bare `java OracleDriver` calls below).
sc --wait 120 --timeout 900 \
  "mkdir $OX" \
  "javac -d $OX /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java" \
  "cd $OX" \
  || fail "guest javac -- ox/ is not staged on the ISO, run local/mk-ox-iso.sh"

step "L1 baseline + L2 run"
sc --timeout 1800 "java OracleDriver out-l1.txt noforce" > /dev/null \
  || fail "L1 run"
sc --timeout 1800 "java OracleDriver out-l2.txt" > /dev/null || fail "L2 run"

step "pull + compare"
pull out-l1.txt "$REFDIR/out-l1.txt"
pull out-l2.txt "$REFDIR/out-l2.txt"
echo "--- L1 vs host (pre-existing divergences only) ---"
bash "$HERE/compare.sh" "$REFDIR/out-host.txt" "$REFDIR/out-l1.txt" || true
echo "--- L2 vs host (any diff here is an L2 bug) ---"
bash "$HERE/compare.sh" "$REFDIR/out-host.txt" "$REFDIR/out-l2.txt"

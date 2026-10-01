#!/bin/bash
# End-to-end L2 execution oracle. See AGENTS.md for the full runbook.
# Usage: ./run_oracle.sh [workdir-on-vm]   (default /jnode/tmp/ox)
# Prerequisites: JNode booted with full plugins, serial agent up,
#   JAC helper reachable, host JDK 1.6+ for the reference run.
set -u
HERE="$(dirname "$0")"
OX="${1:-/jnode/tmp/ox}"
JAC="${JAC:-$HOME/.config/opencode/skills/jnode-serial/scripts/jnode_agent_cmd.py}"
HOST_JAVA="${HOST_JAVA:-/home/levente/ext/prg/java/bin/java}"
HOST_JAVAC="${HOST_JAVAC:-/home/levente/ext/prg/java/bin/javac}"
REFDIR="${REFDIR:-/tmp/l2oracle-ref}"

step() { echo "=== $1"; }

fail() { echo "ORACLE ABORT: $1" >&2; exit 1; }

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
python3 "$JAC" "mkdir $OX" > /dev/null || fail "mkdir"
python3 "$JAC" "javac -d $OX /devices/sg0/ox/Probes.java /devices/sg0/ox/OracleDriver.java" \
  || fail "guest javac -- ox/ is not staged on the ISO, run local/mk-ox-iso.sh"
python3 "$JAC" "cd $OX" > /dev/null || fail "cd"

step "L1 baseline + L2 run"
python3 "$JAC" "java OracleDriver out-l1.txt noforce" > /dev/null || fail "L1 run"
python3 "$JAC" "java OracleDriver out-l2.txt" > /dev/null || fail "L2 run"

step "pull + compare"
python3 "$JAC" "cat out-l1.txt" > "$REFDIR/out-l1.txt" || fail "pull L1"
python3 "$JAC" "cat out-l2.txt" > "$REFDIR/out-l2.txt" || fail "pull L2"
echo "--- L1 vs host (pre-existing divergences only) ---"
bash "$HERE/compare.sh" "$REFDIR/out-host.txt" "$REFDIR/out-l1.txt" || true
echo "--- L2 vs host (any diff here is an L2 bug) ---"
bash "$HERE/compare.sh" "$REFDIR/out-host.txt" "$REFDIR/out-l2.txt"

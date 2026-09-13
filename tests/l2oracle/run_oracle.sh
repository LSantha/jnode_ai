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
"$HOST_JAVAC" -d "$REFDIR" "$REFDIR/Probes.java" "$REFDIR/OracleDriver.java" || fail "host javac"
"$HOST_JAVA" -cp "$REFDIR" OracleDriver "$REFDIR/out-host.txt" noforce || fail "host run"

step "push to VM ($OX)"
python3 "$JAC" "mkdir $OX" > /dev/null || fail "mkdir"
cat "$HERE/Probes.java" | python3 "$JAC" --write "$OX/Probes.java" > /dev/null || fail "push Probes"
cat "$HERE/OracleDriver.java" | python3 "$JAC" --write "$OX/OracleDriver.java" > /dev/null || fail "push Driver"

step "on-VM javac"
python3 "$JAC" "cd $OX" > /dev/null || fail "cd"
python3 "$JAC" "javac Probes.java OracleDriver.java" > /dev/null || fail "guest javac"

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

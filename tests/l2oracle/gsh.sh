#!/bin/sh
# Guarded guest command runner v2. NEVER blocks silently, NEVER misreports a
# GC loop as "slow".
#
# Usage: gsh.sh <hard_cap_sec> <stall_sec> <label> <cmd> [cmd...]
#
# Runs the serial client in the BACKGROUND and polls it, so:
#   - a stalled guest is detected by OUTPUT STALL (no growth for stall_sec),
#     not by waiting for a timeout that may never fire;
#   - timeout(1) escalates to SIGKILL (-k) so an unresponsive client dies;
#   - GC loops are detected from EITHER the live output OR the mux response
#     dir OR the KDB console log, and reported as such;
#   - on any failure the client is SIGKILLed and the VM is powered off.
# ANCHOR-L2-243: with JNODE_L2_INSTANCE set every serial command is
# --instance scoped, so a co-running leg's client on the legacy endpoint
# is never touched here (nor are ours by theirs); the GC-marker sources
# resolve to the instance's mux response dir, log and boot log.
set -u
HARD=$1; shift
STALL=$1; shift
LABEL=$1; shift
VI=${JNODE_L2_INSTANCE:-}
S=${JNODE_SERIAL_SCRIPTS:-$HOME/.config/opencode/skills/jnode-serial/scripts}
SERIAL_FALLBACK=$(dirname "$0")/../../.opencode/skills/jnode-serial/scripts
[ -d "$S" ] || S=$(cd "$SERIAL_FALLBACK" 2>/dev/null && pwd) || S="$SERIAL_FALLBACK"
OUT=${REGRESS_WORK:-/tmp/jnode-regress}/gsh-$LABEL.out
mkdir -p /tmp/opencode
rm -f "$OUT"
SI=""
RESP_GLOB="/tmp/jnode_serial_resp/*.out"
KSCAN=/tmp/jnode.kdb
if [ -n "$VI" ]; then
  SI="--instance $VI"
  _state_dir=${JNODE_STATE_DIR:-/tmp/jnode}
  _mux_resp=$(python3 - "$S" "$VI" <<'PYEOF' 2>/dev/null
import sys
sys.path.insert(0, sys.argv[1])
import jnode_paths as jp
print(jp.paths_for(sys.argv[2]).resp_dir + "/*.out")
PYEOF
)
  [ -n "$_mux_resp" ] && RESP_GLOB="$_mux_resp"
  KSCAN="$_state_dir/$VI/boot.log"
fi

python3 "$S/serial_cmd.py" $SI --timeout "$HARD" "$@" > "$OUT" 2>&1 &
CPID=$!

last_size=-1
still=0
i=0
verdict=""
while [ $i -lt $((HARD / 5 + 2)) ]; do
  if ! kill -0 "$CPID" 2>/dev/null; then
    wait "$CPID" 2>/dev/null
    rc=$?
    if [ "$rc" -eq 0 ] && [ -s "$OUT" ]; then
      grep -av "^\[batch" "$OUT" | tr -d '\r'
      exit 0
    fi
    verdict="client exited rc=$rc (empty=$([ -s "$OUT" ] && echo no || echo yes))"
    break
  fi
  sleep 5
  i=$((i + 1))
  size=$(wc -c < "$OUT" 2>/dev/null || echo 0)
  if [ "$size" -gt "$last_size" ]; then
    last_size=$size
    still=0
  else
    still=$((still + 5))
  fi
  if [ "$still" -ge "$STALL" ]; then
    verdict="no output for ${still}s (guest not making progress)"
    break
  fi
done

if [ -z "$verdict" ]; then
  verdict="hard cap ${HARD}s reached"
fi
GC=0
for src in "$OUT" "$(ls -t $RESP_GLOB 2>/dev/null | head -n 1)" "$KSCAN"; do
  [ -f "$src" ] || continue
  n=$(grep -aciE "mark|sweep|out of memory|oom|full gc|gc storm" "$src" 2>/dev/null | head -n 1)
  case "$n" in
    '' | *[!0-9]*) n=0 ;;
  esac
  [ "$n" -gt "$GC" ] && GC=$n
done
STATE=$(vboxmanage showvminfo "${JNODE_VM:-JNode}" --machinereadable 2>/dev/null \
  | grep "^VMState=" | head -n 1 | cut -d'"' -f2)
echo "== GSH FAIL label=$LABEL: $verdict; vms=${STATE:-unknown} gc_markers=$GC"
if [ "$GC" -gt 3 ]; then
  echo "== VERDICT: GC LOOP in guest (mark/sweep/oom markers: $GC)"
elif [ "${STATE:-}" = "running" ]; then
  echo "== VERDICT: guest alive but stalled (wedged shell / stuck compile / heap thrash)"
else
  echo "== VERDICT: VM not running (${STATE:-unknown}) -- nothing executed"
fi
echo "== output tail:"
tail -n 15 "$OUT" 2>/dev/null | tr -d '\r'
kill -9 "$CPID" 2>/dev/null
# ANCHOR-L2-243: only this leg's client may be swept. On the legacy
# endpoint the old all-clients pgrep stays (the endpoint is shared); on
# an instance, a co-running leg's client must survive this failure and
# ours is identifiable by its --instance argument.
if [ -n "$VI" ]; then
  for p in $(ps -eo pid=,cmd= | grep -F 'serial_cmd.py' | grep -F -- "--instance $VI" | grep -v grep | awk '{print $1}'); do
    kill -9 "$p" 2>/dev/null
  done
else
  pkill -9 -f "serial_[c]md.py" 2>/dev/null
fi
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
rm -f /tmp/jnode-ready
exit 1

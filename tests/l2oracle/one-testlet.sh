#!/bin/sh
# One isolated single-testlet phase: fresh boot -> write list in-guest ->
# run one mode -> fetch -> poweroff.
# Usage: one-testlet.sh <fqcn> <noforce|force>
set -u
CLS=$1
MODE=$2
S=${JNODE_SERIAL_SCRIPTS:-$HOME/.config/opencode/skills/jnode-serial/scripts}
SAFE=$(echo "$CLS" | tr '.$' '__')
ST=/tmp/opencode/one-$SAFE-$MODE.status
OUT=/tmp/opencode/one-$SAFE-$MODE.txt

rm -f "$ST" "$OUT" /tmp/jnode-ready /tmp/jnode.kdb
echo "one $CLS $MODE: boot $(date +%H:%M:%S)" > "$ST"
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
sleep 3
vboxmanage startvm "JNode" --type headless >/dev/null 2>&1
sh /tmp/opencode/boot-wait.sh >/dev/null 2>&1
[ -f /tmp/jnode-ready ] || { echo "one $CLS $MODE: BOOT FAILED" >> "$ST"; exit 1; }
sh /tmp/opencode/gsh.sh 600 90 "one$SAFE$MODE" \
  "mkdir /jnode/tmp/mv" \
  "echo $CLS > /jnode/tmp/mv/one.txt" \
  "cd /devices/sg0/ox/mauve" \
  "java MauveDriver $MODE /jnode/tmp/mv/one.txt /jnode/tmp/mv/$MODE.txt" \
  > /dev/null 2>&1
echo "one $CLS $MODE: workload rc=$? $(date +%H:%M:%S)" >> "$ST"
sh /tmp/opencode/gsh.sh 90 30 "one${SAFE}${MODE}f" \
  "cat /jnode/tmp/mv/$MODE.txt" > "$OUT" 2>&1
grep -avE "^\[batch" "$OUT" | tr -d '\r' > "$OUT.clean"
echo "one $CLS $MODE: $(grep -aE '\|(pass|runEX)' "$OUT.clean" | tail -n 1) $(date +%H:%M:%S)" >> "$ST"
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
echo "one $CLS $MODE: COMPLETE" >> "$ST"

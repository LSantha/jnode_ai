#!/bin/sh
# One isolated live phase: fresh boot -> single workload -> fetch -> poweroff.
# Usage: mauve-phase.sh <subsetN> <noforce|force>
# Writes /tmp/opencode/mv-<subsetN>-<mode>.txt and a status line to
# /tmp/opencode/mv-<subsetN>-<mode>.status
set -u
V=$1
MODE=$2
S=${JNODE_SERIAL_SCRIPTS:-$HOME/.config/opencode/skills/jnode-serial/scripts}
OUT=/tmp/opencode/mv-v$V-$MODE.txt
ST=/tmp/opencode/mv-v$V-$MODE.status
LIST=/devices/sg0/ox/mauve/list.txt
[ "$V" != "1" ] && LIST=/devices/sg0/ox/mauve/list$V.txt
DST=/jnode/tmp/mv/$MODE.txt

rm -f "$OUT" "$ST" /tmp/jnode-ready /tmp/jnode.kdb
echo "phase v$V $MODE: booting $(date +%H:%M:%S)" > "$ST"
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
sleep 3
vboxmanage startvm "JNode" --type headless >/dev/null 2>&1
sh /tmp/opencode/boot-wait.sh >/dev/null 2>&1
[ -f /tmp/jnode-ready ] || { echo "phase v$V $MODE: BOOT FAILED" >> "$ST"; exit 1; }
echo "phase v$V $MODE: booted in $(cat /tmp/jnode-ready)s, running $(date +%H:%M:%S)" >> "$ST"
sh /tmp/opencode/gsh.sh 1500 120 "mv$V$MODE" \
  "mkdir /jnode/tmp/mv" \
  "cd /devices/sg0/ox/mauve" \
  "java MauveDriver $MODE $LIST $DST" > /dev/null 2>&1
echo "phase v$V $MODE: workload rc=$? at $(date +%H:%M:%S)" >> "$ST"
sh /tmp/opencode/gsh.sh 90 30 "mv${V}${MODE}f" "cat $DST" > "$OUT" 2>&1
grep -avE "^\[batch" "$OUT" | tr -d '\r' > "$OUT.clean"
echo "phase v$V $MODE: fetched $(grep -c '^mauve|' "$OUT.clean" 2>/dev/null) rows, DONE=$(grep -c DONE "$OUT.clean" 2>/dev/null) at $(date +%H:%M:%S)" >> "$ST"
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
echo "phase v$V $MODE: COMPLETE" >> "$ST"

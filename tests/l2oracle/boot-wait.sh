#!/bin/sh
# Boot JNode and wait for the serial shell by PROBING, not by sleeping.
# Writes /tmp/jnode-ready with the elapsed seconds when the guest answers.
rm -f /tmp/jnode-ready
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
sleep 5
rm -f /tmp/jnode.kdb
# ANCHOR-L2-211: this startvm used to be swallowed too, so a front end that
# died during startup (e.g. setuid neutralised by NoNewPrivs) fell through to
# the full 40x10s poll and then reported "timeout" -- indistinguishable from a
# guest that was merely slow. Fail at once and name the reason in the marker
# file the caller reads.
if ! vboxmanage startvm "JNode" --type headless >/tmp/jnode-boot-start.err 2>&1; then
  echo "startfailed" > /tmp/jnode-ready
  exit 1
fi
S=${JNODE_SERIAL_SCRIPTS:-$HOME/.config/opencode/skills/jnode-serial/scripts}
i=0
while [ $i -lt 40 ]; do
  sleep 10
  i=$((i + 1))
  if tr -d '\r' < /tmp/jnode.kdb 2>/dev/null | grep -qa panic; then
    # A panicking guest never reaches a shell. Fail fast instead of polling
    # for minutes: usually the CD still carries the L2 bootimage, because a
    # host-phase build (-Djnode.compiler=L2) overwrote the ISO. Rebuild the
    # oracle ISO (isobuild) before the live phases.
    echo "panic" > /tmp/jnode-ready
    exit 1
  fi
  if python3 $S/serial_cmd.py --timeout 15 "echo alive" 2>/dev/null | grep -q "alive"; then
    echo "$((i * 10))" > /tmp/jnode-ready
    exit 0
  fi
done
echo "timeout" > /tmp/jnode-ready
exit 1

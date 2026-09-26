#!/bin/sh
# Boot JNode and wait for the serial shell by PROBING, not by sleeping.
# Writes /tmp/jnode-ready with the elapsed seconds when the guest answers.
rm -f /tmp/jnode-ready
vboxmanage controlvm "${JNODE_VM:-JNode}" poweroff >/dev/null 2>&1
sleep 5
rm -f /tmp/jnode.kdb
vboxmanage startvm "JNode" --type headless >/dev/null 2>&1
S=${JNODE_SERIAL_SCRIPTS:-$HOME/.config/opencode/skills/jnode-serial/scripts}
i=0
while [ $i -lt 40 ]; do
  sleep 10
  i=$((i + 1))
  if python3 $S/serial_cmd.py --timeout 15 "echo alive" 2>/dev/null | grep -q "alive"; then
    echo "$((i * 10))" > /tmp/jnode-ready
    exit 0
  fi
done
echo "timeout" > /tmp/jnode-ready
exit 1

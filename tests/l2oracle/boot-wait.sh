#!/bin/sh
# Boot JNode and wait for the serial shell by PROBING, not by sleeping.
# Writes /tmp/jnode-ready with the elapsed seconds when the guest answers.
#
# ANCHOR-L2-243 (instance mode): with JNODE_L2_INSTANCE set, the endpoints
# are the jnode-serial instance namespace (agent.sock, boot.log) of the
# VM named by JNODE_VM (created once via jnode-vbox
# `config --name <vm> --instance <inst> --iso <iso>`): a dedicated VM,
# its own UART wiring, slot and log -- co-running legs on the shared
# "JNode" VM or on the legacy /tmp/jnode.kdb pipe cannot touch this boot
# and vice versa. Unset keeps the legacy shared-VM behavior.
VI=${JNODE_L2_INSTANCE:-}
VMN=${JNODE_VM:-JNode}
S=${JNODE_SERIAL_SCRIPTS:-$HOME/.config/opencode/skills/jnode-serial/scripts}
SERIAL_FALLBACK=$(dirname "$0")/../../.opencode/skills/jnode-serial/scripts
[ -d "$S" ] || S=$(cd "$SERIAL_FALLBACK" 2>/dev/null && pwd) || S="$SERIAL_FALLBACK"
KSCAN=/tmp/jnode.kdb
SI=""
if [ -n "$VI" ]; then
  if ! eval "$(python3 "$S/jnode_paths.py" --env --instance "$VI")" ||
     [ -z "${JNODE_AGENT_SOCK:-}" ]; then
    echo "boot-wait: cannot resolve instance '$VI'" >&2
    echo "startfailed" > /tmp/jnode-ready
    exit 1
  fi
  KSCAN=$JNODE_BOOT_LOG
  SI="--instance $VI"
fi
rm -f /tmp/jnode-ready
vboxmanage controlvm "$VMN" poweroff >/dev/null 2>&1
sleep 5
if [ -f "$KSCAN" ]; then
  # Instance mode: the log belongs to this instance; a stale one from an
  # earlier boot must not read like a panic of THIS one.
  [ -n "$VI" ] && rm -f "$KSCAN"
fi
[ -n "$VI" ] || rm -f /tmp/jnode.kdb
# ANCHOR-L2-211: this startvm used to be swallowed too, so a front end that
# died during startup (e.g. setuid neutralised by NoNewPrivs) fell through to
# the full 40x10s poll and then reported "timeout" -- indistinguishable from a
# guest that was merely slow. Fail at once and name the reason in the marker
# file the caller reads.
if ! vboxmanage startvm "$VMN" --type headless >/tmp/jnode-boot-start.err 2>&1; then
  echo "startfailed" > /tmp/jnode-ready
  exit 1
fi
i=0
while [ $i -lt 40 ]; do
  sleep 10
  i=$((i + 1))
  if tr -d '\r' < "$KSCAN" 2>/dev/null | grep -qa panic; then
    # A panicking guest never reaches a shell. Fail fast instead of polling
    # for minutes: usually the CD still carries the L2 bootimage, because a
    # host-phase build (-Djnode.compiler=L2) overwrote the ISO. Rebuild the
    # oracle ISO (isobuild) before the live phases.
    echo "panic" > /tmp/jnode-ready
    exit 1
  fi
  if python3 $S/serial_cmd.py $SI --timeout 15 "echo alive" 2>/dev/null | grep -q "alive"; then
    echo "$((i * 10))" > /tmp/jnode-ready
    exit 0
  fi
done
echo "timeout" > /tmp/jnode-ready
exit 1

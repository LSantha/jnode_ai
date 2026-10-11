#!/bin/bash
# Boot JNode headless in QEMU with per-instance serial resources.
#
# Every VM owns its own sockets/log (jnode_paths.py from the jnode-serial
# skill): /tmp/jnode.serial2 + /tmp/qemu_serial.log for the legacy
# "default" instance, /tmp/jnode/<id>/... for every other one. Several
# QEMU/VirtualBox VMs can therefore run side by side.
#
# Usage:
#   start_qemu.sh [options] [simple|full] [iso-path] [entry]
#
# Modes:
#   simple (default)  UART1 -> boot log file, UART2 -> agent console socket
#   full              UART1 -> KDB socket + boot log, UART2 -> agent console
#
# Options:
#   -n, --instance ID   instance id (default: $JNODE_INSTANCE, else auto:
#                       "default" when free, otherwise qemu-1, qemu-2, ...)
#   --wait-ready SECS   block until the boot log shows "Serial console
#                       available" (fails when the deadline passes)
#   --stop              kill the QEMU behind an instance and forget it
#   --dry-run           print the resolved instance/paths/QEMU command
#   -h, --help          this text
#
# entry: GRUB menu index of a standard cdrom ISO (0-5, default 0). Menus
# built for CI have a single entry with timeout 0 -- entry is a no-op there.
#
# The QEMU process is backgrounded with </dev/null >/dev/null 2>&1 & so
# this script never blocks the caller; always go through this script
# instead of a raw qemu-system-x86_64 line.
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
SERIAL_SCRIPTS=$(cd "$SCRIPT_DIR/../../jnode-serial/scripts" && pwd)
PATHS_PY="$SERIAL_SCRIPTS/jnode_paths.py"

MODE="simple"
ISO="all/build/cdroms/jnode-x86-lite.iso"
ENTRY="0"
INSTANCE="${JNODE_INSTANCE:-}"
WAIT_READY=""
STOP=0
DRY=0

usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -n|--instance) INSTANCE="${2:?--instance needs an id}"; shift 2 ;;
    --wait-ready) WAIT_READY="${2:?--wait-ready needs seconds}"; shift 2 ;;
    --stop) STOP=1; shift ;;
    --dry-run) DRY=1; shift ;;
    simple|full) MODE="$1"; shift ;;
    -*)
      echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *)
      # positional: iso path and/or entry number, in any order
      case "$1" in
        *[!0-9]*) ISO="$1" ;;
        *) ENTRY="$1" ;;
      esac
      shift ;;
  esac
done

if [ "$ENTRY" -lt 0 ] || [ "$ENTRY" -gt 5 ] 2>/dev/null; then
  echo "ENTRY must be 0-5 (GRUB menu index) for a standard cdrom ISO"
  echo "  0 = JNode (default)        3 = all plugins (VESA)"
  echo "  1 = all plugins            4 = tests"
  echo "  2 = minimal shell          5 = via dhcp"
  exit 1
fi

# ---------------------------------------------------------------- instance
if [ -z "$INSTANCE" ]; then
  INSTANCE=$(python3 "$PATHS_PY" --alloc --prefix qemu)
fi

if [ "$STOP" = "1" ]; then
  ENTRY_JSON=$(python3 "$PATHS_PY" --field registry_path --instance "$INSTANCE")
  if [ ! -f "$ENTRY_JSON" ]; then
    echo "ERROR: no registry entry for instance '$INSTANCE'"
    python3 "$PATHS_PY" --list || true
    exit 1
  fi
  HYP=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('hypervisor') or '')" "$ENTRY_JSON")
  PID=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('vm_pid') or '')" "$ENTRY_JSON")
  if [ "$HYP" != "qemu" ]; then
    echo "ERROR: instance '$INSTANCE' is hypervisor=$HYP (use jnode-vbox for VirtualBox)"
    exit 1
  fi
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
    kill -9 "$PID" 2>/dev/null || true
    echo "stopped qemu pid $PID (instance $INSTANCE)"
  else
    echo "instance '$INSTANCE': no running qemu pid"
  fi
  python3 "$PATHS_PY" --unregister --instance "$INSTANCE"
  eval "$(python3 "$PATHS_PY" --env --instance "$INSTANCE")"
  rm -f "$JNODE_AGENT_SOCK" "$JNODE_MONITOR_SOCK" "$JNODE_KDB_SOCK"
  exit 0
fi

if python3 "$PATHS_PY" --is-live --instance "$INSTANCE" >/dev/null 2>&1; then
  echo "ERROR: instance '$INSTANCE' already has a live VM"
  echo "       pick another id (--instance) or stop it (--stop)"
  exit 1
fi

eval "$(python3 "$PATHS_PY" --env --instance "$INSTANCE")"
python3 "$PATHS_PY" --mkdir --instance "$INSTANCE" >/dev/null

QEMU_NAME="JNode x86"
[ "$INSTANCE" != "default" ] && QEMU_NAME="JNode x86 [$INSTANCE]"

LAUNCH_LOG=$(python3 "$PATHS_PY" --field stderr_log --instance "$INSTANCE")

if [ "$DRY" = "1" ]; then
  echo "instance:  $INSTANCE"
  echo "mode:      $MODE"
  echo "iso:       $ISO"
  echo "entry:     $ENTRY"
  echo "agent:     $JNODE_AGENT_SOCK"
  echo "kdb:       $JNODE_KDB_SOCK"
  echo "boot log:  $JNODE_BOOT_LOG"
  echo "monitor:   $JNODE_MONITOR_SOCK"
  echo "launch log:$LAUNCH_LOG"
  echo "name:      $QEMU_NAME"
  exit 0
fi

if [ ! -f "$ISO" ]; then
  echo "ERROR: ISO not found: $ISO"
  echo "       build it first: sh build.sh cd-x86-lite"
  exit 1
fi

# Only this instance's resources are cleared -- never another VM's.
rm -f "$JNODE_AGENT_SOCK" "$JNODE_MONITOR_SOCK" "$JNODE_BOOT_LOG"
if [ "$MODE" = "full" ]; then rm -f "$JNODE_KDB_SOCK"; fi

# ------------------------------------------------------------------- boot
ARGS=(-machine accel=kvm:tcg -m "${JNODE_QEMU_MEM:-1024}"
      -name "$QEMU_NAME" -cdrom "$ISO" -no-reboot -display none)

if [ "$MODE" = "full" ]; then
  ARGS+=(-chardev "socket,id=com1,path=$JNODE_KDB_SOCK,server=on,wait=off,logfile=$JNODE_BOOT_LOG"
         -serial chardev:com1
         -serial "unix:$JNODE_AGENT_SOCK,server,nowait")
else
  ARGS+=(-serial "file:$JNODE_BOOT_LOG"
         -serial "unix:$JNODE_AGENT_SOCK,server,nowait")
fi
ARGS+=(-monitor "unix:$JNODE_MONITOR_SOCK,server,nowait")

qemu-system-x86_64 "${ARGS[@]}" ${JNODE_QEMU_ARGS:-} \
    </dev/null >"$LAUNCH_LOG" 2>&1 &
QEMU_PID=$!

python3 "$PATHS_PY" --register --instance "$INSTANCE" --hypervisor qemu \
    --vm-name "$QEMU_NAME" --vm-pid "$QEMU_PID" --iso "$ISO" \
    --state starting >/dev/null
echo "QEMU started (pid $QEMU_PID, instance $INSTANCE, mode $MODE)"

# ------------------------------------------------------- GRUB entry choice
# The menu renders on VGA only (GRUB 0.97; menu.lst enables `serial` per
# entry for the kernel, not for the menu), so selection goes through the
# QEMU monitor with verification: grub_pick.py screendumps the screen,
# finds the highlight bar, sends HOME/DOWN and checks the bar moved,
# then ENTER. Blind key bursts are unreliable (keys sent before GRUB
# polls int16 linger in the BIOS buffer; rapid batches get dropped).
# Tune with GRUB_TIMEOUT=<secs> (default 25). Needs a menu with
# timeout > 0 (CI menus boot entry 0 regardless).
if [ "$ENTRY" != "0" ]; then
  GRUB_TIMEOUT="${GRUB_TIMEOUT:-25}"
  for i in $(seq 1 10); do
    [ -S "$JNODE_MONITOR_SOCK" ] && { echo "Monitor socket ready after ${i}s"; break; }
    sleep 1
  done
  if [ ! -S "$JNODE_MONITOR_SOCK" ]; then
    echo "WARNING: monitor socket not ready, cannot select GRUB entry #${ENTRY}; boots default"
  elif ! python3 "$SCRIPT_DIR/grub_pick.py" --monitor "$JNODE_MONITOR_SOCK" \
          --entry "$ENTRY" --timeout "$GRUB_TIMEOUT" --boot-log "$JNODE_BOOT_LOG"; then
    echo "WARNING: GRUB entry #${ENTRY} selection failed; VM may boot the default entry"
    echo "         inspect: pnmtopng $JNODE_MONITOR_SOCK.last.ppm | display -"
  fi
fi

# --------------------------------------------------------------- readiness
for i in $(seq 1 30); do
  [ -S "$JNODE_AGENT_SOCK" ] && { echo "Agent socket ready after ${i}s"; break; }
  sleep 1
done
if [ ! -S "$JNODE_AGENT_SOCK" ]; then
  echo "WARNING: agent socket not visible after 30s; check $LAUNCH_LOG"
fi

if [ -n "$WAIT_READY" ]; then
  echo "Waiting up to ${WAIT_READY}s for the serial console..."
  deadline=$(( $(date +%s) + WAIT_READY ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if grep -q "Serial console available" "$JNODE_BOOT_LOG" 2>/dev/null; then
      echo "Serial console is up (boot log: $JNODE_BOOT_LOG)"
      break
    fi
    sleep 2
  done
  if ! grep -q "Serial console available" "$JNODE_BOOT_LOG" 2>/dev/null; then
    echo "ERROR: serial console not up after ${WAIT_READY}s"
    echo "       boot log: $JNODE_BOOT_LOG"
    tail -n 20 "$LAUNCH_LOG" 2>/dev/null || true
    exit 1
  fi
fi

python3 "$PATHS_PY" --register --instance "$INSTANCE" --hypervisor qemu \
    --vm-name "$QEMU_NAME" --vm-pid "$QEMU_PID" --iso "$ISO" \
    --state ready >/dev/null

cat <<SUMMARY
instance:  $INSTANCE
agent:     $JNODE_AGENT_SOCK
kdb:       $JNODE_KDB_SOCK
boot log:  $JNODE_BOOT_LOG
monitor:   $JNODE_MONITOR_SOCK
next:      python3 $SERIAL_SCRIPTS/serial_cmd.py --instance $INSTANCE --wait 300 "date"
stop:      $0 --stop --instance $INSTANCE
SUMMARY

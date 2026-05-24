#!/bin/bash
set -e

MODE="${1:-simple}"
ISO="${2:-all/build/cdroms/jnode-x86-lite.iso}"
ENTRY="${3:-0}"

# Validate entry
if [ "$ENTRY" -lt 0 ] || [ "$ENTRY" -gt 5 ] 2>/dev/null; then
  echo "ENTRY must be 0-5 (GRUB menu index)"
  echo "  0 = JNode (default)      — has kdb+lkd serial logging"
  echo "  1 = JNode (all plugins)"
  echo "  2 = JNode (minimal shell)"
  echo "  3 = JNode (all plugins, VESA mode)"
  echo "  4 = JNode tests (all plugins + tests)"
  echo "  5 = JNode via dhcp (all plugins)"
  exit 1
fi

# Add monitor when booting non-default entry
MONITOR=""
if [ "$ENTRY" != "0" ]; then
  MONITOR="-monitor unix:/tmp/qemu_monitor.sock,server,nowait"
fi

case "$MODE" in
  simple)
    rm -f /tmp/qemu_serial.log /tmp/jnode.serial2 /tmp/qemu_monitor.sock
    qemu-system-x86_64 \
      -machine accel=kvm:tcg \
      -m 1024 \
      -name "JNode x86" \
      -cdrom "$ISO" \
      -serial file:/tmp/qemu_serial.log \
      -serial unix:/tmp/jnode.serial2,server,nowait \
      $MONITOR \
      -no-reboot \
      -display none \
      </dev/null >/dev/null 2>&1 &
    ;;
  full)
    rm -f /tmp/qemu_serial.log /tmp/jnode.serial2 /tmp/jnode.kdb /tmp/qemu_monitor.sock
    qemu-system-x86_64 \
      -machine accel=kvm:tcg \
      -m 1024 \
      -name "JNode x86" \
      -cdrom "$ISO" \
      -chardev socket,id=com1,path=/tmp/jnode.kdb,server=on,wait=off,logfile=/tmp/qemu_serial.log \
      -serial chardev:com1 \
      -serial unix:/tmp/jnode.serial2,server,nowait \
      $MONITOR \
      -no-reboot \
      -display none \
      </dev/null >/dev/null 2>&1 &
    ;;
  *)
    echo "Usage: $0 [simple|full] [iso-path] [entry]"
    echo "  entry: 0-5 (GRUB menu index, default: 0)"
    exit 1
    ;;
esac

QEMU_PID=$!
echo "QEMU started (PID: $QEMU_PID)"

# Send keystrokes for non-default GRUB entry
if [ "$ENTRY" != "0" ]; then
  # Wait for monitor socket
  for i in $(seq 1 10); do
    if [ -S /tmp/qemu_monitor.sock ]; then
      echo "Monitor socket ready after ${i}s"
      break
    fi
    sleep 1
  done
  if [ ! -S /tmp/qemu_monitor.sock ]; then
    echo "WARNING: Monitor socket not ready, GRUB may have already booted default"
  else
    # Send N×DOWN + ENTER to select entry N
    KEYS=""
    for ((e=0; e<ENTRY; e++)); do
      KEYS+="sendkey down\n"
    done
    KEYS+="sendkey ret\n"
    printf "$KEYS" | socat - UNIX-CONNECT:/tmp/qemu_monitor.sock 2>/dev/null || true
    echo "Sent ${ENTRY}×DOWN + ENTER to GRUB (entry #${ENTRY})"
  fi
fi

# Wait for serial socket
for i in $(seq 1 30); do
  if [ -S /tmp/jnode.serial2 ]; then
    echo "Serial socket ready after ${i}s"
    exit 0
  fi
  sleep 1
done

echo "WARNING: Serial socket not visible after 30s, QEMU may still be booting"
exit 0

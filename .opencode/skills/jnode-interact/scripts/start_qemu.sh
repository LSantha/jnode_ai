#!/bin/bash
set -e

MODE="${1:-simple}"
ISO="${2:-all/build/cdroms/jnode-x86-lite.iso}"

case "$MODE" in
  simple)
    rm -f /tmp/qemu_serial.log /tmp/jnode.serial2
    qemu-system-x86_64 \
      -machine accel=kvm:tcg \
      -m 1024 \
      -name "JNode x86" \
      -cdrom "$ISO" \
      -serial file:/tmp/qemu_serial.log \
      -serial unix:/tmp/jnode.serial2,server,nowait \
      -no-reboot \
      -display none \
      </dev/null >/dev/null 2>&1 &
    ;;
  full)
    rm -f /tmp/qemu_serial.log /tmp/jnode.serial2 /tmp/jnode.kdb
    qemu-system-x86_64 \
      -machine accel=kvm:tcg \
      -m 1024 \
      -name "JNode x86" \
      -cdrom "$ISO" \
      -chardev socket,id=com1,path=/tmp/jnode.kdb,server=on,wait=off,logfile=/tmp/qemu_serial.log \
      -serial chardev:com1 \
      -serial unix:/tmp/jnode.serial2,server,nowait \
      -no-reboot \
      -display none \
      </dev/null >/dev/null 2>&1 &
    ;;
  *)
    echo "Usage: $0 [simple|full] [iso-path]"
    exit 1
    ;;
esac

QEMU_PID=$!
echo "QEMU started (PID: $QEMU_PID)"

for i in $(seq 1 30); do
  if [ -S /tmp/jnode.serial2 ]; then
    echo "Serial socket ready after ${i}s"
    exit 0
  fi
  sleep 1
done

echo "WARNING: Serial socket not visible after 30s, QEMU may still be booting"
exit 0

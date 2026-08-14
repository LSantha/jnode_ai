#!/bin/sh

ISO="${JNODE_ISO:-all/build/cdroms/jnode-x86-lite.iso}"
SERIAL_LOG="${JNODE_SERIAL_LOG:-/tmp/jnode_serial.log}"

qemu-system-x86_64 \
    -machine accel=kvm:tcg \
    -m 1024 \
    -cpu pentium \
    -boot once=d,menu=off \
    -cdrom "$ISO" \
    -serial file:"$SERIAL_LOG" \
    -net none \
    -usb \
    -vga vmware \
    -name "JNode x86" \
    $JNODE_QEMU_ARGS "$@"

echo "Serial log: $SERIAL_LOG"


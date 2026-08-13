# Running JNode in QEMU

## Basic Boot

```bash
# Minimal, serial-only (fastest for testing)
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 512 -serial stdio -display none
```

## With Serial Log File

```bash
# Capture boot log to file + see it live
rm -f /tmp/jnode-serial.log
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -M pc -cpu pentium -boot once=d \
  -serial file:/tmp/jnode-serial.log -display none &
tail -f /tmp/jnode-serial.log
```

## With Networking (User-mode / SLIRP)

```bash
# User-mode networking (no root needed)
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -netdev user,id=net0 -device rtl8139,netdev=net0 \
  -serial stdio -display none
```

Inside JNode:
```bash
jnode> dhcp eth0
jnode> ping 10.0.2.2   # Host gateway
```

## With Bridged Networking (Requires Root)

```bash
# Replace br0 with your bridge interface
sudo qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -netdev bridge,id=net0,br=br0 -device rtl8139,netdev=net0 \
  -serial stdio -display none
```

## With KVM Acceleration (Linux Host)

```bash
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -enable-kvm -cpu host \
  -serial stdio -display none
```

## x86_64 Build

```bash
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86_64-lite.iso \
  -m 1024 -cpu qemu64 -serial stdio -display none
```

## Debugging with GDB

```bash
# Terminal 1: Start QEMU paused at boot
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 512 -s -S -serial stdio -display none

# Terminal 2: Connect GDB to kernel entry
gdb -ex "target remote localhost:1234" \
    -ex "set architecture i386" \
    -ex "break _start" \
    -ex "continue"
```

## Useful QEMU Options

| Option | Purpose |
|--------|---------|
| `-serial stdio` | Serial console on terminal |
| `-serial file:log.txt` | Serial to file |
| `-serial telnet::4444,server,nowait` | Telnet to port 4444 |
| `-display none` | Headless (no GUI window) |
| `-m 1024` | 1 GB RAM |
| `-cpu pentium` | 32-bit Pentium class (required for 32-bit ISO) |
| `-cpu qemu64` | 64-bit CPU |
| `-enable-kvm` | KVM acceleration |
| `-s -S` | GDB server on :1234, pause at start |

## CI Configuration

The CI uses `.github/qemu/jnode.properties` and boots with:

```bash
qemu-system-x86_64 -cdrom ./all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -M pc -cpu pentium -boot once=d,menu=off -net none \
  -serial file:/tmp/com1.txt -display none
```

See [QEMU Wiki](https://github.com/LSantha/jnode_ai/wiki/Boot-Sequence) for boot process details.

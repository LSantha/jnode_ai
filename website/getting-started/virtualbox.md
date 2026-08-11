# Running JNode in VirtualBox

VirtualBox is required for **JDWP remote debugging** and provides a more desktop-like experience.

## Create VM

1. **New VM** → Name: `JNode` → Type: `Linux` → Version: `Other Linux (32-bit)`
2. **Memory**: 1024 MB (minimum 512 MB)
3. **Hard Disk**: *Do not add a virtual hard disk* — we boot from ISO
4. **System → Motherboard**: Enable **I/O APIC**
5. **System → Processor**: 1 CPU, enable **PAE/NX**
6. **Storage → Controller: IDE**: Add **Optical Drive** → Choose `jnode-x86-lite.iso`
7. **Audio**: Disable (JNode has no audio driver yet)
8. **Network → Adapter 1**: **NAT** (for JDWP) or **Bridged Adapter**

## Serial Console (Required for JDWP)

1. **Ports → Serial Port 1**: Enable
   - Port Number: `COM1`
   - Port Mode: **Host Pipe**
   - Path: `/tmp/jnode-com1` (Linux/macOS) or `\\.\pipe\jnode-com1` (Windows)
   - Check **Create Pipe**

## Start VM & Connect

```bash
# Terminal 1: Start VM (headless or GUI)
VBoxManage startvm "JNode" --type headless

# Terminal 2: Connect to serial console
socat -,raw,echo=0 UNIX-CONNECT:/tmp/jnode-com1
# Or on Windows: use PuTTY → Serial → COM1
```

You'll see the boot banner and shell prompt.

## JDWP Remote Debugging

JNode listens for JDWP on port **8000** by default (configured in `jnode.properties`).

### Port Forwarding (NAT)

```bash
VBoxManage modifyvm "JNode" --natpf1 "jdwp,tcp,,8000,,8000"
```

### Connect with jdb

```bash
jdb -attach localhost:8000
```

### Supported JDWP Features

| Feature | Status |
|---------|--------|
| Classes, methods, fields | ✅ |
| Threads, thread groups | ✅ |
| `where` (stack traces) | ✅ |
| `classpath` | ✅ |
| Method invocation | ✅ |
| Constructors | ✅ |
| Watchpoints | ❌ |
| Breakpoints | ⚠️ Limited |

See [JDWP Setup Wiki](https://github.com/LSantha/jnode_ai.wiki/wiki/GDB-Debugging-Support) and [JDWP Tests](../development/testing.md#jdwp-integration-tests).

## Shared Folders (Optional)

For file transfer between host and JNode:

1. **Shared Folders** → Add folder → Auto-mount
2. In JNode (after network is up):
   ```bash
   jnode> mount -t vboxsf share_name /mnt/share
   ```

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Boot hangs at "Loading..." | Ensure **I/O APIC** enabled, try `-cpu pentium` in QEMU first |
| No serial output | Verify pipe path, check `socat` / PuTTY connection |
| JDWP connection refused | Check port forwarding, firewall, `jnode.properties` `jdwp.port=8000` |
| Network not working | Try Bridged Adapter instead of NAT; check `dhcp eth0` |

## VM Export for Distribution

```bash
VBoxManage export "JNode" --output jnode-vm.ova
```

The `.ova` can be imported on any VirtualBox host.

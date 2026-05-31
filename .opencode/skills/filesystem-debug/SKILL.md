---
name: filesystem-debug
description: Debug filesystem issues in JNode - analyze test failures, decode structures, test in VM
license: MIT
metadata:
  version: 1.0.0
  author: opencode
---

# Skill: Filesystem Debug

This skill provides a systematic workflow for debugging filesystem issues in JNode.

## What I do

1. **Analyze test failures** from XML JUnit reports
2. **Decode filesystem structures** (BPB, boot sectors) with Python scripts
3. **Build JNode** and run in VM for runtime testing
4. **Validate fixes** both unit-test and VM level

## When to use me

Use this skill when debugging:
- FAT/NTFS/ext2 filesystem mounting failures
- Boot sector parsing errors
- Cluster chain traversal issues
- File read/write problems in JNode

## How to use

```bash
skill({ name: "filesystem-debug" })
```

## Workflow

### 1. Parse Test Failure Reports

```bash
# Extract full failure details from JUnit XML
grep -A30 'failure message="illegal entry"' build/reports/junit/TEST-*.xml

# Check specific error patterns
grep 'Stacktrace\|Exception\|at org.jnode.fs' build/reports/junit/TEST-*.xml
```

### 2. Decode Boot Sector Structures

```python
import struct
with open('test.fat32', 'rb') as f:
    bs = f.read(512)

# FAT32 BPB fields
root_ent = struct.unpack('<H', bs[0x11:0x13])[0]
fat_sz16 = struct.unpack('<H', bs[0x16:0x18])[0]
tot_sec32 = struct.unpack('<I', bs[0x20:0x24])[0]

# FAT32 detection per Microsoft spec
is_fat32 = (root_ent == 0 and fat_sz16 == 0)
```

### 3. Build and Test in VM

```bash
# Build ISO
sh build.sh cd-x86-lite

# For VirtualBox: check storage attachment and start VM
# (pre-attached storage in VM config)
VBoxManage showvminfo "JNode" | grep Location
VBoxManage startvm "JNode" --type headless
sleep 35

# For QEMU: start with FAT32 test image attached
rm -f /tmp/jnode.serial2 /tmp/qemu_serial.log /tmp/qemu_monitor.sock
qemu-system-x86_64 \
  -machine accel=kvm:tcg -m 1024 \
  -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -drive file=/tmp/fat32-working.img,format=raw \
  -serial file:/tmp/qemu_serial.log \
  -serial unix:/tmp/jnode.serial2,server,nowait \
  -no-reboot -display none \
  </dev/null >/dev/null 2>&1 &

# Create symlink and test commands (required for jnode_agent_cmd.py)
ln -sf /tmp/jnode.serial2 /tmp/jnode_com2
python3 .opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py "mount"
```

**Note:** For QEMU testing, use pre-created `/tmp/fat32-working.img` or create one:
```bash
truncate -s 50M /tmp/test.img
mkfs.fat -F32 /tmp/test.img
```

### 4. Create Test Images

```bash
# Create FAT32 test image
mkfs.fat -F32 /tmp/test-fat32.img

# Or with specific size
truncate -s 50M /tmp/test-fat32.img
mkfs.fat -F32 /tmp/test-fat32.img
```

## Key Files

- **Test reports**: `fs/build/reports/junit/TEST-*.xml`
- **Boot sector**: `fs/src/fs/org/jnode/fs/jfat/BootSector.java`
- **FAT table**: `fs/src/fs/org/jnode/fs/jfat/Fat*.java`
- **QEMU helper**: `.opencode/skills/jnode-interact/scripts/start_qemu.sh`
- **Serial agent**: `.opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py`

## Filesystem Validation via Shell

| Command | Purpose |
|---------|---------|
| `ls /devices/hdc0` | List mounted FAT32 partition contents |
| `cat /devices/hdc0/test.txt` | Read file contents from FAT32 |
| `echo text > /devices/hdc0/file.txt` | Create/write file on FAT32 |
| `mkdir /devices/hdc0/dir` | Create directory on FAT32 |
| `rm /devices/hdc0/file.txt` | Delete file on FAT32 |
| `rm -r /devices/hdc0/dir` | Delete directory recursively |
| `mount` | Show all mounted filesystems |

**Validation workflow:**
1. `ls /devices/hdc0` - verify directory listing
2. `cat /devices/hdc0/test.txt` - verify read access
3. `echo test > /devices/hdc0/newfile.txt` - verify write/create
4. `ls /devices/hdc0/newfile.txt` - verify file exists
5. `rm /devices/hdc0/newfile.txt` - verify delete
6. `rm -r /devices/hdc0/testdir` - verify directory removal

## Common Checks

| Error | Root Cause | Fix Location |
|-------|-----------|--------------|
| `illegal entry: 2` | `CountOfClusters` not computed for FAT32 | `BootSector.compute()` |
| `IllegalAccessError` | Wrong BPB field offsets | `BootSector.decode()` |
| Infinite loop in scan | Chain termination not detected | `FatChain.read()`, `FatEntriesFactory` |
| Mount fails silently | FAT type misdetected | `BootSector.isFat32()` |
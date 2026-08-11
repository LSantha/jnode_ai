# Reference

## Shell Commands

### File System

| Command | Description |
|---------|-------------|
| `ls [path]` | List directory |
| `cd <path>` | Change directory |
| `pwd` | Print working directory |
| `cat <file>` | Display file |
| `cp <src> <dst>` | Copy file |
| `mv <src> <dst>` | Move/rename |
| `rm <file>` | Remove file |
| `mkdir <dir>` | Create directory |
| `rmdir <dir>` | Remove empty directory |
| `find <path> <pattern>` | Find files |

### Process & Memory

| Command | Description |
|---------|-------------|
| `ps` | List processes/isolates |
| `kill <pid>` | Terminate isolate |
| `mem` | Memory usage summary |
| `vminfo` | VM version, build ID, uptime |
| `gc` | Trigger garbage collection |

### System

| Command | Description |
|---------|-------------|
| `help [cmd]` | Show help |
| `version` | Show JNode version |
| `reboot` | Reboot system |
| `shutdown` | Power off |
| `plugins` | List loaded plugins |
| `devices` | List devices |
| `drivers` | List drivers |
| `mount` | Show mounts |
| `dhcp <iface>` | DHCP request |

### Network

| Command | Description |
|---------|-------------|
| `ping <host>` | ICMP echo |
| `netstat` | Network connections |
| `ifconfig <iface>` | Interface config |
| `route` | Routing table |
| `dns <name>` | DNS lookup |

### Debug

| Command | Description |
|---------|-------------|
| `kdb` | Enter kernel debugger |
| `threads` | Thread dump |
| `isolates` | Isolate dump |
| `heap` | Heap histogram |

---

## Boot Parameters

Passed via GRUB kernel command line:

| Parameter | Description | Default |
|-----------|-------------|---------|
| `console=ttyS0` | Serial console | `ttyS0` |
| `console=tty0` | VGA console | — |
| `mem=512M` | Memory limit | Auto-detect |
| `plugins=full` | Plugin list | `default` |
| `debug=jdwp` | Enable JDWP | Disabled |
| `jdwp.port=8000` | JDWP port | `8000` |
| `loglevel=DEBUG` | Boot log level | `INFO` |

Example GRUB entry:
```grub
menuentry "JNode (debug)" {
    multiboot /boot/kernel.gz console=ttyS0 debug=jdwp loglevel=DEBUG
    module /boot/bootimage.gz
}
```

---

## Configuration Files

### jnode.properties

Main build/runtime configuration:

```properties
# Architecture
jnode.bits=32
jnode.memmgr.plugin.id=org.jnode.vm.memmgr.mmtk.MMTkMemoryManager
jnode.compiler=l1

# Plugins
jnode.enable.jnasm=true
jnode.plugin.list=default-plugin-list.xml

# Network
jnode.net.dhcp=true
jnode.net.ipv4.forwarding=false

# Debug
jnode.jdwp.enabled=false
jnode.jdwp.port=8000
jnode.jdwp.suspend=n
```

Location: `jnode.properties` (root) or `-Dbuild.properties.file=...`

### Plugin Lists

| File | Description |
|------|-------------|
| `all/conf/default-plugin-list.xml` | Minimal: kernel, shell, basic FS, net |
| `all/conf/full-plugin-list.xml` | Full: GUI, all FS, all drivers, dev tools |

Selected via `jnode.plugin.list` property.

### BootImageBuilder Config

Controls boot image generation (`all/conf/bootimage.xml`):

- Class filtering (include/exclude)
- Native library bundling
- Plugin pre-loading
- VM options baked in

---

## Build Properties

| Property | Default | Description |
|----------|---------|-------------|
| `jnode.bits` | `32` | Target: `32` or `64` |
| `jnode.memmgr.plugin.id` | MMTk | GC implementation |
| `jnode.compiler` | `l1` | JIT: `l1`, `l2`, `none` |
| `jnode.enable.jnasm` | `true` | Use JNasm assembler |
| `jnode.plugin.list` | `default` | Plugin set |
| `build.native.only` | `false` | Skip Java compile |
| `skip.tests` | `false` | Skip JUnit tests |

---

## Environment Variables

| Variable | Used By | Description |
|----------|---------|-------------|
| `JAVA_HOME` | `build.sh` | JDK 8 location |
| `ANT_HOME` | `build.sh` | Ant installation |
| `CLASSLIB_URL` | Build | Override classlib download URL |
| `GH_PAT` | CI | GitHub PAT for wiki clone |
| `OPENCODE_API_KEY` | CI | OpenCode agent API key |

---

## Version Identifiers

Since [c7f8352](https://github.com/LSantha/jnode_ai_oc/commit/c7f835273):

```
jnode-vm-ver = 0.2.9-dev+<git-describe>-<timestamp>
```

Example: `0.2.9-dev+gabc1234-dirty-20260811-143022`

Available at:
- `java.vm.version` system property
- `vminfo` command output
- Boot banner
- `<iso>.vm-version` sidecar file

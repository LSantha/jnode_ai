# Reference

## Shell Commands

### File System

| Command | Description |
|---------|-------------|
| `ls` / `dir` | List directory |
| `cd <path>` | Change directory |
| `pwd` | Print working directory |
| `cat <file>` | Display file |
| `cp <src> <dst>` | Copy file |
| `rm` / `del` | Remove file |
| `mkdir <dir>` | Create directory |
| `find <path> <pattern>` | Find files |
| `du`, `df`, `cmp`, `md5sum` | Disk usage, sizes, compare, checksum |

### Process & Memory

| Command | Description |
|---------|-------------|
| `isolate` | List/inspect isolates |
| `thread` | Thread dump |
| `kill <pid>` | Terminate process |
| `terminate` | Terminate an isolate |
| `memory` | Memory usage summary |
| `vminfo` | VM version, build ID, uptime |
| `gc` | Trigger garbage collection |
| `onheap`, `page` | Heap / page info |

### System

| Command | Description |
|---------|-------------|
| `help [cmd]` | Show help |
| `uname` | Show system info |
| `reboot` | Reboot system |
| `halt` | Power off |
| `plugin` | List loaded plugins |
| `device` | List devices |
| `mount` | Show mounts |
| `dhcp <iface>` | DHCP request |
| `env` / `printenv` | Show environment |
| `alias`, `bindkeys`, `locale`, `log4j` | Shell configuration |

### Network

| Command | Description |
|---------|-------------|
| `ping <host>` | ICMP echo |
| `netstat` | Network connections |
| `ifconfig <iface>` | Interface config |
| `route` | Routing table |
| `resolver` | DNS lookup |
| `arp`, `bootp`, `tcpinout`, `wget`, `tftp`, `wlanctl` | Other network tools |

### Debug

| Command | Description |
|---------|-------------|
| `kdb` | Enter kernel debugger |
| `debug` | Start JDWP listener |
| `disasm` | Disassemble bytecode |
| `lsirq` | List interrupt requests |

---

## Boot Parameters

The kernel copies the GRUB command line (multiboot `kernel` line) and exposes it to the system as the `jnode.cmdline` property. Plugins can inspect it at startup.

Known handling:

| Parameter | Description |
|-----------|-------------|
| `debug` | Substring on the command line enables plugin debug mode |

Example GRUB entry:
```grub
menuentry "JNode" {
    multiboot /boot/kernel.gz debug
    module /boot/bootimage.gz
}
```

Beyond the `debug` flag, command-line handling is up to individual plugins reading `jnode.cmdline`. This is an area still under development.

---

## Configuration Files

### jnode.properties

Main build/runtime configuration:

```properties
# Architecture
jnode.bits=32
jnode.memmgr.plugin.id=org.jnode.vm.memmgr.def
jnode.compiler=default

# Plugins
jnode.enable.jnasm=true
target.plugin.list=default
```

`jnode.compiler` accepts `L1A`, `L1B`, or `default` (which resolves to L1A).

Location: `jnode.properties` (root) or `-Dbuild.properties.file=...`

### Plugin Lists

| File | Description |
|------|-------------|
| `all/conf/system-plugin-list.xml` | Core VM/plugin infrastructure, always included in the bootimage |
| `all/conf/default-plugin-list.xml` | Standard set: kernel, shell, FS, net, common commands |
| `all/conf/full-plugin-list.xml` | Default set plus additional/optional plugins |

The jar packager selects `default` or `full` via `target.plugin.list`.

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
| `jnode.memmgr.plugin.id` | `org.jnode.vm.memmgr.def` | Memory manager (`org.jnode.vm.memmgr.mmtk.nogc`/`genrc`/`ms` alternatives) |
| `jnode.compiler` | `default` | JIT compiler (`L1A`, `L1B`, or `default`) |
| `jnode.enable.jnasm` | `true` | Use JNasm assembler |
| `target.plugin.list` | `default` | Plugin set for jar packager |
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

Since [c7f8352](https://github.com/LSantha/jnode_ai/commit/c7f835273):

```
jnode-vm-ver = 0.2.9-dev+<git-describe>-<timestamp>
```

Example: `0.2.9-dev+gabc1234-dirty-20260811-143022`

Available at:
- `java.vm.version` system property
- `vminfo` command output
- Boot banner
- `<iso>.vm-version` sidecar file

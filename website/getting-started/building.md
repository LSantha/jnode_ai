# Building JNode

## Build Script

The main entry point is `build.sh` in the repository root:

```bash
sh build.sh <target> [options]
```

## Main Targets

| Target | Description |
|--------|-------------|
| `assemble` | Compile Java, build native, create bootimage (no ISO) |
| `cd-x86-lite` | **Minimal x86 ISO** — kernel, shell, basic drivers (~15 MB) |
| `cd-x86_64-lite` | Minimal x86_64 ISO (experimental) |
| `cd-x86` | Full x86 ISO with GUI, network, all plugins |
| `tests` | Run all JUnit + regression tests |
| `clean` | Remove all build artifacts |

## Common Options

```bash
# Build with custom properties
sh build.sh -Dbuild.properties.file=my.props cd-x86-lite

# Verbose output
sh build.sh -verbose cd-x86-lite

# Skip tests during build
sh build.sh -Dskip.tests=true cd-x86-lite
```

## Architecture-Specific Builds

### x86 (32-bit) — Default

```bash
sh build.sh cd-x86-lite
# Output: all/build/cdroms/jnode-x86-lite.iso
```

### x86_64 (64-bit) — Experimental

```bash
sh build.sh cd-x86_64-lite
# Output: all/build/cdroms/jnode-x86_64-lite.iso
```

Requires 64-bit capable CPU and QEMU `-cpu qemu64` or similar.

## Build Properties

Key properties in `jnode.properties` or via `-D`:

| Property | Default | Description |
|----------|---------|-------------|
| `jnode.bits` | `32` | Target architecture: `32` or `64` |
| `jnode.memmgr.plugin.id` | `org.jnode.vm.memmgr.mmtk.MMTkMemoryManager` | GC implementation |
| `jnode.compiler` | `l1` | JIT: `l1`, `l2`, or `none` |
| `jnode.enable.jnasm` | `true` | Use JNasm assembler |
| `jnode.plugin.list` | `default-plugin-list.xml` | Plugin set: `default` or `full` |

## Build Output Structure

```
all/build/
├── cdroms/
│   ├── jnode-x86-lite.iso
│   └── jnode-x86-lite.iso.vm-version    # Build identifier
├── upload/                              # Packaged for release
│   ├── jnode-x86-<ver>.iso.gz
│   └── jnode-x86-<ver>.iso.gz.vm-version
├── native/                              # Compiled .o / .so files
└── boot/                                # Bootimage, GRUB config
```

## Build Identifier

Since [commit c7f8352](https://github.com/LSantha/jnode_ai/commit/c7f835273), each build gets a unique `jnode-vm-ver` property:

```
0.2.9-dev+gabc1234-dirty-20260811-143022
```

This appears in:
- `VmImpl` (via `java.vm.version`)
- Boot banner
- `vminfo` command
- Sidecar `.vm-version` file next to each ISO

See [Build System Wiki](https://github.com/LSantha/jnode_ai/wiki/Build-System) for deep dive.

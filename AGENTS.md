# Agent Instructions for JNode

## Project Context

JNode is a Java operating system with custom JVM. ~3,300 Java files, 25 assembly files, organized into sub-projects (core, fs, net, shell, gui, builder, etc.).

## Build System

- **Command**: `sh build.sh <target>` from root
- **Main targets**: `assemble`, `x86`, `x86_64`, `cd-x86-lite`, `cd-x86_64-lite`, `tests`
- **Java**: 1.6, 1.7, or 1.8 required
- **Output**: `all/build/cdroms/jnode-*.iso`
- **Classlib**: Downloaded during build to `all/lib/classlib.jar`

## Key Directories

| Path | Purpose |
|------|---------|
| `core/src/core/` | VM, kernel (579 Java files) |
| `core/src/driver/` | Driver framework (262 files) |
| `core/src/vmmagic/` | VM magic annotations |
| `core/src/native/x86/` | Assembly (kernel.asm, vm.asm, mm32/64.asm, etc.) |
| `core/src/classpath/` | GNU Classpath integration |
| `core/src/openjdk/` | OpenJDK patches |
| `core/src/mmtk-vm/` | MMTk bindings |
| `builder/src/` | Build tools, JNasm assembler, BootImageBuilder |
| `shell/` | Shell commands |
| `fs/` | Filesystems, block drivers |
| `net/` | Network stack |
| `gui/` | AWT, video/input drivers |
| `all/build.xml` | Master build file |

## Running Tests

- **All tests**: `sh build.sh tests` (from root)
- **Per subproject**: `cd <subproject> && ant test`
- **Framework**: JUnit 4.5, JMock, Mockito
- **Test locations**: `<subproject>/src/test/` (390 test files total)

## Boot Testing

1. Build: `sh build.sh cd-x86-lite` or `cd-x86_64-lite`
2. Run: VirtualBox (`.vmx`) or QEMU (`qemu.sh`)
3. Check logs for crashes

## Code Style

- **File encoding**: US-ASCII
- **Java target**: 1.6
- **Formatting**: Use `jnode-eclipse-formatter-settings.xml`

## Constraints

- Never commit unless explicitly requested
- Never push force to main/master
- Don't commit secrets or credentials

## Key Files

- `all/conf/default-plugin-list.xml` - Default plugins
- `all/conf/full-plugin-list.xml` - Full plugin set
- `jnode.properties` - Build configuration
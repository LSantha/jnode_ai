# Quick Start

Get JNode running in 5 minutes.

## Prerequisites

- **Java 8** (JDK 1.8) — Zulu, Temurin, or Oracle
- **Ant 1.10+** — `apt install ant` / `brew install ant`
- **NASM 2.14+** — `apt install nasm` / `brew install nasm`
- **QEMU** — `apt install qemu-system-x86` / `brew install qemu`
- **Git** — for cloning

## Clone & Build

```bash
git clone https://github.com/LSantha/jnode_ai_oc.git
cd jnode_ai_oc
sh build.sh cd-x86-lite
```

The build downloads the classlib (~30 MB) on first run, then compiles the kernel, native code, and creates a bootable ISO at:

```
all/build/cdroms/jnode-x86-lite.iso
```

## Boot in QEMU

```bash
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso -m 512 -serial stdio -display none
```

You should see the boot banner, then a shell prompt:

```
JNode 0.2.9-dev+<build-id>
Copyright (C) 2003-2026 The JNode Project

jnode>
```

## Try Commands

```bash
jnode> help
jnode> ls /
jnode> ps
jnode> vminfo
jnode> mem
```

Type `exit` to shut down cleanly.

## Next Steps

- [Full build options](building.md) — x86_64, plugins, classlib
- [QEMU details](qemu.md) — Serial logging, networking, snapshots
- [VirtualBox + JDWP](virtualbox.md) — Remote debugging with `jdb`
- [Architecture overview](../architecture/overview.md) — How it fits together

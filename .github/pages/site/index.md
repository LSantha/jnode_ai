# JNode — A Java Operating System

[![Build Status](https://github.com/LSantha/jnode_ai/actions/workflows/ant.yml/badge.svg)](https://github.com/LSantha/jnode_ai/actions/workflows/ant.yml)
[![Java 8](https://img.shields.io/badge/Java-8-blue.svg)](https://openjdk.java.net/projects/jdk/8/)
[![License](https://img.shields.io/badge/License-LGPL%202.1-green.svg)](https://www.gnu.org/licenses/lgpl-2.1.html)

**JNode** is an experimental operating system written in Java. It builds its own JVM, kernel, drivers, filesystems, network stack, and GUI, and boots them on bare metal with only a few hundred lines of assembly at the very bottom. It's a platform for exploring OS design, JVM implementation, and system-level Java.

---

## Developed with AI Agents

This is a fork of the classic JNode project, maintained as a living experiment in agent-driven development. GitHub Actions and OpenCode agents triage issues, implement fixes, review pull requests, update the wiki, and boot every build in QEMU before it lands. Batching is coordinated through an orchestrator workflow, with agentic skills for QEMU boot testing, JDWP debugging, and filesystem diagnosis.

---

## Quick Links

| Topic | Description |
|-------|-------------|
| [:material-rocket-launch: Quick Start](getting-started/quick-start.md) | Build and boot your first JNode ISO |
| [:material-hammer-wrench: Building](getting-started/building.md) | Detailed build instructions for x86 and x86_64 |
| [:material-cpu-64-bit: Run in QEMU](getting-started/qemu.md) | Test JNode in QEMU with serial logging |
| [:material-virtualbox: Run in VirtualBox](getting-started/virtualbox.md) | JDWP debugging support via VirtualBox |

---

## Architecture Overview

JNode consists of several major components:

```mermaid
graph TD
    A[Boot Loader] --> B[Kernel]
    B --> C[VmImpl / JVM]
    C --> D[Plugin System]
    D --> E[Core Services]
    E --> F[Drivers]
    E --> G[Filesystems]
    E --> H[Network Stack]
    E --> I[Shell]
    E --> J[GUI / AWT]
```

See [Architecture Overview](architecture/overview.md) for details.

---

## Subsystems

| Subsystem | Description | Wiki Reference |
|-----------|-------------|----------------|
| [Filesystems](subsystems.md#filesystems) | Ext2/3, FAT, ISO9660, NFS2, exFAT, HFS+, NTFS | [Filesystem-Layer](https://github.com/LSantha/jnode_ai/wiki/Filesystem-Layer) |
| [Network Stack](subsystems.md#network-stack) | IPv4, TCP, UDP, DNS, NetAPI | [Network-Stack](https://github.com/LSantha/jnode_ai/wiki/Network-Stack) |
| [Shell](subsystems.md#shell) | Commands, syntax, aliases, plugins | [Shell-Commands](https://github.com/LSantha/jnode_ai/wiki/Shell-Commands) |
| [GUI/AWT](subsystems.md#gui-awt) | Video drivers, input, AWT peers, Thinlet | [GUI-AWT](https://github.com/LSantha/jnode_ai/wiki/GUI-AWT) |
| [Drivers](subsystems.md#drivers) | PCI, USB, IDE, SCSI, Ethernet, video, input | [Driver-Framework](https://github.com/LSantha/jnode_ai/wiki/Driver-Framework) |

See [Subsystems](subsystems.md) for details.

---

## Development

- [Build & Test](development/index.md) — Ant-based build, JUnit, regression tests
- [Testing](development/testing.md) — JUnit, regression, boot testing

---

## Reference

- [Commands](reference.md#shell-commands) — Shell command reference
- [Boot Parameters](reference.md#boot-parameters) — GRUB/kernel boot options
- [Configuration](reference.md#configuration-files) — `jnode.properties`, plugin lists

See [Reference](reference.md) for details.

---

## External Documentation

!!! info "Full Wiki"
    The [JNode Wiki](https://github.com/LSantha/jnode_ai/wiki) contains 100+ detailed technical pages covering every subsystem, class, and design decision. This site provides a curated entry point; the wiki is the exhaustive reference.

---

## Community

- **Issues**: [GitHub Issues](https://github.com/LSantha/jnode_ai/issues)
- **CI**: [GitHub Actions](https://github.com/LSantha/jnode_ai/actions)

---

*JNode is licensed under the GNU Lesser General Public License v2.1. See [LGPL v2.1](https://www.gnu.org/licenses/lgpl-2.1.html) for details.*

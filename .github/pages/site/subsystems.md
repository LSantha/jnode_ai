# Subsystems

JNode's functionality is organized into independent subsystems, each implemented as a set of plugins.

## Overview

| Subsystem | Package | Wiki |
|-----------|---------|------|
| **Filesystems** | `org.jnode.fs.*` | [Filesystem-Layer](https://github.com/LSantha/jnode_ai/wiki/Filesystem-Layer) |
| **Network Stack** | `org.jnode.net.*` | [Network-Stack](https://github.com/LSantha/jnode_ai/wiki/Network-Stack) |
| **Shell** | `org.jnode.shell.*` | [Shell-Commands](https://github.com/LSantha/jnode_ai/wiki/Shell-Commands) |
| **GUI/AWT** | `org.jnode.awt.*`, `org.jnode.gui.*` | [GUI-AWT](https://github.com/LSantha/jnode_ai/wiki/GUI-AWT) |
| **Drivers** | `org.jnode.driver.*` | [Driver-Framework](https://github.com/LSantha/jnode_ai/wiki/Driver-Framework) |

## Filesystems

Supported formats (as documented in the wiki):

| FS | Read | Write | Notes |
|----|------|-------|-------|
| FAT12/16/32 | ✅ | ✅ | Best-supported FS |
| RAM disk (ramfs) | ✅ | ✅ | In-memory |
| exFAT | ✅ | ✅ | |
| Ext2 | ✅ | ❌ | Read-only; ext3 mounted RO unless journal features absent |
| ISO9660 | ✅ | ❌ | CD-ROM |
| NTFS | ✅ | ❌ | Read-only |
| HFS+ | ✅ | ❌ | Read-only |
| NFS v2 | ✅ | ⚠️ | Network FS, partial |
| FTP / SMB | ✅ | ⚠️ | Network FS mounts |

Architecture: VFS layer → FSEntry cache → FileSystemType plugins → Block drivers

See [Filesystem Layer](https://github.com/LSantha/jnode_ai/wiki/Filesystem-Layer) and [Block Device Layer](https://github.com/LSantha/jnode_ai/wiki/Block-Device-Layer).

## Network Stack

```
Application
    │
    ▼
NetAPI (org.jnode.net.api)
    │
    ▼
Transport: TCP / UDP
    │
    ▼
Internet: IPv4, ICMP, ARP
    │
    ▼
Link: Ethernet, Loopback
    │
    ▼
Driver: RTL8139, NE2000, PRO/100, PCNet
```

Key components:
- **NetAPI** — Java networking API implementation
- **NetworkLayerManager** — Layer registration & routing
- **IPv4Protocol** — Packet processing, fragmentation reassembly
- **TCPProtocol** — State machine (SYN/ACK, retransmission)
- **ResolverImpl** — DNS resolution (cached)

See [Network Stack](https://github.com/LSantha/jnode_ai/wiki/Network-Stack), [TCP Protocol](https://github.com/LSantha/jnode_ai/wiki/TCP-Protocol), [UDP Protocol](https://github.com/LSantha/jnode_ai/wiki/UDP-Protocol).

## Shell

The JNode shell (`org.jnode.shell`) provides:

- **Built-in commands**: `ls`, `cd`, `cat`, `cp`, `rm`, `memory`, `vminfo`, `help`, `alias`, `history`
- **Plugin commands**: Dynamically loaded via plugin system
- **Aliases & functions**: Persistent across sessions
- **Tab completion**: For commands, files, aliases
- **I/O redirection**: `>`, `>>`, `<`, `|`
- **Scripting**: `.jsh` script files

Architecture: `Shell` → `CommandParser` → `Command` plugins → `AliasManager`

See [Shell Commands](https://github.com/LSantha/jnode_ai/wiki/Shell-Commands) and [Shell Syntax](https://github.com/LSantha/jnode_ai/wiki/Shell-Syntax).

## GUI / AWT

JNode implements a subset of **AWT** with custom peers:

| Component | Implementation |
|-----------|----------------|
| `Frame` / `Window` | `JNodeFramePeer` → Thinlet / native |
| `Graphics` / `Graphics2D` | `JNodeGraphics2D` → Video driver |
| `Image` | `JNodeImage` → Direct framebuffer |
| `Font` | `JNodeFont` → FreeType / built-in |
| `EventQueue` | `JNodeEventQueue` → Input drivers |

Video drivers: VESA, VGA, ATI, Cirrus, nVidia, VMware SVGA
Input drivers: PS/2 keyboard/mouse, USB HID

See [GUI/AWT](https://github.com/LSantha/jnode_ai/wiki/GUI-AWT), [Video Driver Architecture](https://github.com/LSantha/jnode_ai/wiki/Video-Driver-Architecture), [Input Drivers](https://github.com/LSantha/jnode_ai/wiki/Input-Drivers).

## Drivers

Driver framework (`org.jnode.driver`):

1. **DeviceManager** — Central registry, hotplug
2. **DeviceFinder** — Matches devices to drivers (PCI, USB, ACPI)
3. **Bus drivers** — PCI, USB, ISA, PCMCIA/CardBus
4. **Device drivers** — Extend `Driver` base class

Major driver categories:

| Category | Drivers |
|----------|---------|
| **Storage** | IDE (ATA/ATAPI), SCSI, RAM disk, Floppy |
| **Network** | RTL8139, Intel PRO/100 (eepro100), AMD PCNet (LANCE), NE2000, 3c90x, Broadcom 570x, VIA Rhine, Prism2 wireless |
| **Video** | VGA, VESA, ATI, Cirrus Logic, nVidia, VMware SVGA |
| **Input** | PS/2 keyboard, PS/2 mouse, USB HID |
| **Serial** | 16550 UART (`0x3f8`) |

No audio driver is implemented yet.

See [Driver Framework](https://github.com/LSantha/jnode_ai/wiki/Driver-Framework), [Bus Drivers](https://github.com/LSantha/jnode_ai/wiki/Bus-Drivers), [PCI Capability Structure](https://github.com/LSantha/jnode_ai/wiki/PCI-Capability-Structure).

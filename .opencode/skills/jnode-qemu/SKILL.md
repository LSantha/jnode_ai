---
name: jnode-qemu
description: Boot JNode headless in QEMU - per-instance serial sockets and boot log, verified GRUB menu entry selection, multi-VM side by side, CI-friendly wait-ready mode
license: MIT
---

## What this is

Lifecycle for running JNode in headless QEMU: launch, GRUB entry
selection, readiness wait, stop. Serial transport (agent console, boot
log, KDB) is provided by the `jnode-serial` skill's instance model - this
skill just wires the ports and manages the process.

## Quick start

```sh
ISO=all/build/cdroms/jnode-x86-lite.iso      # sh build.sh cd-x86-lite

# boot default entry, wait until the serial console answers
.opencode/skills/jnode-qemu/scripts/start_qemu.sh --wait-ready 240 simple "$ISO"

# run commands through the shared client
python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --wait 300 "date"

# stop and forget
.opencode/skills/jnode-qemu/scripts/start_qemu.sh --stop
```

## Usage

```
start_qemu.sh [options] [simple|full] [iso-path] [entry]

simple (default)  UART1 -> boot log file, UART2 -> agent console socket
full              UART1 -> KDB socket + boot log, UART2 -> agent console

-n, --instance ID   instance id (default $JNODE_INSTANCE, else auto:
                    "default" when free, otherwise qemu-1, qemu-2, ...)
--wait-ready SECS   block until the boot log shows the serial console
--stop              kill the QEMU behind an instance and unregister it
--dry-run           print resolved instance/paths, start nothing
```

Extra QEMU args: set `JNODE_QEMU_ARGS`; memory: `JNODE_QEMU_MEM`
(default 1024). KVM is used when available (`accel=kvm:tcg`).

The launcher backgrounds QEMU itself (`</dev/null >launch.log 2>&1 &`)
so it never blocks the caller; launch stderr lands in the instance's
`qemu.log` (`/tmp/qemu_launch.log` for default).

## GRUB menu entries (standard cdrom ISO)

The menu renders on VGA only (GRUB 0.97; menu.lst enables `serial` per
entry for the kernel, not for the menu). Entries, in menu order:

| entry | title                             |
|-------|-----------------------------------|
| 0     | JNode (default)                   |
| 1     | JNode (all plugins)               |
| 2     | JNode (minimal shell)             |
| 3     | JNode (all plugins) (VESA mode)   |
| 4     | JNode tests (all plugins + tests) |
| 5     | JNode via dhcp (all plugins)      |

`start_qemu.sh ... <entry>` selects an entry through
`scripts/grub_pick.py`: it screendumps the VGA screen via the QEMU
monitor, detects the highlight bar, sends HOME/DOWN and **verifies the
bar moved** before ENTER. Blind key bursts are unreliable (keys sent
before GRUB polls int16 linger in the BIOS buffer; rapid batches get
dropped and land on a random entry). CI menus have a single entry and
timeout 0 - entry selection is a no-op there.

Debug a stuck selection: `pnmtopng <monitor>.last.ppm | display -`
(kept next to the monitor socket on failure).

## Several VMs at once

Every VM gets its own instance (sockets, log, registry entry):

```sh
start_qemu.sh --instance vm-a simple "$ISO"
start_qemu.sh --instance vm-b simple "$ISO" 1
python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --instance vm-a "date"
python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --instance vm-b "date"
```

Autostart with no `--instance` grabs `default` when free (legacy paths),
else allocates `qemu-1`, `qemu-2`, ... Never run two VMs on one
instance; `start_qemu.sh --stop --instance X` tears one down (QEMU pid
from the registry, sockets removed, entry unregistered).

## KDB mode

`full` wires UART1 to a KDB socket (plus the boot log). After boot:

```sh
python3 .opencode/skills/jnode-serial/scripts/jnode_kdb_cmd.py --instance <id> p
python3 .opencode/skills/jnode-serial/scripts/jnode_kdb_cmd.py --instance <id> W
```

## CI (GitHub Actions)

```sh
sh .opencode/skills/jnode-qemu/scripts/start_qemu.sh --instance ci --wait-ready 240 simple "$ISO"
python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --instance ci --wait 300 "date" || exit 1
```

Runners need `qemu-system-x86_64`, `socat` (entry selection), KVM
optional. `--wait-ready` fails non-zero on timeout and prints the launch
log tail.

## Troubleshooting

- `agent socket ready after 1s` is just QEMU creating the listening
  socket; the guest is ready when `--wait-ready` succeeds or
  `serial_cmd.py --wait` returns output.
- Empty boot log + VM alive = stuck before the kernel (usually GRUB).
  Screendump via the monitor socket and look.
- `-no-reboot` makes QEMU exit on triple fault - a VM that vanishes
  usually died in the guest; check `qemu.log`.
- `host doesn't support requested feature ... svm` is a harmless CPUID
  warning on AMD-less hosts.

---
name: jnode-vbox
description: Boot JNode in VirtualBox headless - configure UART pipes into the jnode-serial instance model, verified GRUB entry selection via screenshots and scancodes, start/stop/status lifecycle
license: MIT
---

## What this is

Lifecycle for running JNode in VirtualBox. Unlike QEMU (where the
launcher owns the VM), VirtualBox VMs are long-lived config objects: this
skill creates/configures them once (`config`) and then starts/stops them
headless. Both UARTs are wired into the `jnode-serial` instance model so
`serial_cmd.py` / `jnode_kdb_cmd.py` work exactly as they do on QEMU.

UART wiring (what `config` sets):

```
UART1 0x3F8 IRQ4 -> server pipe at <kdb_sock>     (boot log + KDB)
UART2 0x2F8 IRQ3 -> server pipe at <agent_sock>   (agent console)
```

## Quick start

```sh
V=.opencode/skills/jnode-vbox/scripts/jnode_vbox.py
ISO=all/build/cdroms/jnode-x86-lite.iso

# create or rewire a VM (idempotent; never touches disks)
python3 $V config --name JNode --iso "$ISO"          # default instance

# boot headless, pick GRUB entry 1, wait for the agent console
python3 $V start --name JNode --entry 1 --wait-ready 480

python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --wait 300 "date"

python3 $V stop --name JNode
python3 $V status --name JNode
```

For a second concurrent VM use another name AND another instance:
`config --name JNode2 --instance vb2 --iso ...`, then `start --instance
vb2`. One VM owns one instance's pipes (the pipes are owned by whichever
VM was configured last).

## Usage

```
jnode_vbox.py config  --name VM [--iso FILE] [--instance ID] [--memory MB]
jnode_vbox.py start   --name VM [--instance ID] [--entry N] [--wait-ready SECS]
jnode_vbox.py stop    --name VM [--instance ID]
jnode_vbox.py status  --name VM [--instance ID]
```

- `config` creates the VM when missing (`createvm` + IDE controller +
  DVD attach), then only modifies settings: memory, ACPI/IOAPIC, boot
  order, both UARTs. Refuses while the VM runs.
- `start` is headless; `--entry N` (0-5) selects a GRUB 0.97 menu entry via
  `vbox_grub_pick.py` (screenshots + keyboard scancodes, verified like
  the QEMU picker); `0`/omitted boots the ISO default and skips the
  picker, matching `start_qemu.sh`; `--wait-ready SECS` blocks on `serial_cmd.py --wait`.
- `stop` sends ACPI poweroff, waits 15s, then forces `poweroff`
  (JNode guests often ignore ACPI).
- `status` prints VM state, UART modes, and the registry entry.

## VirtualBox vs QEMU differences

- The agent endpoint is a **pipe** (VBox `--uartmode2 server`), not a
  QEMU socket; only ONE client may hold it - always go through the mux
  (`serial_cmd.py` does this automatically).
- **No boot log file**: UART1's pipe carries the kernel boot log; connect
  with `jnode_kdb_cmd.py` (or `python3 -c` a raw socket read) and read to
  collect boot diagnostics.
- Boot is slower: entry 1 ("all plugins") can take 3-6 minutes from
  `startvm` to `Serial console available`. Size `--wait-ready`
  accordingly (QEMU needs ~60s for the same).
- The mux self-heals: while the guest has not opened COM2, connects are
  FINed/reset; the mux reconnects with backoff and flips `link=up` once
  the guest is ready. Check with `serial_cmd.py --status`.
- Screenshots: `VBoxManage controlvm <vm> screenshotpng x.png` (VBox
  6/7); the picker falls back between `screenshotpng`/`screenshot` and
  converts via `pngtopnm`.

## GRUB entries

Same 6-entry menu as the standard cdrom ISO (see the `jnode-qemu` skill
for the table). The picker waits `--delay` seconds (default 6) after
power-on, then polls screenshots until the menu's highlight bar appears,
sends scancodes HOME `47 c7`, DOWN `50 d0`, ENTER `1c 9c`, and verifies
the bar position after every step.

## Restoring a VM's original UART config

`config` is deliberately blunt (it always sets both server pipes). To put
a hand-tuned VM back:

```sh
VBoxManage modifyvm <name> --uartmode1 file /tmp/jnode.kdb   # log-only KDB
VBoxManage modifyvm <name> --uartmode2 server /tmp/jnode.serial2
```

## Troubleshooting

- `start` fails with GRUB menu timeout: the VM booted nothing (no boot
  media / wrong boot order) or is wedged; `screenshotpng` and look.
- ACPI poweroff timeout + forced poweroff is normal for JNode guests.
- Stale settings file after a failed `unregistervm`:
  `VBoxManage unregistervm <name> --delete` (VM powered off), else remove
  `~/VirtualBox VMs/<name>/`.
- `config` on a running VM is refused - stop first.

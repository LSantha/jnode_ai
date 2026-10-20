---
name: jnode-vbox
description: Boot JNode in VirtualBox headless - UART1 file-boot-log by default (no wedged-pipe risk), optional KDB pipe via --kdb, verified GRUB entry selection via screenshots and scancodes, start/stop/status lifecycle
license: MIT
---

## What this is

Lifecycle for running JNode in VirtualBox. Unlike QEMU (where the
launcher owns the VM), VirtualBox VMs are long-lived config objects: this
skill creates/configures them once (`config`) and then starts/stops them
headless. Both UARTs are wired into the `jnode-serial` instance model so
`serial_cmd.py` / `jnode_kdb_cmd.py` work exactly as they do on QEMU.

UART wiring (what `config` sets by default):

```
UART1 0x3F8 IRQ4 -> file at <boot_log>             (kernel/boot serial log)
UART2 0x2F8 IRQ3 -> server pipe at <agent_sock>    (agent console)
```

`config --kdb` switches UART1 to a server pipe at `<kdb_sock>` for the
KDB tools instead. VBox cannot tee a log file and a pipe on one UART
the way QEMU's `logfile=` chardev can, so the two are mutually
exclusive: the default optimizes for the always-on case (log capture,
guest never wedges writing into an unread pipe); KDB is the rare case
and is opt-in.

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
jnode_vbox.py config  --name VM [--iso FILE] [--instance ID] [--memory MB] [--kdb]
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
  A start also refuses when another process holds the instance's UART
  pipe/socket files (e.g. a hand-started VM on the legacy default
  paths) — they are never unlinked out from under a live holder; stale
  files with no holder are still cleaned up.
- `stop` sends ACPI poweroff, waits 15s, then forces `poweroff`
  (JNode guests often ignore ACPI).
- `status` prints VM state, UART modes, and the registry entry.

## VirtualBox vs QEMU differences

- The agent endpoint is a **pipe** (VBox `--uartmode2 server`), not a
  QEMU socket; only ONE client may hold it - always go through the mux
  (`serial_cmd.py` does this automatically).
- Boot log is a **file** (`--uartmode1 file <boot_log>`): VBox itself
  always reads the guest's COM1 output into the file, so the guest can
  never wedge on an unread pipe and the log is complete from the first
  byte. To read it: `tail -f /tmp/jnode/<id>/boot.log` (QEMU-full-style
  socket+logfile tee is impossible on VBox).
- **KDB on VBox is opt-in** (`config --kdb`, or at runtime
  `VBoxManage controlvm <vm> changeuartmode1 server <kdb_sock>`) --
  UART1 becomes a server pipe and log capture stops (read the pipe
  instead: `jnode_kdb_cmd.py --mux` holds it and logs replies).
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

`config` is deliberately blunt (it always sets both UARTs to the
instance-model layout). To put a hand-tuned VM back:

```sh
# back to the default (file boot log + agent pipe) -- equivalent to
# plain `config` without --kdb:
VBoxManage modifyvm <name> --uartmode1 file /tmp/jnode/<id>/boot.log
VBoxManage modifyvm <name> --uartmode2 server /tmp/jnode/<id>/agent.sock
```

## Troubleshooting

- `start` fails with GRUB menu timeout: the VM booted nothing (no boot
  media / wrong boot order) or is wedged; `screenshotpng` and look.
- ACPI poweroff timeout + forced poweroff is normal for JNode guests.
- Stale settings file after a failed `unregistervm`:
  `VBoxManage unregistervm <name> --delete` (VM powered off), else remove
  `~/VirtualBox VMs/<name>/`.
- A VM must be stopped before reconfiguring: `config` and `start` refuse
  a running VM of the same name, and both refuse any instance whose
  registered VM is still live (autodetect would otherwise hand a second
  VM the live one's pipes).
- `stop`/`status` verify the instance is bound to the VM named with
  `--name`; a mismatched `--name` errors instead of unregistering the
  wrong instance. `--name` must match the name the instance was
  registered under.

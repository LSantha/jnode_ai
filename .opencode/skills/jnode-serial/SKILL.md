---
name: jnode-serial
description: Agent console, serial boot log, and KDB over serial for JNode VMs (QEMU and VirtualBox) - one persistent mux client per VM instance, instance-namespaced sockets, works locally and in CI
license: MIT
---

## What this is

The **transport** layer for talking to a running JNode, shared by the
`jnode-qemu` and `jnode-vbox` skills (and usable standalone). One command
client (`serial_cmd.py`) drives JNode's implicit serial agent console
(`SerialConsolePlugin`) over a per-VM unix socket/pipe; a persistent mux
daemon holds the single client connection; optional KDB tooling drives the
kernel debugger on UART1. Everything is keyed by an **instance id** so many
VMs (QEMU/VirtualBox, local/GitHub-Actions) run side by side without path
collisions.

Paths and instance logic live in `scripts/jnode_paths.py` (importable, and
a CLI).

## Instance model

Instance id resolution: `--instance` flag > `$JNODE_INSTANCE` env >
auto (`default` when free, else `qemu-1`, `qemu-2`, ...).

The legacy **`default` instance keeps the historical fixed paths** so
existing tooling (`tests/jdwp`, `local/*.sh`, older skills) keeps working:

| resource        | default instance     | any other id            |
|-----------------|----------------------|-------------------------|
| agent socket    | `/tmp/jnode.serial2` | `/tmp/jnode/<id>/agent.sock` |
| boot log        | `/tmp/qemu_serial.log` | `/tmp/jnode/<id>/boot.log` |
| KDB socket      | `/tmp/jnode.kdb`     | `/tmp/jnode/<id>/kdb.sock` |
| QEMU monitor    | `/tmp/qemu_monitor.sock` | `/tmp/jnode/<id>/monitor.sock` |
| mux files       | `/tmp/jnode_serial_*` (fifo `cmd.fifo`, `req/`, `resp/`, `mux.*`) | `/tmp/jnode/<id>/mux/...` |
| KDB mux files   | `/tmp/kdb_*`         | `/tmp/jnode/<id>/kdb/...` |
| registry entry  | `/tmp/jnode/instances/default.json` | same dir, `<id>.json` |

`JNODE_STATE_DIR` relocates the state root (replaces `/tmp/jnode`).

Mux pid files are claimed under flock; each claim leaves a permanent
`<pidfile>.lock` sidecar next to the pid file — flock needs a stable
inode, so it is a normal artifact, not litter (do not clean it up).

Inspect/manage with the CLI:

```sh
python3 scripts/jnode_paths.py --list          # registered instances
python3 scripts/jnode_paths.py --env --instance qemu-2   # shell exports
python3 scripts/jnode_paths.py --alloc --prefix qemu     # free id
python3 scripts/jnode_paths.py --resolve   # the single live id
                                           # (exit 1 none, exit 2 several)
# test-only: JNODE_TEST_FAKE_QEMU_PID=<pid> makes entry_live() accept
# that pid as "a live qemu-system" (read path only -- --stop and
# safe_kill_pid always prove identity via /proc)
python3 scripts/jnode_paths.py --probe --instance qemu-2 # socket present?
python3 scripts/jnode_paths.py --is-live --instance qemu-2
python3 scripts/jnode_paths.py --sock-busy --instance qemu-2
                    # exit 0 = a process holds the agent socket
                    # (unix listener or FIFO open)
python3 scripts/jnode_paths.py --prune         # forget dead entries
```

Two VMs never share an instance: reconfiguring a VM's UARTs to an
instance's paths is what binds them (QEMU launchers do it implicitly,
`jnode_vbox.py config --instance ...` for VirtualBox).

## Running commands

```sh
python3 scripts/serial_cmd.py --wait 300 "date"        # wait for shell
python3 scripts/serial_cmd.py "ls /jnode" "date"       # batch, one round-trip
python3 scripts/serial_cmd.py --instance qemu-2 "date"
python3 scripts/serial_cmd.py --oneshot "date"         # no mux, direct connect
python3 scripts/serial_cmd.py --status                 # mux pid/link/cmd count
python3 scripts/serial_cmd.py --stop | --restart       # mux lifecycle
python3 scripts/serial_cmd.py --interrupt              # Ctrl-C the guest shell
```

The mux is auto-started on first use. On VirtualBox only ONE client may
hold the UART pipe; the mux reconnects with backoff until the guest opens
COM2 (entry "all plugins" can take 3-6 minutes on VBox), then the status
flips to `link=up`. `--wait N` retries for N seconds; fail-fast otherwise.

Batch output is captured per command; the batch status line
(`[batch OK 2]`) is the exit signal (0 all OK).

## Boot log and readiness

Launchers wire UART1 to a per-instance boot log (QEMU: `-serial file:`
simple mode or a `logfile=` chardev; VirtualBox: `--uartmode1 file`, the
default -- KDB needs `--kdb`/`changeuartmode1` there). JNode prints
`Serial console available on serial1 at 115200 baud` when the agent
console is up ("serial1" is the JNode device name - it may be COM1 or COM2
depending on how the hypervisor numbered the ports). Readiness =
that marker in the boot log AND a successful `serial_cmd.py --wait`.

## KDB over serial

UART1 doubles as the kernel debugger when the ISO boots with `kdb`
(QEMU full mode; on VirtualBox pass `--kdb` to `jnode_vbox.py config`,
or flip a running file-mode VM with
`VBoxManage controlvm <vm> changeuartmode1 server <kdb_sock>`):

```sh
python3 scripts/jnode_kdb_cmd.py --instance qemu-kdb p   # ping
python3 scripts/jnode_kdb_cmd.py --instance qemu-kdb t   # thread info
python3 scripts/jnode_kdb_cmd.py --instance qemu-kdb T W # traces
python3 scripts/jnode_kdb_cmd.py --mux                   # start persistent holder
python3 scripts/jnode_kdb_cmd.py --stop                  # stop the holder
```

`--mux` is idempotent: it refuses to start a second holder for an
instance (single pid file), and `--stop` tears the holder down.

Commands: `h/?` help, `p` ping, `t`/`T` current thread, `q`/`r` ready
queue, `w`/`W` waiting threads, `v` verify states, `l` load queue.
Responses are compact XML (`<ping/>`, `<currentthread .../>`).
`kdb_mux.py` holds the socket and forwards one-char commands from
`<kdb_dir>/cmd.fifo`, logging replies to `<kdb_dir>/resp.log`.

When UART1 is a pipe/socket (QEMU full, VBox `--kdb`), it also carries
the full kernel boot log; connecting early and reading is a valid way
to collect boot diagnostics. When UART1 is a file (QEMU simple, VBox
default), read the boot log file instead.

## CI (GitHub Actions) pattern

```sh
# start_qemu.sh is bash-only (arrays); do not run it under sh/dash
bash .opencode/skills/jnode-qemu/scripts/start_qemu.sh --instance ci --wait-ready 240 simple "$ISO"
python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --instance ci --wait 300 "date"
python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py --instance ci --stop
```

Log the section banners yourself (`echo "::group::boot"` etc.); the
scripts are quiet and exit non-zero on failure. GH runners have QEMU +
socat, no VirtualBox.

## Troubleshooting

- `link=down` in `--status`: mux is (re)connecting - normal before the
  guest opens its COM port; it self-heals, or use `--stop` to recycle.
- VBox: a second concurrent client on the agent pipe gets FIN/RST -
  always go through the mux (`--oneshot` only when no mux holds it).
- Guest wedged (no prompt): `serial_cmd.py --interrupt`, or KDB `W` to
  see which threads wait on the serial console.
- `pkill -f` matches the caller's own `bash -c` line - kill by PID or use
  a `[f]` bracket regex.
- Nothing on the socket: check the instance (`--list`), the boot log, and
  that the VM's UART2 really points at this instance's paths
  (`jnode_vbox.py status` or the QEMU command line).

## Tests

`tests/fake_guest.py` emulates the agent protocol (CRLF output,
`\n[JNODE_AGENT_READY]\n` prompt). `tests/test_serial_e2e.py` boots fake
guests on instance sockets and covers mux/oneshot/wait/status/two
parallel instances; `tests/test_jnode_paths.py` covers the path/registry
model. Run: `python3 -m pytest .opencode/skills/jnode-serial/tests/ -q`.

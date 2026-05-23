---
name: jnode-interact
description: Run JNode in QEMU with full serial logging and interactive shell for debugging, testing, and ad-hoc feature verification
license: MIT
---

## What I do

This skill provides headless QEMU setup for running JNode (designed for CI/GitHub Actions):
- Full serial boot logging to file (UART1 → `/tmp/qemu_serial.log`)
- Automatic serial console in agent mode on second serial port (UART2 → Unix socket)
- Interactive shell command execution via `jnode_agent_cmd.py`
- Debugging, testing, and ad-hoc configuration verification

## When to use me

Use this skill when you need to:
- Test JNode shell commands or plugins
- Debug runtime behavior
- Verify plugin lifecycle (start, stop, unload)
- Test filesystem, network, or system commands
- Perform ad-hoc configuration changes and verify them
- Inspect running system state (threads, memory, plugins, devices)

## QEMU Setup

**Always use the startup script.** Running the raw `qemu-system-x86_64` command inline may hang because the agent's shell tool does not reliably handle backgrounding with `& true`. The script in `scripts/start_qemu.sh` handles this correctly.

```bash
bash .opencode/skills/jnode-interact/scripts/start_qemu.sh [simple|full] [iso-path]
```

Two approaches, choose based on need:

| Approach | Log | Console | KDB | Use case |
|---|---|---|---|---|
| **Simple (default)** | ✅ file | ✅ unix | ❌ | 90% of cases — testing, debugging, CI |
| **Full (advanced)** | ✅ logfile | ✅ unix | ✅ interactive | Complex debug — thread queues, stack traces, KDB probing |

**CRITICAL:** The `& true` at the end of the QEMU command is required. Without it, the bash tool may timeout and kill QEMU. Always use this pattern. The `start_qemu.sh` script already includes it.

### Approach A — Simple (default)

Boot log + serial console. No interactive KDB. Use this for most tasks.

```bash
qemu-system-x86_64 \
  -machine accel=kvm:tcg \
  -m 1024 \
  -name "JNode x86" \
  -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -serial file:/tmp/qemu_serial.log \
  -serial unix:/tmp/jnode.serial2,server,nowait \
  -no-reboot \
  -display none \
  </dev/null >/dev/null 2>&1 & true
```

**Parameters (Approach A):**
| Parameter | Purpose |
|---|---|---|
| `-machine accel=kvm:tcg` | KVM acceleration with TCG fallback |
| `-m 1024` | 1GB RAM (768MB minimum, 1024MB recommended) |
| `-serial file:...` | UART1 → KDB boot log file |
| `-serial unix:...` | UART2 → Unix socket for interactive console |
| `-no-reboot` | Stop on crash instead of rebooting |
| `-display none` | Disable graphical window (required for headless/CI environments) |

### Approach B — Full (advanced)

Adds interactive KDB debugger alongside boot log and serial console. Use when you need to inspect threads, queues, or probe internal VM state at runtime.

```bash
rm -f /tmp/qemu_serial.log /tmp/jnode.serial2 /tmp/jnode.kdb
qemu-system-x86_64 \
  -machine accel=kvm:tcg \
  -m 1024 \
  -name "JNode x86" \
  -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -chardev socket,id=com1,path=/tmp/jnode.kdb,server=on,wait=off,logfile=/tmp/qemu_serial.log \
  -serial chardev:com1 \
  -serial unix:/tmp/jnode.serial2,server,nowait \
  -no-reboot \
  -display none \
  </dev/null >/dev/null 2>&1 & true
```

**What changed vs simple:**
| Change | Reason |
|---|---|
| `-chardev socket,...,logfile=...` | Single chardev does double duty: Unix socket for KDB interaction + file for boot log |
| `-serial chardev:com1` | Maps UART1 to the chardev instead of a plain file |
| `rm -f` prior sockets | Chardev socket path must not exist before QEMU starts (unlike `-serial unix:`) |

**KDB commands (send to the socket):**
```
?   Help                    w   Print waiting threads
t   Print current thread    W   Stack traces of waiting threads
q   Print thread queues     p   Ping
r   Stack traces of ready   l   Show Load/Compile queue
v   Verify thread
```

### Testing KDB interactively

After boot, connect to the KDB socket, send `?`, and read the response:

```bash
python3 -c "
import socket, time, select
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(5)
s.connect('/tmp/jnode.kdb')
s.send(b'?\n')
time.sleep(0.5)
s.setblocking(False)
buf = b''
while True:
    r, _, _ = select.select([s], [], [], 1.0)
    if s in r: d = s.recv(4096)
    else: break
    if not d: break
    buf += d
print(buf.decode(errors='replace'))
s.close()
"
```

Expected output (~218 bytes):
```
Commands:
l   Show Load/Compile queue
p   Ping
q   Print thread queues
r   Print stacktraces of ready-queue
t   Print current thread
v   Verify thread
w   Print waiting threads
W   Print stacktraces of waiting threads
```

The same pattern works for any KDB command — open a connection, send `cmd\n`, read response, close. Each connection is independent; no persistent reader is needed.

### GitHub Actions notes

- Runners have KVM available at `/dev/kvm`
- Use `accel=kvm:tcg` for KVM with TCG fallback
- No display needed — QEMU runs headless by default when no `-display` is specified
- The serial console (UART2) is the primary interaction method

### Add `lkd` boot flag for full serial logging

Edit `all/conf/x86/menu-cdrom.lst` to include `lkd` alongside `kdb`:
```
kernel /jnode32.gz mp=no kdb lkd
```

The `lkd` flag adds an `UnsafeDebugAppender` to Log4j, routing ALL Log4j output to the KDB serial port (UART1). Without it, logging switches to a VGA virtual console after `Log4jConfigurePlugin` starts, and the serial log stops growing.

### Build before testing

```bash
sh build.sh cd-x86-lite
```

## Detecting Successful Boot

### Check the KDB serial log

```bash
wc -l /tmp/qemu_serial.log
```

A successful boot with `lkd` produces **~190 lines**. The log stops growing once boot completes because the system is idle — this is normal, not a freeze.

Key markers:
- `Starting JNode` — Java entry point reached
- `Initialize BootLog` — Boot logging initialized
- `Starting system plugins` — Plugin lifecycle started
- `Mounted RAMFS on jnode` — Filesystem mounted
- `Mounted JIFS on jifs` — Persistent filesystem mounted
- `Mounted ISO9660 on /devices/sg0` — CD-ROM detected

### Check for serial console readiness

The safest way to detect when the serial console is ready:

```bash
grep "Serial console available" /tmp/qemu_serial.log
```

When `SerialConsolePlugin` starts, it logs:
```
INFO  [SerialConsolePlugin]: Serial console available on serial1 at 115200 baud
```

This confirms the shell is ready to accept commands.

### Verify the shell is responsive

```bash
ln -sf /tmp/jnode.serial2 /tmp/jnode_com2
python3 .opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py "date"
```

Output should include `[JNODE_AGENT_READY]` prompt.

## Serial Console (Automatic)

### How it works

`SerialConsolePlugin` (`org.jnode.shell.command.driver.console.SerialConsolePlugin`) is registered in the plugin descriptor with a `class` attribute. When the plugin system starts it:

1. `startPlugin()` is called automatically during plugin startup
2. Opens `serial1` (COM2, I/O 0x2f8) at 115200 baud (divisor 1)
3. Creates a `SerialAgentConsole` with raw text mode (no VT100, no local echo)
4. Starts a `CommandShell` thread with custom prompt: `[JNODE_AGENT_READY]`
5. Thread is daemon — dies with the system

### No manual activation needed

Unlike the `serialconsole` shell command (which requires typing `serialconsole -p serial1 -b 115200 -a`), the plugin starts automatically at boot. The shell is ready as soon as the socket appears.

### Plugin lifecycle

- **Unload**: `plugin --unload org.jnode.shell.command.driver.console` — stops the console thread, closes the connection, the socket stops responding
- **Reload**: Currently broken in JNode — `reloadPlugin()` loads the descriptor but does not call `startPlugin()`. The console will NOT restart. (Known limitation of the plugin framework)

## Interacting with the Console

### Using the agent command script

```bash
# Create symlink (script expects /tmp/jnode_com2)
ln -sf /tmp/jnode.serial2 /tmp/jnode_com2

# Send commands
python3 .opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py \
  "date" "echo hello" "pwd" "ls /" "uptime"
```

### How the script works

1. Connects to the Unix socket (`/tmp/jnode_com2` → `/tmp/jnode.serial2`)
2. Clears any pending data from the buffer
3. Sends each command followed by `\r\n`
4. Reads response until timeout (1 second of silence)
5. Prints output, strips trailing whitespace

### Important: Single client only

The serial pipe supports **only ONE client at a time**. Do not connect multiple scripts or terminals simultaneously — you will get "Broken pipe" errors. If the script fails with "Broken pipe", make sure no other process is connected to the socket.

## Useful Shell Commands

### System Information
| Command | Purpose |
|---------|---------|
| `date` | Current date/time |
| `uptime` | System uptime |
| `uname -a` | OS info |
| `memory` | Memory usage (total, used, free) |
| `vminfo` | VM details, CPU features, compiler stats |
| `thread` | List all threads with state |
| `env` | All system properties |
| `cpuid` | CPU feature flags |

### Filesystem
| Command | Purpose |
|---------|---------|
| `ls /` | List root directory |
| `dir /` | Same as `ls` |
| `cat <file>` | Print file contents |
| `mkdir <dir>` | Create directory |
| `echo text > file` | Write to file |
| `find / -name "*.jar"` | Search for files |
| `df` | Disk usage |
| `mount` | Show mounted filesystems |

### Plugins
| Command | Purpose |
|---------|---------|
| `plugin` | List all plugins with state |
| `plugin <id>` | Show specific plugin status |
| `plugin --unload <id>` | Unload a plugin |
| `plugin --load <id>` | Load a plugin |

### Network
| Command | Purpose |
|---------|---------|
| `ifconfig` | Network interfaces |
| `netstat` | Network statistics |
| `ping <host>` | Ping a host |
| `route` | Routing table |

### Shell Help
| Command | Purpose |
|---------|---------|
| `help` | Show help about `help` |
| `help <command>` | Show help for a specific command |
| `alias` | List all command aliases |
| `syntax` | List commands with syntax definitions |
| `man <command>` | Same as `help <command>` |

### System Control
| Command | Purpose |
|---------|---------|
| `gc` | Run garbage collector |
| `halt` | Shut down JNode |
| `reboot` | Reboot JNode |
| `sleep <seconds>` | Pause execution |

## Getting Help

### About a specific command
```
help ls
help plugin
help serialconsole
```

### Command syntax
```
syntax --dump ls
syntax --dump plugin
```

### List all available commands
```
alias
```

## Stopping QEMU

```bash
# Find the PID
pgrep -f "qemu-system-x86_64.*JNode"

# Kill it
kill <PID>
# or force kill
pkill -9 qemu
```

## Troubleshooting

### Socket not found
```bash
ls -la /tmp/jnode.serial2
```
If missing: QEMU may not have started. Verify QEMU is running: `pgrep -fa qemu`.

### Serial console not ready
Wait for the `SerialConsolePlugin` log message:
```bash
grep "Serial console available" /tmp/qemu_serial.log
```
If not found after 70 seconds, the plugin may have failed to start. Check other error messages in the log.

### Serial log stuck at ~190 lines
This is normal — the boot log is complete and the system is idle. The log will grow when commands generate Log4j output.

### Broken pipe errors
Only one client can connect to the serial pipe at a time. Make sure no other terminal or script is connected. Kill any existing connections before running the script.

### Commands return no output
The shell might not be fully initialized yet. Wait a few more seconds after the socket appears. The `[JNODE_AGENT_READY]` prompt confirms readiness.

### KDB socket "Resource temporarily unavailable"
This means another client is already connected to the KDB socket. The chardev (`server,nowait`) accepts only **one client at a time**. Close the existing connection first. Each KDB command can be a fresh connect-send-disconnect cycle — no persistent reader is needed.

### KDB returns empty response after boot
Normal behavior. KDB is a boot-time debugger; some commands may return empty after the system is fully running. The `?` help command should always work. If `?` also returns empty, verify QEMU started with Approach B (check for `-chardev` in the process command line).

### KDB blocks boot (system freezes before startup)
If `wait=on` was used instead of `wait=off`, QEMU will wait for a client to connect to the KDB socket before booting. Verify your command uses `wait=off`. No persistent reader is needed — KDB does not block with `wait=off`.

### Chardev socket path must not exist
Unlike `-serial unix:server,nowait` (which creates/reuses the socket file), the `-chardev socket,path=...` option will **fail** if the socket file already exists. Always `rm -f /tmp/jnode.kdb` before starting QEMU with Approach B.

### Serial console stops working after switching from Approach A to B
If the serial console produces no output with Approach B despite `SerialConsolePlugin` starting successfully, the most likely cause is a stale socket file from the previous QEMU instance. Clean up: `rm -f /tmp/jnode.serial2 /tmp/jnode_com2`.

### Serial console not auto-starting
Verify `SerialConsolePlugin` is in the plugin list:
```
plugin org.jnode.shell.command.driver.console
```
Should show: `org.jnode.shell.command.driver.console; state active; version 0.2.9-dev`

If inactive, the plugin descriptor may be missing the `class` attribute or the plugin failed to start.

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

### Start QEMU headless

```bash
qemu-system-x86_64 \
  -machine accel=kvm:tcg \
  -m 1024 \
  -name "JNode x86" \
  -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -serial file:/tmp/qemu_serial.log \
  -serial unix:/tmp/jnode.serial2,server,nowait \
  -no-reboot \
  </dev/null >/dev/null 2>&1 & true
```

**CRITICAL:** The `& true` at the end is required. This ensures the command returns immediately without hanging. Without `true`, the bash tool may timeout and kill QEMU.

**Parameters explained:**
| Parameter | Purpose |
|---|---|---|
| `-machine accel=kvm:tcg` | KVM acceleration with TCG fallback |
| `-m 1024` | 1GB RAM (768MB minimum, 1024MB recommended) |
| `-serial file:...` | UART1 → KDB boot log file |
| `-serial unix:...` | UART2 → Unix socket for interactive console |
| `-no-reboot` | Stop on crash instead of rebooting |

**GitHub Actions notes:**
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

### Serial console not auto-starting
Verify `SerialConsolePlugin` is in the plugin list:
```
plugin org.jnode.shell.command.driver.console
```
Should show: `org.jnode.shell.command.driver.console; state active; version 0.2.9-dev`

If inactive, the plugin descriptor may be missing the `class` attribute or the plugin failed to start.

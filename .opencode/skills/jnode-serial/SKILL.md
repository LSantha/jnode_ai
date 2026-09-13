---
name: jnode-serial
description: Interact with JNode via VirtualBox serial console (automatic agent mode)
license: MIT
---

## What I do

**VirtualBox-only skill.** This provides interaction with JNode's **implicit serial console** (starts automatically via `SerialConsolePlugin`). It:

1. Holds ONE persistent client on the Unix socket at `/tmp/jnode.serial2` (VirtualBox pipe server)
2. Multiplexes agent commands through that single connection
3. Self-heals the pipe without power cycles

## When to use me

Use this skill when you need to:

- Run commands on a running JNode instance in **VirtualBox** via serial console
- Test JNode shell commands interactively
- Debug JNode without VGA console access

## How the implicit console works

`SerialConsolePlugin` (`org.jnode.shell.command.driver.console.SerialConsolePlugin`) starts **automatically** during boot:

1. `startPlugin()` is called by the plugin system at startup
2. Opens `serial1` (COM2/UART2, I/O 0x2f8) at 115200 baud
3. Creates a `SerialAgentConsole` with raw text mode (no VT100, no local echo)
4. Starts a `CommandShell` thread with prompt `[JNODE_AGENT_READY]`
5. Thread is daemon — dies with the system

**No manual activation needed.** Unlike the `serialconsole` shell command (which requires typing `serialconsole -p serial1 -b 115200 -a`), the plugin starts automatically as soon as boot completes. The shell is ready when the socket appears and responds to commands.

## Prerequisites

1. JNode VM must be running in VirtualBox with serial port configured:

   ```
   UART 2: I/O address: 0x02f8, IRQ: 3, attached to pipe (server) '/tmp/jnode.serial2', 16550A
   ```

2. Plugin must be included in the build (it is, by default, in `default.jgz` and `full.jgz`)

3. Confirm the serial console is ready:

   ```bash
   ls -la /tmp/jnode.serial2
   # Should show: srwxrwxr-x ... /tmp/jnode.serial2
   python3 .opencode/skills/jnode-serial/scripts/serial_cmd.py "date"
   # Should return the date followed by completion
   ```

## How to use

```bash
skill({ name: "jnode-serial" })
```

**Always use `serial_cmd.py`.** It talks to the persistent `serial_mux.py` proxy (started automatically), which holds the single pipe client. The proxy waits for the `[JNODE_AGENT_READY]` prompt with no silence timeout, so long compiles and runs just work. Output streams as it arrives.

```bash
S=.opencode/skills/jnode-serial/scripts
python3 $S/serial_cmd.py "date" "echo hello" "pwd"
python3 $S/serial_cmd.py "javac /jnode/tmp/ox/Big.java"   # multi-minute OK
```

Do NOT open your own connections to `/tmp/jnode.serial2` while the mux runs (single-client pipe — a second client gets an immediate EOF). If you must use a raw one-shot connection, stop the mux first: `python3 $S/serial_cmd.py --stop`.

## Why the mux exists (root cause, validated 2026-09)

One connection per command (`jnode_agent_cmd.py` legacy pattern) eventually wedges VirtualBox's pipe server: new attaches get an immediate FIN/EOF while the guest stays healthy (VGA shell alive, `serial-console` thread ASLEEP on input, `/tmp/jnode_com1.log` quiet). `VBox.log` shows `NamedPipe0: only single connection supported` — VBox believes a client is still connected and refuses all new ones forever. Closing mid-output (the old 10s silence timeout tearing down a `javac`) makes it more likely, but any connect/disconnect cycling triggers it after ~4-8 commands. Only a power cycle used to clear it.

Two changes fix this with no guest changes:

1. **Never disconnect** — `serial_mux.py` holds one pipe client for the whole session (same pattern as the stable `kdb_mux.py` on UART1), so the stuck state is never entered. 30+ consecutive commands including multi-minute `javac` run with zero stalls.
2. **Runtime replug recovery** — if the link ever drops (mux killed, host reboot), the mux reconnects itself, and if VBox FINs new attaches (the stuck signature) it runs `vboxmanage controlvm JNode changeuartmode2 disconnected` + `server /tmp/jnode.serial2`, which resets the pipe server with the VM still running. No power cycle needed. You can also run this by hand:

   ```bash
   vboxmanage controlvm "JNode" changeuartmode2 disconnected
   sleep 1
   vboxmanage controlvm "JNode" changeuartmode2 server /tmp/jnode.serial2
   ```

## Mux management

```bash
S=.opencode/skills/jnode-serial/scripts
python3 $S/serial_cmd.py --status    # mux pid + link state + command count
python3 $S/serial_cmd.py --stop      # stop the mux (frees the pipe)
python3 $S/serial_cmd.py --restart   # stop + start (also clears a stuck pipe)
python3 $S/serial_cmd.py --timeout 1800 "javac ..."  # per-batch deadline (default 1200s)
python3 $S/serial_cmd.py --interrupt # Ctrl-C the running command (ETX -> guest job control)
python3 $S/serial_cmd.py --suspend   # Ctrl-Z: unblock shell, command keeps running in background
tail -n 50 /tmp/jnode_serial_mux.log  # proxy log (connects, replugs, errors)
```

Ctrl-C/Ctrl-Z are delivered through the held connection and translated by
the guest into job-control key events (same as VGA). The mux also replugs
the pipe server itself if a batch is in flight but zero bytes arrive for
180s (silent VBox UART freeze) — check the mux log for `replugging pipe`.

## Usage examples

```bash
S=.opencode/skills/jnode-serial/scripts
# Fast commands return as soon as the prompt comes back
python3 $S/serial_cmd.py "date" "echo hello" "pwd"

# Long-running commands stream output; no silence timeout
python3 $S/serial_cmd.py "ping 192.168.1.1"

# Multiple commands in sequence (one batch, one after another)
python3 $S/serial_cmd.py "ifconfig" "route" "resolver"

# Compile and run on the box (full-plugins boot entry has javac)
python3 $S/serial_cmd.py "javac /jnode/tmp/ox/Hi.java" "java -cp /jnode/tmp/ox Hi"
```

## Key files

- `scripts/serial_mux.py` — persistent single-client proxy daemon (auto-started by the client; command queue, prompt framing, reconnect + VBox replug recovery)
- `scripts/serial_cmd.py` — agent CLI client (same UX as the legacy script, plus `--write`, `--timeout`, `--status`, `--stop`, `--restart`)
- `scripts/jnode_agent_cmd.py` — LEGACY one-shot script (one connection per call). Kept for compatibility (other skills reference it). Prefer `serial_cmd.py`: every legacy call cycles the pipe and risks the VBox stall above.

## Multi-line file writes

There is **no heredoc and no `>>` append** on JNode — `RedirectingInterpreter` only handles `<`, `>`, `|`. But a full multi-line file can still be written in **a single serial command**, because the shell tokenizer converts backslash escapes inside quoted strings into real characters before `echo` runs:

```bash
# One command -> real multi-line file
S=.opencode/skills/jnode-serial/scripts
python3 $S/serial_cmd.py \
  "echo 'public class Hi {
  public static void main(String[] a){
    System.out.println(\"hi\");
  }
}' > /tmp/Hi.java"
```

The `\n`, `\t`, `\b`, `\r` escape sequences each become the real byte. Verified with `wc -c` (byte counts match exactly) and `javac` + `java`.

### Escaping rules for content

Wrap the whole content in **single quotes**; single quotes protect `>`, `<`, `|`, `&`, `"` from the redirection parser. Escape only these characters *inside* the content:

| Source char | Send as | Why |
| ----------- | ------- | --- |
| `\`         | `\\`    | tokenizer consumes a backslash + the following char |
| `'`         | `\'`    | prevents the quote from toggling full-escape mode |
| newline     | `\n`    | becomes a real newline byte |
| tab         | `\t`    | becomes a real tab byte |
| CR          | `\r`    | becomes a real carriage-return byte |

`echo` always appends a trailing newline (`EchoCommand` `println()`), so a file written this way is `content + '\n'` (hence `wc -c` = content bytes + 1).

### Known limits (validated 2026-08)

- Single `echo` writes are **byte-exact up to ~64KB** per command (verified: 2/8/16/64KB all wrote exactly `content + 1` newline). The **~8KB cap seen in earlier tests was a test-harness artifact** (real newlines in the Python payload), not a JNode limit.
- **~128KB+/`argv` limit (validated):** a single echo of ~138KB failed at the *host* with `OSError: [Errno 7] Argument list too long` (Linux `subprocess` argv cap) before reaching JNode. But `--write` (chunked) writes **195KB files byte-exact** (md5 matches). Keep one `echo` ≤ 8KB in practice and let `--write` chunk the rest.
- **md5sum trustworthy for large files:** an earlier suspicion that JNode's `md5sum` misreports ~130KB files was **not reproduced** — a 134KB and a 195KB file each matched the host md5 exactly. The old discrepancy was the harness's char-boundary chunking, not `md5sum`.
- **Persistence:** files survive VM power-cycle/reboot (195KB file md5-matched after restart). One pre-crash file (`Hello.class`) came back **zero-filled** after an unclean reboot — re-verify critical files with `wc -c`/`md5sum` after a restart.
- **Byte-exactness requires line-boundary chunking**: `echo` appends a trailing newline per command, so splitting content mid-line inserts a stray `\n` (a 25-chunk file gained 25 bytes / 24 bogus lines). Chunk only between whole lines.
- **Do not send CR bytes raw**; send `\r` instead.
- A single-quoted arg containing `\'`, `\\` plus `>`, `|`, `&` etc. has thrown `Uncaught exception ... shouldn't happen` on JNode and killed the serial session — keep tricky mixed content short and prefer the agent helper (`--write`) below.

### The agent helper does this for you

`serial_cmd.py` has a `--write` mode that escapes stdin content and writes it via **line-boundary-safe chunking** (whole-line chunks, then `cat part0 ... > target`), so multi-part files stay byte-exact:

```bash
printf 'line1\nline2\n' | python3 $S/serial_cmd.py --write /path/to/file
```

It reports the expected size and runs `wc -c` to verify. Use this for large or quote-heavy files instead of hand-writing `echo` lines. `--write` returns `content-size` when the source ends with a newline, otherwise `content-size + 1` (echo's trailing `\n` is the only allowed discrepancy).

> **Newline rule (validated 2026-08):** `echo` ALWAYS appends a `\n`, even when the content already ends with one (`echo 'a\nb\n'` → 5 bytes). The helper strips the trailing newline from each chunk so `--write` is byte-exact in both cases. A raw one-shot `echo '...' > file` is byte-exact only when the content does NOT end with a newline.

## Important notes

1. **Implicit console** - `SerialConsolePlugin` starts automatically; do NOT run `serialconsole` manually (it will conflict)
2. **Single client** - Only ONE connection at a time to the serial pipe; that client is the mux. All commands go through `serial_cmd.py`.
3. **State persists in the shell** - The JNode shell is one persistent process; `cd` and `classpath --add` survive across calls (verified). Commands don't: there is no `&&`, send multiple commands as separate arguments of one batch.
4. **JNode shell has no `&&`** - use separate commands
5. **No silence timeout** - the mux waits for the prompt however long the command takes (up to `--timeout`, default 1200s). Silent multi-minute `javac` runs are fine.

## Boot entry selection

GRUB menu is VGA-only (not serial). To select a non-default entry (e.g. "all plugins", which has on-VM `javac`), use scancode injection:

```bash
# Start VM
vboxmanage startvm "JNode" --type headless

# Wait for GRUB (4s for BIOS + GRUB load)
sleep 4
# Press SPACE to stop timeout
vboxmanage controlvm "JNode" keyboardputscancode 39 B9

sleep 1
# Press DOWN once (entry 1 = all plugins)
vboxmanage controlvm "JNode" keyboardputscancode 50 D0

sleep 1
# Press ENTER to boot
vboxmanage controlvm "JNode" keyboardputscancode 1C 9C
```

Scancodes: `0x39` (SPACE), `0x50` (DOWN), `0x1C` (ENTER) — each followed by the break code (`make + 0x80`).

## Troubleshooting

### Pipe not found

```bash
ls -la /tmp/jnode.serial2
```

If missing:

1. Check JNode VM is running: `vboxmanage list runningvms`
2. Check serial port config: `vboxmanage showvminfo "JNode" | grep -i uart`
3. If the socket was deleted while VM is running, VirtualBox does NOT recreate it — reattach at runtime without rebooting:

   ```bash
   vboxmanage controlvm "JNode" changeuartmode2 server /tmp/jnode.serial2
   ```

### "Broken pipe" / immediate-EOF errors

- Pipe only supports ONE client at a time, and that client is the mux.
- Don't connect anything else (no legacy script, no raw sockets) while the mux runs — check with `python3 $S/serial_cmd.py --status`.
- If the mux itself reports the link stuck (immediate FIN on attach), it replugs the UART automatically; manual recovery is the `changeuartmode2 disconnected` + `server ...` pair above, or `serial_cmd.py --restart`.

### Commands fail

- Wait for full boot (the `[JNODE_AGENT_READY]` prompt confirms readiness; the mux handshake waits for it automatically)
- Try simple commands first: `date`, `echo hello`

### Serial console stops responding

- May have been unloaded via `plugin --unload org.jnode.shell.command.driver.console` — reloading is broken in JNode, restart the VM

#!/usr/bin/env python3
"""Agent CLI for the JNode serial console.

Usage:
    python3 serial_cmd.py "cmd1" "cmd2" ...
    python3 serial_cmd.py --wait 300 "date"        # wait for shell, then run
    echo <content> | python3 serial_cmd.py --write <path>
    python3 serial_cmd.py --timeout 1800 "javac Big.java"
    python3 serial_cmd.py --oneshot "date"         # direct connect, no mux
    python3 serial_cmd.py --status | --stop | --restart
    python3 serial_cmd.py --instance qemu-2 "date" # pick a VM when several run
    python3 serial_cmd.py --list                   # registered instances

Default mode goes through serial_mux.py (one persistent client per
instance): no per-command connects, no silence timeout, multi-minute
compiles just work, and the mux heals a dropped VirtualBox pipe at
runtime. The mux is started automatically if it is not running.

--oneshot opens the socket itself (the pre-mux behaviour). It is the
fallback for the rare case where the mux cannot start, and for
"connect once, walk away" probes; on VirtualBox every connect/disconnect
cycle moves the pipe server closer to the "only single connection
supported" wedge, so prefer the mux.

The instance model (which socket belongs to which VM) is defined in
jnode_paths.py; the same rules apply to KDB (jnode_kdb_cmd.py) and to the
QEMU/VirtualBox launchers.
"""

import json
import os
import random
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import jnode_paths as jp  # noqa: E402

MUX = os.path.join(HERE, "serial_mux.py")

DEFAULT_TIMEOUT = 1200.0
HEARTBEAT_SECS = 30.0
POLL_SECS = 0.2

# One-shot mode (see module docstring): generous prompt handling -- right
# after boot the console trickles bytes and a tight timeout raises false
# "prompt not seen" errors.
ONESHOT_PROMPT_TIMEOUT = 10.0
ONESHOT_PROMPT_RETRIES = 3
ONESHOT_OUTPUT_TIMEOUT = 10.0

PROMPT = b"[JNODE_AGENT_READY]"

# Multi-line file write support (same JNode echo semantics as before: the
# shell tokenizer turns \n \t \b \r inside quoted strings into real
# characters; echo appends one trailing newline).
WRITE_CHUNK_LIMIT = 6000


def escape_content(content):
    return (content.replace('\\', '\\\\')
                   .replace("'", "\\'")
                   .replace('\n', '\\n')
                   .replace('\t', '\\t')
                   .replace('\r', '\\r'))


def _chunks(content):
    if content.endswith('\n'):
        lines = content.split('\n')[:-1]
    else:
        lines = content.split('\n')
    chunks = []
    cur = []
    cur_len = 0
    for ln in lines:
        if cur:
            inc = len(escape_content('\n' + ln))
            if cur_len + inc > WRITE_CHUNK_LIMIT:
                chunks.append('\n'.join(cur))
                cur = [ln]
                cur_len = len(escape_content(ln))
            else:
                cur.append(ln)
                cur_len += inc
        else:
            cur = [ln]
            cur_len = len(escape_content(ln))
    if cur:
        chunks.append('\n'.join(cur))
    return chunks


def write_commands(path, content):
    """Command list that writes content to path (line-boundary-safe)."""
    if not content:
        return (["touch " + path], 0)
    chunks = _chunks(content)
    cmds = []
    if len(chunks) == 1:
        cmds.append("echo '" + escape_content(chunks[0]) + "' > " + path)
    else:
        parts = []
        for i, chunk in enumerate(chunks):
            part = path + '.part' + str(i)
            parts.append(part)
            cmds.append("echo '" + escape_content(chunk) + "' > " + part)
        cmds.append("cat " + ' '.join(parts) + " > " + path)
        for part in parts:
            cmds.append("rm " + part)
    cmds.append("wc -c " + path)
    if content.endswith('\n'):
        return (cmds, len(content))
    else:
        return (cmds, len(content) + 1)


# --------------------------------------------------------------------------
# Mux lifecycle (per instance)
# --------------------------------------------------------------------------

def mux_pid(paths):
    try:
        with open(paths.mux_pid, "r") as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def _read_status(paths):
    try:
        with open(paths.mux_status, "r") as f:
            return f.read().strip()
    except OSError:
        return ""


def ensure_mux(paths):
    pid = mux_pid(paths)
    if pid is not None:
        return pid
    for d in paths.ipc_dirs:
        try:
            os.makedirs(d)
        except OSError:
            pass
    with open(paths.mux_out, "ab") as out:
        subprocess.Popen([sys.executable, MUX, "--instance", paths.id],
                         stdin=subprocess.DEVNULL,
                         stdout=out, stderr=subprocess.STDOUT,
                         start_new_session=True)
    for _ in range(50):
        time.sleep(0.2)
        pid = mux_pid(paths)
        if pid is not None:
            return pid
    print("ERROR: serial_mux.py did not start (see %s)" % paths.mux_out)
    sys.exit(1)


def stop_mux(paths):
    pid = mux_pid(paths)
    if pid is None:
        print("mux not running (instance %s)" % paths.id)
        return
    try:
        os.kill(pid, 15)
    except OSError as e:
        print("ERROR signalling mux: %s" % e)
        sys.exit(1)
    for _ in range(50):
        time.sleep(0.2)
        if mux_pid(paths) is None:
            print("mux stopped (instance %s)" % paths.id)
            return
    print("ERROR: mux pid %d did not exit" % pid)
    sys.exit(1)


def show_status(paths):
    pid = mux_pid(paths)
    print("instance: %s%s" % (paths.id, " (legacy paths)" if paths.legacy
                              else ""))
    print("agent:    %s" % paths.agent_sock)
    print("boot log: %s" % paths.boot_log)
    if pid is None:
        print("mux: not running")
        sys.exit(1)
    print("mux: pid %d" % pid)
    status = _read_status(paths)
    print("status: %s" % (status if status else "(no status file yet)"))
    print("mux log: %s" % paths.mux_log)


def wait_ready(paths, seconds):
    """Block until the mux reports its link to the guest shell up."""
    ensure_mux(paths)
    deadline = time.time() + seconds
    while time.time() < deadline:
        if "link=up" in _read_status(paths):
            return True
        time.sleep(0.5)
    return False


# --------------------------------------------------------------------------
# Batch submission (per instance)
# --------------------------------------------------------------------------

def submit(paths, cmds, timeout):
    reqid = "%x%x" % (time.time_ns(), random.getrandbits(32))
    reqpath = os.path.join(paths.req_dir, reqid + ".json")
    with open(reqpath, "w") as f:
        json.dump({"cmds": cmds, "timeout": timeout, "pid": os.getpid()}, f)
    # Wake the mux (non-blocking; it also polls, so a full fifo is fine).
    try:
        fd = os.open(paths.fifo, os.O_WRONLY | os.O_NONBLOCK)
    except OSError:
        fd = None
    if fd is not None:
        try:
            os.write(fd, (reqid + "\n").encode("ascii"))
        except OSError:
            pass
        try:
            os.close(fd)
        except OSError:
            pass
    return reqid


def wait_result(paths, reqid, cmds, timeout):
    outpath = os.path.join(paths.resp_dir, reqid + ".out")
    donepath = os.path.join(paths.resp_dir, reqid + ".done")
    start = time.time()
    deadline = start + timeout + 60.0
    offset = 0
    pending = b""
    last_growth = start
    last_beat = start
    while True:
        try:
            with open(outpath, "rb") as f:
                f.seek(offset)
                data = f.read()
        except OSError:
            data = b""
        if data:
            offset += len(data)
            last_growth = time.time()
            pending += data
            text = pending.decode("utf-8", "replace")
            lines = text.split("\n")
            for line in lines[:-1]:
                if line.strip():
                    print(line)
            pending = lines[-1].encode("utf-8", "replace")
        if os.path.exists(donepath):
            if pending.decode("utf-8", "replace").strip():
                print(pending.decode("utf-8", "replace"))
            try:
                with open(donepath, "r") as f:
                    status = f.read().strip()
            except OSError:
                status = "OK ?"
            print("[batch %s]" % status)
            return 0 if status.startswith("OK") else 1
        now = time.time()
        if now > deadline:
            print("ERROR: batch %s gave no completion after %ds"
                  % (reqid, timeout + 60))
            return 1
        if now - last_growth > HEARTBEAT_SECS and now - last_beat > HEARTBEAT_SECS:
            last_beat = now
            print("[%ds still running: %s]"
                  % (int(now - start),
                     cmds[0][:60] if cmds else "?"), flush=True)
        time.sleep(POLL_SECS)


def run_batch(paths, cmds, timeout):
    ensure_mux(paths)
    reqid = submit(paths, cmds, timeout)
    return wait_result(paths, reqid, cmds, timeout)


# --------------------------------------------------------------------------
# One-shot mode (mux-free fallback)
# --------------------------------------------------------------------------

def drain_quiet(sock, quiet=0.4, cap=3.0):
    """Discard already-buffered bytes until the line stays quiet."""
    deadline = time.time() + cap
    last = time.time()
    while time.time() < deadline:
        try:
            sock.settimeout(quiet)
            data = sock.recv(4096)
            if not data:
                return
            last = time.time()
        except socket.timeout:
            if time.time() - last >= quiet:
                return
        except OSError:
            return


def oneshot(paths, cmds, timeout):
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(5)
    try:
        sock.connect(paths.agent_sock)
    except (FileNotFoundError, ConnectionRefusedError):
        print("ERROR: %s not found/refused. Is the instance '%s' running? "
              "(jnode_paths.py --list)" % (paths.agent_sock, paths.id))
        return 1
    except (BlockingIOError, InterruptedError) as e:
        print("ERROR: cannot connect to %s: %s" % (paths.agent_sock, e))
        print("HINT: another client may already hold this console socket "
              "(the mux?). Use serial_cmd.py without --oneshot, or "
              "serial_cmd.py --stop --instance %s first." % paths.id)
        return 1

    def wait_prompt(budget):
        deadline = time.time() + budget
        buf = b""
        try:
            sock.send(b"\n")
        except OSError:
            return False
        while time.time() < deadline:
            try:
                sock.settimeout(min(2.0, max(0.1, deadline - time.time())))
                data = sock.recv(4096)
                if data:
                    buf += data
                    if PROMPT in buf:
                        return True
                else:
                    return False
            except socket.timeout:
                try:
                    sock.send(b"\n")
                except OSError:
                    return False
        return False

    ready = False
    for attempt in range(1, ONESHOT_PROMPT_RETRIES + 1):
        if wait_prompt(ONESHOT_PROMPT_TIMEOUT):
            ready = True
            break
        if attempt < ONESHOT_PROMPT_RETRIES:
            print("Prompt not seen yet, retrying (%d/%d)..."
                  % (attempt, ONESHOT_PROMPT_RETRIES), file=sys.stderr)
            time.sleep(2.0)
    if not ready:
        print("ERROR: JNode shell prompt not seen (is the VM booted?)")
        print("HINT: right after boot the console trickles bytes; wait a "
              "few seconds and retry -- it self-recovers.")
        sock.close()
        return 1

    # F1 fix: the guest may emit the prompt twice (spontaneous console-open
    # prompt plus the reply to our probe newline). Consume any stale bytes
    # now, otherwise the command loop below breaks on a leftover prompt
    # and silently drops the command output.
    drain_quiet(sock)

    rc = 0
    cmd_deadline = time.time() + timeout
    for cmd in cmds:
        try:
            sock.send((cmd + "\r\n").encode("utf-8", "replace"))
        except OSError as e:
            print("ERROR sending %r: %s" % (cmd, e))
            rc = 1
            break
        buf = b""
        last_out = time.time()
        printed = 0
        while True:
            if time.time() > cmd_deadline:
                print("ERROR: --timeout exceeded in one-shot mode")
                rc = 1
                break
            remaining = last_out + ONESHOT_OUTPUT_TIMEOUT - time.time()
            if remaining <= 0:
                break
            try:
                sock.settimeout(min(1.0, remaining))
                data = sock.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                rc = 1
                break
            if not data:
                rc = 1
                break
            buf += data
            last_out = time.time()
            text = buf[printed:].decode("utf-8", "replace")
            lines = text.split("\n")
            for line in lines[:-1]:
                stripped = line.strip()
                if stripped and PROMPT.decode() not in stripped:
                    print(line)
            printed = len(buf) - len(lines[-1].encode("utf-8", "replace"))
            if PROMPT in buf:
                break
        if rc:
            break
        if printed < len(buf):
            for line in buf[printed:].decode("utf-8", "replace").split("\n"):
                stripped = line.strip()
                if stripped and PROMPT.decode() not in stripped:
                    print(line)
    sock.close()
    return rc


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def usage():
    print("Usage: python3 serial_cmd.py 'cmd1' 'cmd2' ...")
    print("       echo <content> | python3 serial_cmd.py --write <path>")
    print("       python3 serial_cmd.py [--instance ID] [--wait SECS] "
          "[--timeout SECS] 'cmd' ...")
    print("       python3 serial_cmd.py --oneshot 'cmd'   # no mux")
    print("       python3 serial_cmd.py --interrupt        # Ctrl-C running "
          "command")
    print("       python3 serial_cmd.py --status | --stop | --restart | "
          "--list")


def _signal_mux(paths, token):
    ensure_mux(paths)
    try:
        fd = os.open(paths.fifo, os.O_WRONLY | os.O_NONBLOCK)
    except OSError as e:
        print("ERROR: cannot reach mux fifo: %s" % e)
        sys.exit(1)
    try:
        os.write(fd, token)
    except OSError:
        pass
    try:
        os.close(fd)
    except OSError:
        pass


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        usage()
        sys.exit(0)

    instance = None
    if "--instance" in args:
        i = args.index("--instance")
        if i + 1 >= len(args):
            print("ERROR: --instance needs an id")
            sys.exit(2)
        instance = args[i + 1]
        del args[i:i + 2]

    # --list does not need a live instance; accept it in any position
    # (it used to be checked only as args[0], so `--instance X --list`
    # shipped `--list` to the guest shell).
    if args and args[0] == "--list":
        sys.exit(jp.main(["--list"]))

    if not args:
        # e.g. a bare `--instance foo`: report usage, not a traceback
        usage()
        sys.exit(2)

    try:
        paths = jp.resolve_paths(instance)
    except jp.InstanceError as e:
        print("ERROR: %s" % e)
        sys.exit(2)

    if args[0] == "--status":
        show_status(paths)
        return
    if args[0] == "--stop":
        stop_mux(paths)
        return
    if args[0] == "--restart":
        if mux_pid(paths) is not None:
            stop_mux(paths)
        ensure_mux(paths)
        print("mux restarted (instance %s)" % paths.id)
        return
    if args[0] in ("--interrupt", "--suspend"):
        # Ctrl-C (ETX) or Ctrl-Z (SUB) through the mux straight to the guest
        # shell; the guest turns them into job-control key events. Ctrl-Z
        # only unblocks: the command keeps running in the background.
        token = b"@@INT\n" if args[0] == "--interrupt" else b"@@TSTP\n"
        _signal_mux(paths, token)
        print("interrupt sent (%s)" % args[0])
        return

    timeout = DEFAULT_TIMEOUT
    wait_secs = None
    oneshot_mode = False
    write_path = None
    rest = []
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--timeout":
            if i + 1 >= len(args):
                print("ERROR: --timeout needs seconds")
                sys.exit(2)
            timeout = float(args[i + 1])
            i += 2
        elif arg == "--wait":
            if i + 1 >= len(args):
                print("ERROR: --wait needs seconds")
                sys.exit(2)
            wait_secs = float(args[i + 1])
            i += 2
        elif arg == "--oneshot":
            oneshot_mode = True
            i += 1
        elif arg == "--write":
            if i + 1 >= len(args):
                print("ERROR: --write requires a target path")
                sys.exit(2)
            write_path = args[i + 1]
            i += 2
        else:
            rest.append(arg)
            i += 1

    if write_path:
        content = sys.stdin.read()
        wcmds, expected = write_commands(write_path, content)
        print("writing %s (%d bytes expected; echo may add trailing NL)"
              % (write_path, expected))
        # keep commands given alongside --write instead of dropping them
        cmds = rest + wcmds
    else:
        cmds = rest

    if not cmds:
        usage()
        sys.exit(2)

    if wait_secs is not None:
        print("[waiting up to %gs for instance %s shell]"
              % (wait_secs, paths.id), flush=True)
        if not wait_ready(paths, wait_secs):
            print("ERROR: shell of instance %s not ready after %gs "
                  "(agent %s)" % (paths.id, wait_secs, paths.agent_sock))
            sys.exit(1)
    elif not os.path.exists(paths.agent_sock):
        # Fail fast instead of letting the mux retry until the batch
        # timeout: nobody is listening on that instance.
        print("ERROR: no agent socket for instance '%s' (%s)" %
              (paths.id, paths.agent_sock))
        print("  is that VM running?      jnode_paths.py --list")
        print("  booting and want to wait: serial_cmd.py --wait 300 ...")
        sys.exit(1)

    if oneshot_mode:
        sys.exit(oneshot(paths, cmds, timeout))
    sys.exit(run_batch(paths, cmds, timeout))


if __name__ == "__main__":
    main()

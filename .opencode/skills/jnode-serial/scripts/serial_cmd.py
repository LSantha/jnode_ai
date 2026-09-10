#!/usr/bin/env python3
"""Agent CLI for the JNode serial console via the persistent mux.

Usage:
    python3 serial_cmd.py "cmd1" "cmd2" ...
    echo <content> | python3 serial_cmd.py --write <path>
    python3 serial_cmd.py --timeout 1800 "javac Big.java"
    python3 serial_cmd.py --status | --stop | --restart

Same UX as the legacy jnode_agent_cmd.py, but commands run through
serial_mux.py (one persistent pipe client): no per-command connects,
no silence timeout, multi-minute compiles just work. The mux is started
automatically if it is not running.
"""

import json
import os
import random
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MUX = os.path.join(HERE, "serial_mux.py")

FIFO = "/tmp/jnode_serial_cmd.fifo"
REQDIR = "/tmp/jnode_serial_req"
RESPDIR = "/tmp/jnode_serial_resp"
PIDFILE = "/tmp/jnode_serial_mux.pid"
STATUS = "/tmp/jnode_serial_mux.status"
MUXOUT = "/tmp/jnode_serial_mux.out"

DEFAULT_TIMEOUT = 1200.0
HEARTBEAT_SECS = 30.0
POLL_SECS = 0.2

# Multi-line file write support (same JNode echo semantics as the legacy
# script: the shell tokenizer turns \n \t \b \r inside quoted strings
# into real characters; echo appends one trailing newline).
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


def mux_pid():
    try:
        with open(PIDFILE, "r") as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
        return pid
    except (OSError, ValueError):
        return None


def ensure_mux():
    pid = mux_pid()
    if pid is not None:
        return pid
    for d in (REQDIR, RESPDIR):
        try:
            os.makedirs(d)
        except OSError:
            pass
    with open(MUXOUT, "ab") as out:
        subprocess.Popen([sys.executable, MUX],
                         stdin=subprocess.DEVNULL,
                         stdout=out, stderr=subprocess.STDOUT,
                         start_new_session=True)
    for _ in range(50):
        time.sleep(0.2)
        pid = mux_pid()
        if pid is not None:
            return pid
    print("ERROR: serial_mux.py did not start (see %s)" % MUXOUT)
    sys.exit(1)


def stop_mux():
    pid = mux_pid()
    if pid is None:
        print("mux not running")
        return
    try:
        os.kill(pid, 15)
    except OSError as e:
        print("ERROR signalling mux: %s" % e)
        sys.exit(1)
    for _ in range(50):
        time.sleep(0.2)
        if mux_pid() is None:
            print("mux stopped")
            return
    print("ERROR: mux pid %d did not exit" % pid)
    sys.exit(1)


def show_status():
    pid = mux_pid()
    if pid is None:
        print("mux: not running")
        sys.exit(1)
    print("mux: pid %d" % pid)
    try:
        with open(STATUS, "r") as f:
            print("status: %s" % f.read().strip())
    except OSError:
        print("status: (no status file yet)")


def submit(cmds, timeout):
    reqid = "%x%x" % (time.time_ns(), random.getrandbits(32))
    reqpath = os.path.join(REQDIR, reqid + ".json")
    with open(reqpath, "w") as f:
        json.dump({"cmds": cmds, "timeout": timeout}, f)
    # Wake the mux (non-blocking; it also polls, so a full fifo is fine).
    try:
        fd = os.open(FIFO, os.O_WRONLY | os.O_NONBLOCK)
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


def wait_result(reqid, cmds, timeout):
    outpath = os.path.join(RESPDIR, reqid + ".out")
    donepath = os.path.join(RESPDIR, reqid + ".done")
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


def run_batch(cmds, timeout):
    ensure_mux()
    reqid = submit(cmds, timeout)
    return wait_result(reqid, cmds, timeout)


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print("Usage: python3 serial_cmd.py 'cmd1' 'cmd2' ...")
        print("       echo <content> | python3 serial_cmd.py --write <path>")
        print("       python3 serial_cmd.py [--timeout SECS] 'cmd' ...")
        print("       python3 serial_cmd.py --interrupt   # Ctrl-C the running command")
        print("       python3 serial_cmd.py --status | --stop | --restart")
        sys.exit(0)
    if args[0] == "--status":
        show_status()
        return
    if args[0] == "--stop":
        stop_mux()
        return
    if args[0] == "--restart":
        if mux_pid() is not None:
            stop_mux()
        ensure_mux()
        print("mux restarted")
        return
    if args[0] in ("--interrupt", "--suspend"):
        # Send Ctrl-C (ETX) or Ctrl-Z (SUB) through the mux straight to the
        # guest shell. The guest translates it into a Ctrl-C / Ctrl-Z key
        # event (job control), which kills or unblocks the running command.
        # Use this to recover a wedged shell instead of timing out and
        # abandoning it. Ctrl-Z only unblocks: the command keeps running in
        # the background and its output will interleave with later commands.
        ensure_mux()
        sig = b"@@INT\n" if args[0] == "--interrupt" else b"@@TSTP\n"
        try:
            fd = os.open(FIFO, os.O_WRONLY | os.O_NONBLOCK)
        except OSError as e:
            print("ERROR: cannot reach mux fifo: %s" % e)
            sys.exit(1)
        try:
            os.write(fd, sig)
        except OSError:
            pass
        try:
            os.close(fd)
        except OSError:
            pass
        print("interrupt sent (%s)" % args[0])
        return

    timeout = DEFAULT_TIMEOUT
    rest = args
    if args[0] == "--timeout":
        if len(args) < 3:
            print("ERROR: --timeout needs seconds + a command")
            sys.exit(1)
        timeout = float(args[1])
        rest = args[2:]

    if rest[0] == "--write":
        if len(rest) < 2:
            print("ERROR: --write requires a target path")
            sys.exit(1)
        path = rest[1]
        content = sys.stdin.read()
        cmds, expected = write_commands(path, content)
        print("writing %s (%d bytes expected; echo may add trailing NL)"
              % (path, expected))
        sys.exit(run_batch(cmds, timeout))

    sys.exit(run_batch(rest, timeout))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Interact with JNode Kernel Debugger (KDB) over the instance's UART1.

KDB is a single-character command protocol. Connect to the instance's KDB
socket (jnode_paths.py: /tmp/jnode.kdb for the legacy "default" instance,
/tmp/jnode/<id>/kdb.sock otherwise), send one char, read the response.

Usage:
    python3 jnode_kdb_cmd.py <command> [command ...]
    python3 jnode_kdb_cmd.py --instance qemu-2 W
    python3 jnode_kdb_cmd.py --mux          # start the persistent holder
    python3 jnode_kdb_cmd.py --stop         # stop the persistent holder

Commands:
    h, ?  - Help
    p     - Ping
    t     - Current thread info
    T     - Current thread + stack trace
    q     - Thread queues (ready + sleep)
    r     - Ready queue stack traces
    w     - Waiting threads (summary)
    W     - Waiting threads + stack traces
    v     - Verify thread states
    l     - Load/Compile queue
"""

import os
import signal
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import jnode_paths as jp  # noqa: E402
import kdb_mux as kdbmux  # noqa: E402

KDB_MUX = os.path.join(HERE, "kdb_mux.py")

KDB_COMMANDS = {
    'h': 'Help',
    '?': 'Help (alias)',
    'p': 'Ping',
    't': 'Current thread info',
    'T': 'Current thread + stack trace',
    'q': 'Thread queues',
    'r': 'Ready queue stack traces',
    'w': 'Waiting threads (summary)',
    'W': 'Waiting threads + stack traces',
    'v': 'Verify thread states',
    'l': 'Load/Compile queue',
}

RESP_TIMEOUT = 0.5
RESP_IDLE = 1.0


def connect_kdb(paths, retries=1):
    last = None
    for _ in range(max(1, retries)):
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(3)
        try:
            sock.connect(paths.kdb_sock)
            return sock
        except OSError as e:
            last = e
            sock.close()
            time.sleep(0.3)
    raise last if last else OSError("cannot connect")


def send_kdb_command(sock, cmd):
    sock.send(cmd.encode())
    time.sleep(0.3)
    buf = b""
    while True:
        try:
            sock.settimeout(RESP_TIMEOUT)
            data = sock.recv(4096)
            if data:
                buf += data
                continue
            break
        except socket.timeout:
            break
    return buf.decode(errors='replace').rstrip()


def start_mux(paths):
    existing = kdbmux.read_pid(paths)
    if existing and kdbmux._pid_alive(existing):
        print("kdb mux already running (pid %d, instance %s)"
              % (existing, paths.id))
    else:
        subprocess.Popen([sys.executable, KDB_MUX, "--instance", paths.id],
                         stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
        print("kdb mux started (instance %s)" % paths.id)
    print("  socket : %s" % paths.kdb_sock)
    print("  fifo   : %s   (echo '<cmd>' > fifo to send)" % paths.kdb_fifo)
    print("  log    : %s" % paths.kdb_resp)


def stop_mux(paths):
    pid = kdbmux.read_pid(paths)
    if not pid or not kdbmux._pid_alive(pid):
        print("kdb mux not running (instance %s)" % paths.id)
        try:
            os.unlink(paths.kdb_mux_pid)
        except OSError:
            pass
        return 0
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError as e:
        print("ERROR: cannot signal kdb mux pid %d: %s" % (pid, e))
        return 1
    for _ in range(20):
        if not kdbmux._pid_alive(pid):
            break
        time.sleep(0.25)
    if kdbmux._pid_alive(pid):
        os.kill(pid, signal.SIGKILL)
    try:
        os.unlink(paths.kdb_mux_pid)
    except OSError:
        pass
    print("kdb mux stopped (pid %d, instance %s)" % (pid, paths.id))
    return 0


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__.strip())
        sys.exit(0 if args else 1)

    instance = None
    if "--instance" in args:
        i = args.index("--instance")
        if i + 1 >= len(args):
            print("ERROR: --instance needs an id")
            sys.exit(2)
        instance = args[i + 1]
        del args[i:i + 2]

    try:
        paths = jp.resolve_paths(instance)
    except jp.InstanceError as e:
        print("ERROR: %s" % e)
        sys.exit(2)

    if args and args[0] == "--mux":
        start_mux(paths)
        return

    if args and args[0] == "--stop":
        sys.exit(stop_mux(paths))

    if not args:
        # a bare `--instance X`: report usage, not a silent exit 0
        print(__doc__.strip())
        sys.exit(2)

    commands = args
    for cmd in commands:
        if cmd not in KDB_COMMANDS:
            print("Unknown KDB command: %s" % cmd)
            print("Valid: %s" % ', '.join(KDB_COMMANDS.keys()))
            sys.exit(1)

    try:
        sock = connect_kdb(paths)
    except (FileNotFoundError, ConnectionRefusedError, OSError) as e:
        print("ERROR: %s: %s (%s)" % (paths.kdb_sock, e, paths.id))
        print("  instance registered?   jnode_paths.py --list")
        print("  UART1 wired for KDB?   see jnode-serial SKILL.md "
              "(QEMU: start_qemu.sh full; VBox: --uartmode1 server)")
        sys.exit(1)

    try:
        for cmd in commands:
            print(">>> KDB command: %s (%s)" % (cmd, KDB_COMMANDS[cmd]))
            output = send_kdb_command(sock, cmd)
            if output:
                print(output)
            print()
    finally:
        sock.close()


if __name__ == '__main__':
    main()

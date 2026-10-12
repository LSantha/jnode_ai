#!/usr/bin/env python3
#
# Copyright (C) 2003-2026 JNode.org
#
# This library is free software; you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as published
# by the Free Software Foundation; either version 2.1 of the License, or
# (at your option) any later version.
#
# This library is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
# or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
# License for more details.
#
# You should have received a copy of the GNU Lesser General Public License
# along with this library; If not, write to the Free Software Foundation,
# Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
"""KDB mux: hold the instance's UART1 KDB socket (single client), tee all
output to a log, and forward command lines dropped into the kdb fifo.

The socket comes from jnode_paths.py, so every VM gets its own KDB channel:
    default : /tmp/jnode.kdb            (legacy)
    <id>    : /tmp/jnode/<id>/kdb.sock

Usage: nohup python3 kdb_mux.py [--instance ID] >/dev/null 2>&1 &
    echo "t" > <kdb fifo> ; sleep 2; tail -n 20 <kdb resp log>
or simply:  python3 jnode_kdb_cmd.py W        (starts this automatically)
"""

import os
import select
import signal
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jnode_paths as jp  # noqa: E402

CONNECT_RETRY_SECS = 1.0
CONNECT_GIVEUP_SECS = 60.0


def _pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def read_pid(paths):
    try:
        with open(paths.kdb_mux_pid) as f:
            return int(f.read().strip())
    except (OSError, ValueError):
        return None


def acquire_pidfile(paths):
    """True when this process now owns the instance's KDB mux slot."""
    existing = read_pid(paths)
    if existing and _pid_alive(existing):
        return False
    try:
        os.makedirs(os.path.dirname(paths.kdb_mux_pid), exist_ok=True)
        with open(paths.kdb_mux_pid, "w") as f:
            f.write("%d\n" % os.getpid())
    except OSError:
        pass
    return True


def log_line(path, data):
    try:
        with open(path, "ab", buffering=0) as f:
            f.write(data)
    except OSError:
        pass


def run(paths):
    for d in paths.kdb_ipc_dirs:
        try:
            os.makedirs(d)
        except OSError:
            pass
    try:
        os.mkfifo(paths.kdb_fifo)
    except FileExistsError:
        pass
    except OSError as e:
        print("cannot create fifo: %s" % e, file=sys.stderr)
        return 1

    while True:
        # Connect (or reconnect after the guest/hypervisor dropped us).
        sock = None
        deadline = time.time() + CONNECT_GIVEUP_SECS
        while time.time() < deadline:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(5)
            try:
                s.connect(paths.kdb_sock)
                sock = s
                break
            except OSError:
                s.close()
                time.sleep(CONNECT_RETRY_SECS)
        if sock is None:
            print("kdb_mux: cannot reach %s (instance %s) after %ds"
                  % (paths.kdb_sock, paths.id, CONNECT_GIVEUP_SECS),
                  file=sys.stderr)
            return 1

        sock.setblocking(False)
        print("mux connected (%s)" % paths.kdb_sock, flush=True)
        try:
            fifo = os.open(paths.kdb_fifo, os.O_RDONLY | os.O_NONBLOCK)
        except OSError as e:
            print("kdb_mux: fifo: %s" % e, file=sys.stderr)
            return 1
        dead = False
        while not dead:
            r, _, _ = select.select([sock, fifo], [], [], 0.2)
            if sock in r:
                try:
                    data = sock.recv(65536)
                except BlockingIOError:
                    data = b""
                except OSError:
                    data = b""
                if data:
                    log_line(paths.kdb_resp, data)
                else:
                    # EOF: the VM went away (or KDB released the port).
                    dead = True
            if fifo in r and not dead:
                try:
                    cmd = os.read(fifo, 4096)
                except BlockingIOError:
                    cmd = b""
                except OSError:
                    cmd = b""
                for line in cmd.split(b"\n"):
                    line = line.strip()
                    if line:
                        log_line(paths.kdb_resp, b">>> " + line + b"\n")
                        try:
                            sock.send(line[:1])   # KDB takes single chars
                        except OSError:
                            dead = True
                        time.sleep(0.3)
        try:
            sock.close()
            os.close(fifo)
        except OSError:
            pass
        print("kdb_mux: link lost, reconnecting to %s" % paths.kdb_sock,
              flush=True)
        time.sleep(CONNECT_RETRY_SECS)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] in ("-h", "--help"):
        print("Usage: python3 kdb_mux.py [--instance ID]")
        print("       (normally started automatically by jnode_kdb_cmd.py)")
        return 0
    instance = None
    if "--instance" in argv:
        i = argv.index("--instance")
        if i + 1 >= len(argv):
            print("kdb_mux.py: --instance needs an id", file=sys.stderr)
            return 2
        instance = argv[i + 1]
        del argv[i:i + 2]
    if argv:
        print("kdb_mux.py: unknown arguments: %s" % " ".join(argv),
              file=sys.stderr)
        return 2
    try:
        paths = jp.resolve_paths(instance)
    except jp.InstanceError as e:
        print("kdb_mux.py: %s" % e, file=sys.stderr)
        return 2
    # single-instance guard: two muxes on one FIFO/UART1 socket is the
    # "only single connection supported" wedge this module exists to avoid
    if not acquire_pidfile(paths):
        print("kdb_mux.py: a kdb mux is already running for instance %s "
              "(pid %d)" % (paths.id, read_pid(paths)), file=sys.stderr)
        return 0

    # Python does not unwind on SIGTERM, so without this handler the
    # finally-block below would never remove the pid file on a normal
    # `kill -TERM` (jnode_kdb_cmd.stop_mux's unlink is the backstop)
    def _sigterm(_sig, _frame):
        raise SystemExit(143)
    signal.signal(signal.SIGTERM, _sigterm)
    try:
        return run(paths)
    finally:
        try:
            if read_pid(paths) == os.getpid():
                os.unlink(paths.kdb_mux_pid)
        except OSError:
            pass


if __name__ == "__main__":
    sys.exit(main())

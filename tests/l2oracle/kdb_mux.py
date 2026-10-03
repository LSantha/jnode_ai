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
# along with this library; If not, write to the Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
#
"""KDB mux: hold /tmp/jnode.kdb (single client), tee all output to a log,
and forward command lines dropped into /tmp/kdb_cmd.fifo.
Usage: nohup python3 kdb_mux.py >/tmp/kdb_mux.out 2>&1 &
  echo "t" > /tmp/kdb_cmd.fifo ; sleep 2; tail -n 20 /tmp/kdb_resp.log

NOTE: mirror of the canonical copy in the jnode-kdb-serial skill
(~/.config/opencode/skills/jnode-kdb-serial/scripts/kdb_mux.py); keep in
sync. This copy exists so the suite is self-contained.
"""
import os, socket, select, sys, time

SOCK = "/tmp/jnode.kdb"
FIFO = "/tmp/kdb_cmd.fifo"
LOG = "/tmp/kdb_mux.log"
RESP = "/tmp/kdb_resp.log"

def main():
    try:
        os.mkfifo(FIFO)
    except FileExistsError:
        pass
    fifo = os.open(FIFO, os.O_RDONLY | os.O_NONBLOCK)
    logf = open(LOG, "ab", buffering=0)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(10)
    s.connect(SOCK)
    s.setblocking(False)
    print("mux connected", flush=True)
    pending_resp = open(RESP, "ab", buffering=0)
    buf = b""
    while True:
        r, _, _ = select.select([s, fifo], [], [], 0.2)
        if s in r:
            try:
                d = s.recv(65536)
            except BlockingIOError:
                d = b""
            if d:
                logf.write(d)
                # echo short responses (KDB replies) to resp log too
                pending_resp.write(d)
            else:
                time.sleep(0.1)
        if fifo in r:
            try:
                cmd = os.read(fifo, 4096)
            except BlockingIOError:
                cmd = b""
            if cmd:
                for line in cmd.split(b"\n"):
                    line = line.strip()
                    if line:
                        pending_resp.write(b">>> " + line + b"\n")
                        s.send(line[:1])  # KDB takes single chars
                        time.sleep(0.3)

if __name__ == "__main__":
    main()

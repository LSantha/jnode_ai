#!/usr/bin/env python3
"""Fake JNode agent console for skill tests (no VM needed).

Speaks the SerialConsolePlugin protocol closely enough for serial_mux.py
and serial_cmd.py:
  * prompt is "\\n[JNODE_AGENT_READY]\\n" (newlines both sides, like the
    real `jnode.prompt` property in SerialConsolePlugin)
  * input is never echoed, output lines are CRLF-terminated
  * "echo X" prints X, "sleep N" blocks N seconds, anything else prints
    "OUT:<cmd>"
  * connection stays open (one client, like the real console)

Usage: python3 fake_guest.py <socket-path>
"""

import os
import socket
import sys
import time

PROMPT = b"\n[JNODE_AGENT_READY]\n"


def handle(conn):
    conn.sendall(PROMPT)
    buf = b""
    while True:
        data = conn.recv(4096)
        if not data:
            return
        buf += data
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            line = line.replace(b"\r", b"").decode("utf-8", "replace").strip()
            if not line:
                conn.sendall(PROMPT)
                continue
            if line in ("exit", "quit"):
                conn.sendall(b"bye\r\n")
                conn.sendall(PROMPT)
                return
            if line.startswith("echo "):
                conn.sendall((line[5:] + "\r\n").encode("utf-8"))
            elif line.startswith("sleep "):
                try:
                    time.sleep(float(line.split(None, 1)[1]))
                except (IndexError, ValueError):
                    pass
                conn.sendall(b"slept\r\n")
            elif line.startswith("wc -c "):
                target = line.split(None, 2)[2]
                try:
                    size = os.path.getsize(target)
                except OSError:
                    size = -1
                conn.sendall(("%s %d\r\n" % (target, size)).encode("utf-8"))
            else:
                conn.sendall(("OUT:%s\r\n" % line).encode("utf-8"))
            conn.sendall(PROMPT)


def main():
    if len(sys.argv) != 2:
        print("Usage: fake_guest.py <socket-path>")
        sys.exit(2)
    path = sys.argv[1]
    try:
        os.unlink(path)
    except OSError:
        pass
    os.makedirs(os.path.dirname(path), exist_ok=True)
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(path)
    srv.listen(4)
    print("fake guest listening on %s" % path, flush=True)
    while True:
        conn, _ = srv.accept()
        try:
            handle(conn)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass


if __name__ == "__main__":
    main()

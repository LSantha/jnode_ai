#!/usr/bin/env python3
"""
Super-fast JNode serial console interaction for agent mode (-a).

Usage:
    python3 jnode_agent_cmd.py "cmd1" "cmd2" ...
"""

import socket
import time
import sys


PROMPT = b'[JNODE_AGENT_READY]'
PROMPT_TIMEOUT = 3.0
CMD_TIMEOUT = 2.0


def connect_serial():
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(5)
    sock.connect('/tmp/jnode_com2')
    return sock


def clear_buffer(sock):
    """Clear any pending data in socket buffer."""
    sock.settimeout(0.1)
    while True:
        try:
            r = sock.recv(4096)
            if not r:
                break
        except socket.timeout:
            break
    sock.settimeout(CMD_TIMEOUT)


def wait_for_prompt(sock, timeout):
    """Send a newline and wait for the [JNODE_AGENT_READY] prompt."""
    deadline = time.time() + timeout
    buf = b""
    sock.send(b'\n')
    while time.time() < deadline:
        try:
            sock.settimeout(min(2.0, deadline - time.time()))
            r = sock.recv(4096)
            if r:
                buf += r
                if PROMPT in buf:
                    return True
            else:
                break
        except socket.timeout:
            sock.send(b'\n')
            continue
    return False


def send_cmd(sock, cmd):
    """Send command and read response."""
    clear_buffer(sock)

    sock.send(f"{cmd}\r\n".encode())

    buf = b""
    while True:
        try:
            sock.settimeout(CMD_TIMEOUT)
            r = sock.recv(4096)
            if r:
                buf += r
                if b'Packet statistics' in buf:
                    time.sleep(0.3)
                    break
            else:
                break
        except socket.timeout:
            break

    output = buf.decode(errors='replace').rstrip()
    if output:
        for line in output.split('\n'):
            if line.strip():
                print(line)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 jnode_agent_cmd.py 'cmd1' 'cmd2' ...")
        sys.exit(1)

    try:
        s = connect_serial()
        if not wait_for_prompt(s, PROMPT_TIMEOUT):
            print("ERROR: JNode shell prompt not seen (is the VM booted?)")
            sys.exit(1)
        for cmd in sys.argv[1:]:
            send_cmd(s, cmd)
        s.close()
    except FileNotFoundError:
        print("ERROR: /tmp/jnode_com2 not found. Is JNode running?")
        sys.exit(1)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
Super-fast JNode serial console interaction for agent mode (-a).

Usage:
    python3 jnode_agent_cmd.py "cmd1" "cmd2" ...
"""

import socket
import time
import sys


def connect_serial():
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(5)
    sock.connect('/tmp/jnode_com2')
    return sock


PROMPT = b'[JNODE_AGENT_READY]'
PROMPT_TIMEOUT = 3.0
OUTPUT_TIMEOUT = 10.0


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
    """Send command and stream response until prompt or output timeout."""
    clear_buffer(sock)

    sock.send(f"{cmd}\r\n".encode())

    buf = b""
    last_output_time = time.time()
    printed_up_to = 0

    while True:
        try:
            remaining = last_output_time + OUTPUT_TIMEOUT - time.time()
            if remaining <= 0:
                break
            sock.settimeout(min(1.0, remaining))
            r = sock.recv(4096)
            if r:
                buf += r
                last_output_time = time.time()

                # Print new complete lines immediately
                decoded = buf[printed_up_to:].decode(errors='replace')
                lines = decoded.split('\n')
                for line in lines[:-1]:
                    stripped = line.strip()
                    if stripped and PROMPT.decode() not in stripped:
                        print(line)
                printed_up_to = len(buf) - len(lines[-1].encode(errors='replace'))

                if PROMPT in buf:
                    break
            else:
                break
        except socket.timeout:
            if time.time() - last_output_time >= OUTPUT_TIMEOUT:
                break

    # Print any remaining buffer
    if printed_up_to < len(buf):
        remaining = buf[printed_up_to:].decode(errors='replace')
        for line in remaining.split('\n'):
            stripped = line.strip()
            if stripped and PROMPT.decode() not in stripped:
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

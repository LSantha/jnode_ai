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
    sock.settimeout(5)


def send_cmd(sock, cmd):
    """Send command and read response."""
    clear_buffer(sock)  # Clear old data!
    
    sock.send(f"{cmd}\r\n".encode())
    time.sleep(0.1)
    
    # Read response - wait longer for ping/wget
    buf = b""
    while True:
        try:
            sock.settimeout(2.0)
            r = sock.recv(4096)
            if r:
                buf += r
                # For ping, wait for final statistics
                if b'Packet statistics' in buf:
                    time.sleep(0.3)
                    break
            else:
                break
        except socket.timeout:
            break
    
    # Output
    output = buf.decode(errors='replace').rstrip()
    if output:
        # Print each line, preserve formatting
        for line in output.split('\n'):
            if line.strip():
                print(line)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 jnode_agent_cmd.py 'cmd1' 'cmd2' ...")
        sys.exit(1)

    try:
        s = connect_serial()
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

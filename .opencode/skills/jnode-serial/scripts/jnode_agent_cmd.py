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
    sock.connect('/tmp/jnode.serial2')
    return sock


PROMPT = b'[JNODE_AGENT_READY]'
PROMPT_TIMEOUT = 3.0
OUTPUT_TIMEOUT = 10.0

# Multi-line file write support.
# The JNode shell tokenizer converts '\\n', '\\t', '\\b', '\\r' escapes inside
# quoted strings into real characters (DefaultInterpreter.Tokenizer), so one
# single-quoted echo argument can produce a whole multi-line file.
#
# echo always appends a trailing newline (echo ends with println()), so chunking
# must respect LINE boundaries: each chunk is whole lines joined by '\n' with no
# trailing '\n', and echo's println() provides that final byte. Char-boundary
# chunking corrupts files (extra '\n' inserted mid-line at every boundary).
# Files not ending in a newline gain one trailing '\n' on the box (inherent to
# echo); --- that is the only allowed discrepancy (reported by the verifier).
WRITE_CHUNK_LIMIT = 6000  # max chars of (escaped) content per echo command


def escape_content(content):
    """Escape content so it can live inside a single-quoted JNode echo arg.

    Escapes backslashes (the tokenizer consumes a backslash before other
    chars), single quotes (which would terminate / toggle the full-escape),
    and real newlines / tabs / CRs into '\\n' / '\\t' / '\\r' sequences which
    the tokenizer turns back into real characters.
    """
    return (content.replace('\\', '\\\\')
                   .replace("'", "\\'")
                   .replace('\n', '\\n')
                   .replace('\t', '\\t')
                   .replace('\r', '\\r'))


def _chunks(content):
    """Split content into whole-line chunks <= WRITE_CHUNK_LIMIT (escaped).

    Chunks never split a line. Each chunk is lines joined with '\n' and NO
    trailing '\n' - echo supplies that newline when writing the part file.
    A single line longer than the limit becomes its own (over-limit) chunk,
    since splitting it would corrupt the line.
    """
    # Dropping the trailing '' produced by split('\n') when the content ends
    # with '\n'; echo's appended newline restores it byte-for-byte.
    if content.endswith('\n'):
        lines = content.split('\n')[:-1]
    else:
        lines = content.split('\n')
    chunks = []
    cur = []
    cur_len = 0  # escaped length so far in current chunk
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


def write_file(sock, path, content):
    """Write multi-line content to a JNode file through the serial agent.

    Chunking is line-boundary-safe: each chunk is whole lines (no trailing
    '\n'), written as 'echo '<esc>' > part', then all parts are concatenated
    with 'cat'. This keeps files byte-identical to the source for files ending
    in a newline.
    """
    if not content:
        send_cmd(sock, "touch " + path)
        return 0
    chunks = _chunks(content)
    if len(chunks) == 1:
        # The chunk (not the raw content) has the trailing '\n' stripped, so
        # echo's appended newline restores the byte-exact original.
        send_cmd(sock, "echo '" + escape_content(chunks[0]) + "' > " + path)
    else:
        parts = []
        for i, chunk in enumerate(chunks):
            part = path + '.part' + str(i)
            parts.append(part)
            send_cmd(sock, "echo '" + escape_content(chunk) + "' > " + part)
        send_cmd(sock, "cat " + ' '.join(parts) + " > " + path)
        for part in parts:
            send_cmd(sock, "rm " + part)
    # echo appends a newline; file size == content size when content ends with
    # a newline, otherwise content size + 1 (echo's trailing '\n').
    if content.endswith('\n'):
        return len(content)
    else:
        return len(content) + 1


def _write_file(path, content):
    """Full serial-session wrapper for --write."""
    try:
        s = connect_serial()
        if not wait_for_prompt(s, PROMPT_TIMEOUT):
            print("ERROR: JNode shell prompt not seen (is the VM booted?)")
            sys.exit(1)
        expected = write_file(s, path, content)
        send_cmd(s, "wc -c " + path)
        print(f"written {expected} bytes expected (echo may add trailing NL)")
        s.close()
    except FileNotFoundError:
        print("ERROR: /tmp/jnode.serial2 not found. Is JNode running?")
        sys.exit(1)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(1)


run_write = _write_file


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
                # Print all but the last (possibly incomplete) line
                for line in lines[:-1]:
                    stripped = line.strip()
                    if stripped and PROMPT.decode() not in stripped:
                        print(line)
                # Track how much we've printed
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
        print("       echo <content> | python3 jnode_agent_cmd.py --write <path>")
        sys.exit(1)

    if sys.argv[1] == '--write':
        if len(sys.argv) < 3:
            print("ERROR: --write requires a target path")
            sys.exit(1)
        path = sys.argv[2]
        content = sys.stdin.read()
        run_write(path, content)
        return

    try:
        s = connect_serial()
        if not wait_for_prompt(s, PROMPT_TIMEOUT):
            print("ERROR: JNode shell prompt not seen (is the VM booted?)")
            sys.exit(1)
        for cmd in sys.argv[1:]:
            send_cmd(s, cmd)
        s.close()
    except FileNotFoundError:
        print("ERROR: /tmp/jnode.serial2 not found. Is JNode running?")
        sys.exit(1)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()

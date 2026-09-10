#!/usr/bin/env python3
"""Persistent single-client proxy for the JNode serial agent console.

Holds ONE connection to /tmp/jnode.serial2 (VirtualBox UART2 pipe server)
for the whole session and multiplexes agent command batches through it.

Why: one connection per command eventually wedges the VBox pipe server
("NamedPipe0: only single connection supported" in VBox.log, immediate
FIN/EOF on every new attach while the guest stays healthy). Holding a
single client avoids the stuck state entirely (same pattern as the stable
kdb_mux.py on UART1).

Protocol (all files under /tmp, US-ASCII):
  REQ dir  /tmp/jnode_serial_req/<reqid>.json   {cmds:[...], timeout:secs}
  wakeup   FIFO /tmp/jnode_serial_cmd.fifo      client writes "<reqid>\\n"
  RESP dir /tmp/jnode_serial_resp/<reqid>.out   mux appends command output
           /tmp/jnode_serial_resp/<reqid>.done  "OK <n>" | "TIMEOUT <msg>"
           | "ERROR <msg>"
  status   /tmp/jnode_serial_mux.status         "pid link cmds=<n> ..."
  log      /tmp/jnode_serial_mux.log            connects, replugs, errors

Framing on the wire: each command is wrapped as
    echo __JSM_B_<id>__
    <command>
    echo __JSM_E_<id>__
and the mux captures everything between the two marker lines, then waits
for the [JNODE_AGENT_READY] prompt before running the next command.
The guest agent console does not echo input, so the capture is exactly
the command's output. No silence timeout: a command completes on
END-marker + prompt, however long that takes (up to the request timeout).

Self-healing: on FIN/RST/EPIPE the mux reconnects with backoff. If new
attaches are FINed immediately (the VBox stuck signature), it resets the
pipe server at runtime (no power cycle):
    vboxmanage controlvm JNode changeuartmode2 disconnected
    vboxmanage controlvm JNode changeuartmode2 server /tmp/jnode.serial2

Usage: nohup python3 serial_mux.py >/tmp/jnode_serial_mux.out 2>&1 &
Clients should use serial_cmd.py (starts the mux automatically).
"""

import json
import os
import select
import socket
import subprocess
import sys
import time

SOCK = "/tmp/jnode.serial2"
FIFO = "/tmp/jnode_serial_cmd.fifo"
REQDIR = "/tmp/jnode_serial_req"
RESPDIR = "/tmp/jnode_serial_resp"
LOG = "/tmp/jnode_serial_mux.log"
PIDFILE = "/tmp/jnode_serial_mux.pid"
STATUS = "/tmp/jnode_serial_mux.status"
VM = "JNode"

PROMPT = b"[JNODE_AGENT_READY]"

HANDSHAKE_TIMEOUT = 10.0
SEND_TIMEOUT = 30.0
RECONNECT_BASE_DELAY = 1.0
RECONNECT_MAX_DELAY = 10.0
# After this many consecutive immediate-FIN attaches, reset the pipe server.
REPLUG_AFTER_FINS = 3
# Replug the pipe server if a batch is in flight but not a single byte
# arrives for this long. The guest always answers a fresh command with the
# BEGIN-marker echo within seconds, so total RX silence means the VBox UART
# itself wedged (observed with no log line at all). Replug is harmless to a
# healthy guest; the in-flight command is resent afterwards (it may run
# twice -- documented caveat, same as reconnect-resend).
WEDGE_SILENCE = 180.0
# Replug at most this often (seconds) to avoid hammering VBox.
REPLUG_MIN_INTERVAL = 15.0
DEFAULT_REQ_TIMEOUT = 1200.0


def log(msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S") + " mux " + msg + "\n"
    try:
        with open(LOG, "ab") as f:
            f.write(line.encode("ascii", "replace"))
    except OSError:
        pass


def write_status(pid, link, cmds):
    try:
        with open(STATUS, "w") as f:
            f.write("pid=%d link=%s cmds=%d time=%d\n"
                    % (pid, link, cmds, int(time.time())))
    except OSError:
        pass


def replug_pipe():
    """Reset the VBox UART2 pipe server at runtime (VM keeps running)."""
    try:
        subprocess.run(["vboxmanage", "controlvm", VM,
                        "changeuartmode2", "disconnected"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=30)
        time.sleep(1.0)
        subprocess.run(["vboxmanage", "controlvm", VM,
                        "changeuartmode2", "server", SOCK],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=30)
        log("replug done (disconnected + server " + SOCK + ")")
        return True
    except Exception as e:
        log("replug FAILED: " + str(e))
        return False


class Link(object):
    """Persistent pipe client with reconnect + replug recovery."""

    def __init__(self):
        self.sock = None
        self.buf = b""
        self.consec_fins = 0
        self.last_replug = 0.0
        self.retry_delay = RECONNECT_BASE_DELAY

    def send_blob(self, blob):
        """Send the whole blob, blocking up to SEND_TIMEOUT secs.

        The socket is normally non-blocking (for the select loop), but
        a single send() on a non-blocking socket can raise EAGAIN or
        deliver only part of the blob when the peer is slow. A partial
        send without the trailing newline would leave the guest shell's
        line reader waiting for EOL forever, so sends must be complete.
        Only a hard failure (EPIPE/RST/closed) reports link loss.
        """
        s = self.sock
        if s is None:
            raise IOError("not connected")
        s.setblocking(True)
        try:
            s.settimeout(SEND_TIMEOUT)
            view = memoryview(blob)
            while view:
                try:
                    n = s.send(view)
                except socket.timeout:
                    raise IOError("send timed out")
                if n == 0:
                    raise IOError("send returned 0")
                view = view[n:]
        except OSError as e:
            raise IOError("send failed: %s" % e)
        finally:
            try:
                s.setblocking(False)
            except OSError:
                pass

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def _connect_once(self):
        """One attach attempt. Returns (sock, immediate_fin)."""
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(5.0)
        try:
            s.connect(SOCK)
        except OSError:
            try:
                s.close()
            except OSError:
                pass
            return None, False
        # Immediate FIN/EOF signature of the stuck server: recv returns
        # b"" at once, before we send anything.
        s.settimeout(1.0)
        try:
            d = s.recv(4096)
        except socket.timeout:
            # Server holding the connection open without greeting: usable.
            return s, False
        except OSError:
            try:
                s.close()
            except OSError:
                pass
            return None, True
        if d == b"":
            try:
                s.close()
            except OSError:
                pass
            return None, True
        # Unexpected greeting data: keep it, connection usable.
        return s, False

    def _handshake(self, s):
        """Wait for the agent prompt. Returns True if shell is ready."""
        deadline = time.time() + HANDSHAKE_TIMEOUT
        buf = b""
        try:
            s.send(b"\n")
        except OSError:
            return False
        while time.time() < deadline:
            try:
                s.settimeout(min(2.0, deadline - time.time()))
                d = s.recv(4096)
                if d:
                    buf += d
                    if PROMPT in buf:
                        return True
                else:
                    return False
            except socket.timeout:
                try:
                    s.send(b"\n")
                except OSError:
                    return False
            except OSError:
                return False
        return False


def prune_respdir():
    now = time.time()
    try:
        names = os.listdir(RESPDIR)
    except OSError:
        return
    for n in names:
        p = os.path.join(RESPDIR, n)
        try:
            if now - os.path.getmtime(p) > 24 * 3600:
                os.unlink(p)
        except OSError:
            pass


def main():
    for d in (REQDIR, RESPDIR):
        try:
            os.makedirs(d)
        except OSError:
            pass
    try:
        os.mkfifo(FIFO)
    except FileExistsError:
        pass
    except OSError as e:
        print("cannot create fifo: %s" % e, file=sys.stderr)
        sys.exit(1)
    prune_respdir()

    pid = os.getpid()
    try:
        with open(PIDFILE, "w") as f:
            f.write(str(pid) + "\n")
    except OSError:
        pass

    fifo = os.open(FIFO, os.O_RDONLY | os.O_NONBLOCK)
    log("started pid=%d" % pid)

    def reopen_fifo(old):
        try:
            os.close(old)
        except OSError:
            pass
        return os.open(FIFO, os.O_RDONLY | os.O_NONBLOCK)

    link = Link()
    queue = []          # pending reqids (fifo order)
    cur = None          # active request dict or None
    cmd_count = 0
    write_status(pid, "down", 0)

    def load_request(reqid):
        path = os.path.join(REQDIR, reqid + ".json")
        try:
            with open(path, "r") as f:
                data = json.load(f)
            cmds = data.get("cmds", [])
            timeout = float(data.get("timeout", DEFAULT_REQ_TIMEOUT))
            return {"reqid": reqid, "cmds": list(cmds),
                    "timeout": timeout, "start": time.time(),
                    "idx": 0, "phase": "idle", "lines": b"",
                    "out": open(os.path.join(RESPDIR, reqid + ".out"),
                                "ab", buffering=0)}
        except Exception as e:
            log("bad request %s: %s" % (reqid, e))
            try:
                with open(os.path.join(RESPDIR, reqid + ".done"),
                          "w") as f:
                    f.write("ERROR bad request: %s\n" % e)
            except OSError:
                pass
            return None

    def finish_request(req, status):
        try:
            req["out"].close()
        except OSError:
            pass
        try:
            with open(os.path.join(RESPDIR, req["reqid"] + ".done"),
                      "w") as f:
                f.write(status + "\n")
        except OSError:
            pass
        try:
            os.unlink(os.path.join(REQDIR, req["reqid"] + ".json"))
        except OSError:
            pass

    def start_next_cmd(req):
        """Send BEGIN + command + END lines for req cmds[req idx]."""
        cid = "%s_%02d" % (req["reqid"], req["idx"])
        req["cid"] = cid
        req["bmark"] = ("__JSM_B_" + cid + "__").encode("ascii")
        req["emark"] = ("__JSM_E_" + cid + "__").encode("ascii")
        req["phase"] = "wait_begin"
        req["cap"] = b""
        cmd = req["cmds"][req["idx"]]
        blob = ("echo __JSM_B_" + cid + "__\r\n" + cmd + "\r\n"
                + "echo __JSM_E_" + cid + "__\r\n").encode("utf-8",
                                                          "replace")
        link.send_blob(blob)

    def emit(req, data, flush=False):
        """Write complete, meaningful lines to the response file.

        Drops the interleaved [JNODE_AGENT_READY] prompts (the guest
        prints one after every command, including the marker echoes)
        and blank lines, same as the legacy one-shot script.
        """
        req["lines"] += data
        text = req["lines"].decode("utf-8", "replace")
        parts = text.split("\n")
        if flush:
            complete, req["lines"] = parts, b""
        else:
            complete, rest = parts[:-1], parts[-1]
            req["lines"] = rest.encode("utf-8", "replace")
        for line in complete:
            s = line.strip()
            if s and PROMPT.decode("ascii") not in s:
                req["out"].write((line + "\n").encode("utf-8",
                                                      "replace"))

    def process_stream(req):
        """Consume link.buf; returns True when current command is done."""
        bmark = req["bmark"]
        emark = req["emark"]
        if req["phase"] == "wait_begin":
            i = link.buf.find(bmark)
            if i < 0:
                # Keep only a tail (marker may straddle recvs).
                if len(link.buf) > 256:
                    link.buf = link.buf[-256:]
                # Prompt without marker: shell ready but echo lost;
                # re-send markers rather than hang.
                if PROMPT in link.buf:
                    link.buf = b""
                    start_next_cmd(req)
                return False
            link.buf = link.buf[i + len(bmark):]
            req["phase"] = "capture"
        if req["phase"] == "capture":
            j = link.buf.find(emark)
            if j < 0:
                # Stream everything so far (minus a tail kept for a
                # split marker), then wait for more.
                keep = len(emark) + 8
                if len(link.buf) > keep:
                    emit(req, link.buf[:-keep].replace(b"\r", b""))
                    link.buf = link.buf[-keep:]
                return False
            emit(req, link.buf[:j].replace(b"\r", b""), flush=True)
            link.buf = link.buf[j + len(emark):]
            req["phase"] = "wait_prompt"
        if req["phase"] == "wait_prompt":
            k = link.buf.find(PROMPT)
            if k < 0:
                return False
            link.buf = link.buf[k + len(PROMPT):]
            return True
        return False

    link_up = False
    pending_sig = None
    last_rx = time.time()
    while True:
        if cur is None and queue:
            reqid = queue.pop(0)
            req = load_request(reqid)
            if req is not None:
                cur = req
                log("batch %s start (%d cmds)" % (reqid, len(req["cmds"])))
        if cur is not None and not cur["cmds"]:
            finish_request(cur, "OK 0")
            cur = None
            continue

        want_sock = link.sock
        rfds = [fifo]
        if want_sock is not None:
            rfds.append(want_sock)
        try:
            r, _, _ = select.select(rfds, [], [], 0.2)
        except (select.error, OSError):
            continue

        if fifo in r:
            try:
                data = os.read(fifo, 65536)
            except BlockingIOError:
                data = b""
            except OSError:
                data = b""
            if data:
                for line in data.split(b"\n"):
                    line = line.strip().decode("ascii", "ignore")
                    if not line:
                        continue
                    if line in ("@@INT", "@@TSTP"):
                        # Ctrl-C / Ctrl-Z: deliver immediately, never queue.
                        # The guest translates ETX/SUB into Ctrl-C / Ctrl-Z
                        # key events so the shell can kill/unblock the
                        # running command (Ctrl-Z only unblocks; the
                        # command keeps running in the background).
                        sig = b"\x03" if line == "@@INT" else b"\x1a"
                        try:
                            if link.sock is not None:
                                link.send_blob(sig)
                                log("sent %s" % line)
                            else:
                                pending_sig = sig
                                log("%s queued (link down)" % line)
                        except (IOError, OSError) as e:
                            log("%s send failed: %s" % (line, e))
                            link.close()
                            write_status(pid, "down", cmd_count)
                        continue
                    if line not in queue and (
                            cur is None or line != cur["reqid"]):
                        queue.append(line)
            else:
                # No writers left: reopen to avoid a select/read spin.
                fifo = reopen_fifo(fifo)

        if want_sock is not None and want_sock in r:
            try:
                d = want_sock.recv(65536)
            except BlockingIOError:
                d = None
            except OSError:
                d = b""
            if d is None:
                pass
            elif d == b"":
                log("link lost (FIN)")
                link.close()
                write_status(pid, "down", cmd_count)
            else:
                link.buf += d
                last_rx = time.time()

        if link.sock is None:
            if link_up:
                link_up = False
                write_status(pid, "down", cmd_count)
            # Keep the single client attached even when idle, so the
            # link is already up when work (or @@INT) arrives. Retry
            # with backoff; requests have their own deadlines.
            if cur is not None and time.time() - cur["start"] > cur["timeout"]:
                finish_request(cur, "TIMEOUT link down too long")
                log("batch %s TIMEOUT (link down)" % cur["reqid"])
                cur = None
                continue
            # Non-blocking-ish: try one attach per loop iteration.
            s, fin = link._connect_once()
            if s is not None:
                if pending_sig is not None:
                    # Deliver Ctrl-C BEFORE the handshake: a wedged shell
                    # never prints a prompt, so handshaking first would
                    # deadlock. The guest consumes ETX in its input read.
                    try:
                        s.settimeout(SEND_TIMEOUT)
                        s.sendall(pending_sig)
                        log("sent signal pre-handshake")
                    except OSError as e:
                        log("pre-handshake ETX failed: %s" % e)
                        try:
                            s.close()
                        except OSError:
                            pass
                        s = None
                    pending_sig = None
            if s is not None and link._handshake(s):
                link.sock = s
                link.sock.setblocking(False)
                link.buf = b""
                link.consec_fins = 0
                link.retry_delay = RECONNECT_BASE_DELAY
                link_up = True
                log("link up")
                write_status(pid, "up", cmd_count)
                if pending_sig is not None:
                    sig = pending_sig
                    pending_sig = None
                    try:
                        link.send_blob(sig)
                        log("sent queued signal")
                    except (IOError, OSError) as e:
                        log("queued signal send failed: %s" % e)
                        link.close()
                        write_status(pid, "down", cmd_count)
                        continue
                if cur is not None:
                    # Resync: fresh prompt then (re)send current command.
                    cur["phase"] = "idle"
                    link.buf = b""
                    try:
                        start_next_cmd(cur)
                    except (IOError, OSError) as e:
                        log("resend failed after link up: %s" % e)
                        link.close()
                        write_status(pid, "down", cmd_count)
            else:
                if s is not None:
                    try:
                        s.close()
                    except OSError:
                        pass
                if fin:
                    link.consec_fins += 1
                    if (link.consec_fins >= REPLUG_AFTER_FINS
                            and time.time() - link.last_replug
                            > REPLUG_MIN_INTERVAL):
                        log("resetting pipe server via "
                            "changeuartmode2")
                        link.last_replug = time.time()
                        replug_pipe()
                        link.consec_fins = 0
                time.sleep(link.retry_delay)
                link.retry_delay = min(link.retry_delay * 2.0,
                                       RECONNECT_MAX_DELAY)
            continue

        if not link_up:
            link_up = True
            write_status(pid, "up", cmd_count)

        if cur is not None:
            if time.time() - last_rx > WEDGE_SILENCE:
                log("RX silence %.0fs with batch %s in flight; "
                    "replugging pipe server" % (time.time() - last_rx,
                                                cur["reqid"]))
                link.close()
                write_status(pid, "down", cmd_count)
                link.last_replug = time.time()
                replug_pipe()
                link.buf = b""
                last_rx = time.time()
                continue
            if time.time() - cur["start"] > cur["timeout"]:
                try:
                    link.send_blob(b"\n")
                except (IOError, OSError):
                    pass
                finish_request(cur, "TIMEOUT deadline exceeded "
                               "(no prompt; shell may be wedged -- "
                               "check KDB 'W' for serial-console in join)")
                log("batch %s TIMEOUT" % cur["reqid"])
                cur = None
                link.buf = b""
                continue
            try:
                if cur["phase"] == "idle":
                    start_next_cmd(cur)
                elif process_stream(cur):
                    cur["idx"] += 1
                    cmd_count += 1
                    last_rx = time.time()
                    write_status(pid, "up", cmd_count)
                    if cur["idx"] >= len(cur["cmds"]):
                        finish_request(cur, "OK %d" % len(cur["cmds"]))
                        log("batch %s done" % cur["reqid"])
                        cur = None
                    else:
                        cur["phase"] = "idle"
            except (IOError, OSError) as e:
                log("link lost during batch %s: %s" % (cur["reqid"], e))
                link.close()
                write_status(pid, "down", cmd_count)


if __name__ == "__main__":
    main()

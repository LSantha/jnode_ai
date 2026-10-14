#!/usr/bin/env python3
"""Persistent single-client proxy for the JNode serial agent console.

Holds ONE connection to the instance's agent socket for the whole session
and multiplexes agent command batches through it. The socket comes from
jnode_paths.py, so several VMs (VirtualBox or QEMU) can each have their own
mux: /tmp/jnode.serial2 for the legacy "default" instance, or
/tmp/jnode/<id>/agent.sock for any other id.

Why: one connection per command eventually wedges the VirtualBox pipe
server ("NamedPipe0: only single connection supported" in VBox.log,
immediate FIN/EOF on every new attach while the guest stays healthy).
Holding a single client avoids the stuck state entirely (same pattern as
the kdb_mux.py on UART1).

Protocol (files under the instance's mux dir, US-ASCII):
   REQ dir  <mux>/req/<reqid>.json     {cmds:[...], timeout:secs}
   wakeup   FIFO <mux>/cmd.fifo        client writes "<reqid>\\n"
   RESP dir <mux>/resp/<reqid>.out     mux appends command output
            <mux>/resp/<reqid>.done    "OK <n>" | "TIMEOUT ..." | "ERROR ..."
   status   <mux>/mux.status           "pid link cmds=<n> ..."
   log      <mux>/mux.log              connects, replugs, errors

Framing on the wire: each command is wrapped as
    echo __JSM_B_<id>__
    <command>
    echo __JSM_E_<id>__
and the mux captures everything between the two marker lines, then waits
for the [JNODE_AGENT_READY] prompt before running the next command.
The guest agent console does not echo input, so the capture is exactly
the command's output. No silence timeout: a command completes on
END-marker + prompt, however long that takes (up to the request timeout).

Self-healing: on FIN/RST/EPIPE the mux reconnects with backoff. For a
VirtualBox instance whose new attaches are FINed immediately (the stuck
signature), it resets the pipe server at runtime (no power cycle):
    vboxmanage controlvm <vm> changeuartmode2 disconnected
    vboxmanage controlvm <vm> changeuartmode2 server <agent sock>
QEMU needs no such trick -- its unix socket is recreated by QEMU itself,
so the mux only reconnects (the hypervisor comes from the instance
registry written by start_qemu.sh / jnode_vbox.py).

Usage: serial_cmd.py starts this automatically; manual start:
    nohup python3 serial_mux.py [--instance ID] >/dev/null 2>&1 &
"""

import atexit
import json
import os
import select
import signal
import socket
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jnode_paths as jp  # noqa: E402

# Filled in by configure(); module-level so the helpers below stay simple.
PATHS = None
SOCK = "/tmp/jnode.serial2"
FIFO = "/tmp/jnode_serial_cmd.fifo"
REQDIR = "/tmp/jnode_serial_req"
RESPDIR = "/tmp/jnode_serial_resp"
LOG = "/tmp/jnode_serial_mux.log"
PIDFILE = "/tmp/jnode_serial_mux.pid"
STATUS = "/tmp/jnode_serial_mux.status"
HYP = "unknown"
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
# BEGIN-marker echo within seconds, so total RX silence means the UART
# itself wedged (observed with no log line at all). Replug is harmless to
# a healthy guest; the in-flight command is resent afterwards, bounded by
# the resend budget, rate limit, and client-liveness checks
# (MAX_RESENDS_PER_CMD et al).
WEDGE_SILENCE = 180.0
# Replug at most this often (seconds) to avoid hammering the hypervisor.
REPLUG_MIN_INTERVAL = 15.0
DEFAULT_REQ_TIMEOUT = 1200.0
# Bound every resend path. The wait_begin prompt-resend and the reconnect
# resend were both unbounded; a guest that keeps printing prompts without
# the marker echo (error storm, wedged shell) re-triggered them ~10x/s
# until the request timeout, and each resend executed a real command on
# the guest (proclet spawn + logged exception), OOM'ing its heap.
MAX_RESENDS_PER_CMD = 3
# Minimum seconds between resends of the same command.
MIN_RESEND_INTERVAL = 2.0
# Capture cap per command: an error flood can drown the END marker so the
# command never completes; cut it off and ETX the guest instead.
MAX_CAPTURE_BYTES = 512 * 1024


def configure(instance_id):
    """Load this instance's socket/IPC paths and hypervisor identity."""
    global PATHS, SOCK, FIFO, REQDIR, RESPDIR, LOG, PIDFILE, STATUS, HYP, VM
    PATHS = jp.paths_for(instance_id)
    SOCK = PATHS.agent_sock
    FIFO = PATHS.fifo
    REQDIR = PATHS.req_dir
    RESPDIR = PATHS.resp_dir
    LOG = PATHS.mux_log
    PIDFILE = PATHS.mux_pid
    STATUS = PATHS.mux_status
    entry = jp.read_entry(instance_id) or {}
    HYP = entry.get("hypervisor") or "unknown"
    VM = entry.get("vm") or "JNode"
    return PATHS


def replug_allowed():
    """VirtualBox needs (and tolerates) an UART replug; QEMU does not."""
    if HYP == "qemu":
        return False
    if HYP == "vbox":
        return True
    # Unknown (hand-started VM): only touch VirtualBox when the very VM
    # name we would control is actually running, so a QEMU-only box never
    # gets a stray vboxmanage against some unrelated VM.
    return jp._vbox_running(VM)


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
    """Reset the VirtualBox UART pipe server at runtime (VM keeps running).

    No-op on QEMU (its unix socket does not wedge) and on hosts where the
    VM named `VM` is not actually a running VirtualBox machine.
    """
    if not replug_allowed():
        log("replug skipped (hypervisor=%s vm=%s)" % (HYP, VM))
        return False
    # resolve the binary once: installs differ only in case, and a miss
    # here would make the VirtualBox wedge recovery silently dead code
    exe = jp._vbox_binary()
    if not exe:
        log("replug skipped: VBoxManage/vboxmanage not on PATH")
        return False
    try:
        subprocess.run([exe, "controlvm", VM,
                        "changeuartmode2", "disconnected"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       timeout=30)
        time.sleep(1.0)
        subprocess.run([exe, "controlvm", VM,
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


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] in ("-h", "--help"):
        print("Usage: python3 serial_mux.py [--instance ID]")
        print("       (normally started automatically by serial_cmd.py)")
        sys.exit(0)
    instance = None
    if "--instance" in argv:
        i = argv.index("--instance")
        if i + 1 >= len(argv):
            print("serial_mux.py: --instance needs an id", file=sys.stderr)
            sys.exit(2)
        instance = argv[i + 1]
        del argv[i:i + 2]
    if argv:
        print("serial_mux.py: unknown arguments: %s" % " ".join(argv),
              file=sys.stderr)
        sys.exit(2)
    try:
        paths = configure(jp.resolve_instance(instance))
    except jp.InstanceError as e:
        print("serial_mux.py: %s" % e, file=sys.stderr)
        sys.exit(2)

    for d in paths.ipc_dirs:
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
    # single-instance guard (same invariant as kdb_mux.acquire_pidfile,
    # plus flock): two concurrent serial_cmd starts must not leave two
    # muxes on one FIFO + guest socket
    if not jp.acquire_pid_slot(PIDFILE, "serial_mux.py"):
        try:
            with open(PIDFILE) as f:
                owner = f.read().strip()
        except OSError:
            owner = "?"
        print("a serial mux is already running (pid %s, instance %s)"
              % (owner, paths.id))
        sys.exit(0)

    def _cleanup_pidfile():
        # drop our own claim; a SIGTERM'd mux must not leave a stale
        # mux.pid behind (it would make ensure_mux() skip the start and
        # hang every serial_cmd for the full deadline, or make a
        # launcher SIGTERM whatever pid recycled the slot)
        try:
            with open(PIDFILE) as f:
                if int(f.read().strip()) == os.getpid():
                    os.unlink(PIDFILE)
        except (OSError, ValueError):
            pass

    atexit.register(_cleanup_pidfile)

    def _sigterm(_sig, _frame):
        # Python does not unwind on SIGTERM without this, so atexit
        # would never run; stop_mux() is only the backstop
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, _sigterm)

    fifo = os.open(FIFO, os.O_RDONLY | os.O_NONBLOCK)
    log("started pid=%d instance=%s sock=%s hyp=%s vm=%s"
        % (pid, paths.id, SOCK, HYP, VM))

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
                    "pid": data.get("pid"),
                    "resends": 0, "sends_this_cmd": 0,
                    "last_resend": 0.0, "cap_total": 0,
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

    class Abandon(Exception):
        """Drop the current request (dead client, resend budget, flood)."""

    def client_alive(cpid):
        if cpid is None:
            return True
        try:
            return os.path.exists("/proc/%d" % int(cpid))
        except (TypeError, ValueError):
            return True

    def abandon_cur(req, reason):
        nonlocal cur
        try:
            link.send_blob(b"\x03")
        except (IOError, OSError):
            pass
        log("batch %s abandoned: %s" % (req["reqid"], reason))
        finish_request(req, "ERROR " + str(reason))
        if cur is req:
            cur = None
        link.buf = b""

    def guarded_send(req):
        """Single funnel for every (re)send of the current command.

        First send of a command passes through; any resend must keep the
        submitting client alive, respect the rate limit, and stay within
        the resend budget, or the whole request is abandoned.
        """
        if not client_alive(req.get("pid")):
            raise Abandon("client %s died" % req.get("pid"))
        if req["sends_this_cmd"] > 0:
            now = time.time()
            if now - req["last_resend"] < MIN_RESEND_INTERVAL:
                return
            req["resends"] += 1
            if req["resends"] > MAX_RESENDS_PER_CMD:
                raise Abandon("abandoned after %d resends"
                              % MAX_RESENDS_PER_CMD)
            req["last_resend"] = now
            log("resend %s cmd %d (resend #%d)"
                % (req["reqid"], req["idx"], req["resends"]))
        req["sends_this_cmd"] += 1
        start_next_cmd(req)

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
                # re-send markers rather than hang (rate- and
                # budget-capped: this was the storm engine).
                if PROMPT in link.buf:
                    link.buf = b""
                    guarded_send(req)
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
                    req["cap_total"] += len(link.buf) - keep
                    if req["cap_total"] > MAX_CAPTURE_BYTES:
                        raise Abandon("output overflow (> %d bytes)"
                                      % MAX_CAPTURE_BYTES)
                    emit(req, link.buf[:-keep].replace(b"\r", b""))
                    link.buf = link.buf[-keep:]
                return False
            req["cap_total"] += j
            if req["cap_total"] > MAX_CAPTURE_BYTES:
                raise Abandon("output overflow (> %d bytes)"
                              % MAX_CAPTURE_BYTES)
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
                        guarded_send(cur)
                    except Abandon as e:
                        abandon_cur(cur, e)
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
                        log("repeated immediate FINs; %s"
                            % ("resetting pipe server via "
                               "changeuartmode2" if replug_allowed()
                               else "reconnect only (hypervisor=%s)" % HYP))
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
                action = ("replugging pipe server" if replug_allowed()
                          else "reconnecting (no UART replug on %s)" % HYP)
                log("RX silence %.0fs with batch %s in flight; %s"
                    % (time.time() - last_rx, cur["reqid"], action))
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
                    guarded_send(cur)
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
                        cur["sends_this_cmd"] = 0
                        cur["cap_total"] = 0
            except Abandon as e:
                abandon_cur(cur, e)
            except (IOError, OSError) as e:
                log("link lost during batch %s: %s" % (cur["reqid"], e))
                link.close()
                write_status(pid, "down", cmd_count)


if __name__ == "__main__":
    main()

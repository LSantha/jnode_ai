#!/usr/bin/env python3
"""Replay tests for the serial_mux request loop (#749).

Run: python3 -m unittest discover -s .opencode/skills/jnode-serial/tests

Boots the real serial_mux.py main loop in a subprocess against a fake
guest shell on a unix socket and replays the incident modes:

  normal      -- multi-command batch still answers
  dead client -- SIGKILL-equivalent client pid: abandon, no replay storm
  resend cap  -- live client, guest never returns a marker: bounded replay
  flood       -- output past MAX_CAPTURE_BYTES: abandon + ETX

The mux never re-sends a command more than MAX_RESENDS_PER_CMD times, and
always sends ETX when it abandons, so a stuck guest command can be
cleared by the next client instead of running until the request timeout.
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, os.pardir, "scripts")
MUX = os.path.join(SCRIPTS, "serial_mux.py")
sys.path.insert(0, SCRIPTS)

import serial_mux  # noqa: E402

DRIVER = '''
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("serial_mux", %r)
mux = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mux)
(sock, fifo, reqdir, respdir, log, pidfile, status,
 min_interval, max_capture) = sys.argv[1:10]
mux.SOCK = sock
mux.FIFO = fifo
mux.REQDIR = reqdir
mux.RESPDIR = respdir
mux.LOG = log
mux.PIDFILE = pidfile
mux.STATUS = status
mux.MIN_RESEND_INTERVAL = float(min_interval)
mux.MAX_CAPTURE_BYTES = int(max_capture)
mux.main()
''' % (MUX,)


class FakeGuest(object):
    """Minimal stand-in for the JNode agent console on the UART pipe.

    mode "normal": echoes both markers and a prompt (healthy guest).
    mode "promptspam": answers every input with a bare prompt and never
        echoes a marker -- the lost-echo signature that used to make the
        mux re-send the command forever.
    mode "flood": answers the BEGIN marker with an endless output burst
        that drowns the END marker.
    """

    def __init__(self, sockpath, mode):
        self.mode = mode
        self.received = b""
        self.etx = threading.Event()
        self.srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.srv.bind(sockpath)
        self.srv.listen(1)
        self.stop = False
        self.thread = threading.Thread(target=self._serve)
        self.thread.daemon = True
        self.thread.start()

    def _serve(self):
        conn, _ = self.srv.accept()
        buf = b""
        while not self.stop:
            try:
                conn.settimeout(0.2)
                d = conn.recv(65536)
            except socket.timeout:
                continue
            except OSError:
                break
            if not d:
                break
            self.received += d
            if b"\x03" in d:
                self.etx.set()
            buf += d
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                self._answer(conn, line.strip())

    def _answer(self, conn, line):
        if line.startswith(b"echo __JSM_B_"):
            if self.mode == "normal":
                conn.sendall(line + b"\n")
            elif self.mode == "promptspam":
                conn.sendall(b"[JNODE_AGENT_READY]\n")
            elif self.mode == "flood":
                conn.sendall(b"[JNODE_AGENT_READY]\n")
                blob = (b"ERROR cannot open '/jnode/tmp/X'\n" * 4000)
                try:
                    conn.sendall(blob)
                except OSError:
                    pass
        elif line.startswith(b"echo __JSM_E_") and self.mode == "normal":
            conn.sendall(line + b"\n[JNODE_AGENT_READY]\n")
        elif not line:
            conn.sendall(b"[JNODE_AGENT_READY]\n")

    def close(self):
        self.stop = True
        try:
            self.srv.close()
        except OSError:
            pass


class MuxCase(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="jsm-")
        self.paths = {
            "sock": os.path.join(self.dir, "sock"),
            "fifo": os.path.join(self.dir, "fifo"),
            "req": os.path.join(self.dir, "req"),
            "resp": os.path.join(self.dir, "resp"),
            "log": os.path.join(self.dir, "mux.log"),
            "pid": os.path.join(self.dir, "mux.pid"),
            "status": os.path.join(self.dir, "mux.status"),
        }
        os.makedirs(self.paths["req"])
        os.makedirs(self.paths["resp"])
        self.guest = None
        self.proc = None

    def tearDown(self):
        if self.guest is not None:
            self.guest.close()
        if self.proc is not None:
            self.proc.kill()
            self.proc.wait()

    def start_mux(self, mode, min_interval=0.1, max_capture=524288):
        self.guest = FakeGuest(self.paths["sock"], mode)
        driver = os.path.join(self.dir, "driver.py")
        with open(driver, "w") as f:
            f.write(DRIVER)
        args = [sys.executable, driver]
        args += [self.paths["sock"], self.paths["fifo"],
                 self.paths["req"], self.paths["resp"], self.paths["log"],
                 self.paths["pid"], self.paths["status"],
                 str(min_interval), str(max_capture)]
        self.proc = subprocess.Popen(args, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT)
        deadline = time.time() + 10.0
        while time.time() < deadline:
            if os.path.exists(self.paths["pid"]):
                return
            time.sleep(0.05)
        self.fail("mux did not start: %s"
                  % self.proc.stdout.read().decode("utf-8", "replace"))

    def submit(self, cmds, pid, timeout=60.0):
        reqid = "t%08x" % int(time.time() * 1000)
        with open(os.path.join(self.paths["req"], reqid + ".json"),
                  "w") as f:
            json.dump({"cmds": cmds, "timeout": timeout, "pid": pid}, f)
        fd = os.open(self.paths["fifo"], os.O_WRONLY | os.O_NONBLOCK)
        try:
            os.write(fd, (reqid + "\n").encode("ascii"))
        finally:
            os.close(fd)
        done = os.path.join(self.paths["resp"], reqid + ".done")
        deadline = time.time() + 40.0
        while time.time() < deadline:
            if os.path.exists(done):
                with open(done) as f:
                    return f.read().strip()
            time.sleep(0.1)
        return "NO-DONE"

    def mux_log(self):
        try:
            with open(self.paths["log"]) as f:
                return f.read()
        except OSError:
            return ""

    def test_normal_batch_still_works(self):
        self.start_mux("normal")
        status = self.submit(["date", "date"], os.getpid())
        self.assertEqual("OK 2", status)

    def test_dead_client_abandons_without_resend_storm(self):
        self.start_mux("promptspam", min_interval=0.2)
        dead = 1 << 22
        status = self.submit(["date"], dead)
        self.assertEqual("ERROR client %d died" % dead, status)
        self.assertTrue(self.guest.etx.wait(5.0), "no ETX after abandon")
        log = self.mux_log()
        self.assertEqual(1, log.count("sent ETX on abandon"),
                         "must abandon once, got:\n" + log)
        self.assertNotIn("resend limit exceeded", log)

    def test_resend_cap_bounds_replay(self):
        cap = serial_mux.MAX_RESENDS_PER_CMD
        self.start_mux("promptspam", min_interval=0.2)
        status = self.submit(["date"], os.getpid())
        self.assertEqual("ERROR resend limit exceeded (%d)" % cap, status)
        self.assertTrue(self.guest.etx.wait(5.0), "no ETX after abandon")
        # initial send + MAX_RESENDS_PER_CMD replays, no more.
        sent = self.guest.received.count(b"echo __JSM_B_")
        self.assertEqual(cap + 1, sent,
                         "command re-sent %d times" % sent)

    def test_output_flood_abandons_with_etx(self):
        self.start_mux("flood", min_interval=0.2, max_capture=65536)
        status = self.submit(["cat /jnode/tmp/big.jar"], os.getpid())
        self.assertEqual("ERROR output overflow (> 65536 bytes)", status)
        self.assertTrue(self.guest.etx.wait(5.0), "no ETX after overflow")

    def test_link_survives_an_abandoned_request(self):
        self.start_mux("flood", min_interval=0.2, max_capture=65536)
        self.assertEqual("ERROR output overflow (> 65536 bytes)",
                         self.submit(["cat /jnode/tmp/big.jar"],
                                     os.getpid()))
        self.guest.mode = "normal"
        self.assertEqual("OK 1", self.submit(["date"], os.getpid()))


if __name__ == "__main__":
    unittest.main()
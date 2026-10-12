"""KDB mux single-instance guard.

kdb_mux.py owns a per-instance pid file so two `--mux` invocations
cannot leave two daemons on the same FIFO/UART1 socket, and
`jnode_kdb_cmd.py --stop` tears the holder down (the launchers' teardown
only ever stopped the serial mux).

No VM involved: a fake KDB guest accepts the mux's connection and holds
it open, exactly what kdb_mux needs to stay resident.

Run:  python3 -m pytest .opencode/skills/jnode-serial/tests/test_kdb_e2e.py -q
"""

import os
import socket
import subprocess
import sys
import threading
import time

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, "..", "scripts")
KDB_CMD = os.path.join(SCRIPTS, "jnode_kdb_cmd.py")
PATHS_PY = os.path.join(SCRIPTS, "jnode_paths.py")


class FakeKdbGuest(object):
    """Accept one KDB connection and keep it open (silent)."""

    def __init__(self, state_dir, instance_id):
        self.id = instance_id
        self.state_dir = str(state_dir)
        self.sock = os.path.join(self.state_dir, instance_id, "kdb.sock")
        os.makedirs(os.path.dirname(self.sock), exist_ok=True)
        self.srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.srv.bind(self.sock)
        self.srv.listen(1)
        self.conn = None
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    @property
    def env(self):
        env = dict(os.environ)
        env["JNODE_STATE_DIR"] = self.state_dir
        env.pop("JNODE_INSTANCE", None)
        return env

    def _serve(self):
        try:
            self.conn, _ = self.srv.accept()
            while self.conn.recv(4096):
                pass
        except OSError:
            pass

    def close(self):
        try:
            self.srv.close()
        except OSError:
            pass
        if self.conn:
            try:
                self.conn.close()
            except OSError:
                pass


def _run(state_dir, *args, timeout=30):
    env = dict(os.environ)
    env["JNODE_STATE_DIR"] = str(state_dir)
    env.pop("JNODE_INSTANCE", None)
    return subprocess.run(
        [sys.executable, KDB_CMD] + list(args),
        capture_output=True, text=True, timeout=timeout, env=env)


def _field(state_dir, name):
    env = dict(os.environ)
    env["JNODE_STATE_DIR"] = str(state_dir)
    env.pop("JNODE_INSTANCE", None)
    r = subprocess.run(
        [sys.executable, PATHS_PY, "--field", name, "--instance", "k1"],
        capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    return r.stdout.strip()


def _pid_alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _wait_for(cond, secs=10):
    deadline = time.time() + secs
    while time.time() < deadline:
        if cond():
            return True
        time.sleep(0.1)
    return False


@pytest.fixture
def kdb_world(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    guest = FakeKdbGuest(state, "k1")
    yield state, guest
    # stop whatever mux is left, then the guest
    subprocess.run([sys.executable, KDB_CMD, "--instance", "k1", "--stop"],
                   env=guest.env, capture_output=True, timeout=30)
    guest.close()


def test_kdb_mux_single_instance_guard(kdb_world):
    state, guest = kdb_world

    r1 = _run(state, "--instance", "k1", "--mux")
    assert r1.returncode == 0, r1.stdout + r1.stderr
    assert "kdb mux started" in r1.stdout

    pidfile = _field(state, "kdb_mux_pid")
    assert pidfile, "kdb_mux_pid field must be resolvable"
    assert _wait_for(lambda: os.path.exists(pidfile)), \
        "kdb mux never wrote its pid file"
    pid1 = int(open(pidfile).read().strip())
    assert _pid_alive(pid1)

    # a second --mux must not spawn a second daemon
    r2 = _run(state, "--instance", "k1", "--mux")
    assert r2.returncode == 0, r2.stdout + r2.stderr
    assert "already running" in r2.stdout
    assert int(open(pidfile).read().strip()) == pid1

    # --stop tears it down and is idempotent
    r3 = _run(state, "--instance", "k1", "--stop")
    assert r3.returncode == 0, r3.stdout + r3.stderr
    assert "kdb mux stopped" in r3.stdout
    assert _wait_for(lambda: not _pid_alive(pid1)), "kdb mux pid survived --stop"
    assert not os.path.exists(pidfile)

    r4 = _run(state, "--instance", "k1", "--stop")
    assert r4.returncode == 0
    assert "not running" in r4.stdout


def test_kdb_mux_pidfile_not_stale_claim(kdb_world):
    """A dead pid in the file must not block a fresh --mux."""
    state, guest = kdb_world
    # craft a stale pid file owned by nobody, at the instance's real path
    pidfile = _field(state, "kdb_mux_pid")
    os.makedirs(os.path.dirname(pidfile), exist_ok=True)
    # spawn-and-reap a child so the pid is guaranteed dead on any host
    child = subprocess.Popen([sys.executable, "-c", "pass"])
    child.wait()
    with open(pidfile, "w") as f:
        f.write("%d\n" % child.pid)
    r = _run(state, "--instance", "k1", "--mux")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "kdb mux started" in r.stdout
    # wait for the child to *replace* the stale claim
    assert _wait_for(
        lambda: open(pidfile).read().strip() not in ("", str(child.pid)))
    pid = int(open(pidfile).read().strip())
    assert pid != child.pid
    assert _pid_alive(pid)


def test_kdb_bare_instance_prints_usage(kdb_world):
    """A bare `--instance X` must print usage, not exit 0 silently."""
    state, guest = kdb_world
    r = _run(state, "--instance", "k1")
    assert r.returncode == 2
    assert "Usage" in r.stdout


def test_kdb_sigterm_removes_pidfile(kdb_world):
    """SIGTERM must unwind through main()'s finally and drop the pid
    file (stop_mux's unlink is only the backstop)."""
    state, guest = kdb_world
    r = _run(state, "--instance", "k1", "--mux")
    assert r.returncode == 0, r.stdout + r.stderr
    pidfile = _field(state, "kdb_mux_pid")
    assert _wait_for(lambda: os.path.exists(pidfile))
    pid = int(open(pidfile).read().strip())
    os.kill(pid, 15)
    assert _wait_for(lambda: not os.path.exists(pidfile)), \
        "pid file survived SIGTERM"
    assert _wait_for(lambda: not _pid_alive(pid))

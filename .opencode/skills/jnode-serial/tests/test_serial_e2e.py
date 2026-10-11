"""End-to-end tests: serial_cmd.py + serial_mux.py against fake_guest.py.

No VM involved: fake_guest.py speaks the SerialConsolePlugin prompt
protocol on a unix socket, exactly what the mux expects.

Run:  python3 -m pytest .opencode/skills/jnode-serial/tests/ -q
"""

import os
import subprocess
import sys
import time

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, "..", "scripts")
FAKE_GUEST = os.path.join(HERE, "fake_guest.py")
SERIAL_CMD = os.path.join(SCRIPTS, "serial_cmd.py")
PATHS_PY = os.path.join(SCRIPTS, "jnode_paths.py")

sys.path.insert(0, SCRIPTS)
import jnode_paths as jp  # noqa: E402


class Guest(object):
    def __init__(self, state_dir, instance_id):
        self.id = instance_id
        self.state_dir = str(state_dir)
        self.sock = os.path.join(self.state_dir, instance_id, "agent.sock")
        self.proc = subprocess.Popen(
            [sys.executable, FAKE_GUEST, self.sock],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.time() + 10
        while not os.path.exists(self.sock):
            if time.time() > deadline:
                raise RuntimeError("fake guest socket never appeared")
            time.sleep(0.05)
        # Register so autodetect/ambiguity logic sees a live instance.
        subprocess.run(
            [sys.executable, PATHS_PY, "--register", "--instance", instance_id,
             "--hypervisor", "qemu", "--vm-pid", str(self.proc.pid),
             "--state", "ready"],
            check=True, env=self.env, capture_output=True)

    @property
    def env(self):
        env = dict(os.environ)
        env["JNODE_STATE_DIR"] = self.state_dir
        env.pop("JNODE_INSTANCE", None)
        return env


def _env(state_dir):
    env = dict(os.environ)
    env["JNODE_STATE_DIR"] = str(state_dir)
    env.pop("JNODE_INSTANCE", None)
    return env


def _run(state_dir, *args, timeout=90, env_extra=None):
    env = _env(state_dir)
    if env_extra:
        env.update(env_extra)
    return subprocess.run(
        [sys.executable, SERIAL_CMD] + list(args),
        capture_output=True, text=True, timeout=timeout, env=env)


def _guest(state_dir, instance_id):
    guest = Guest(state_dir, instance_id)
    guest.state_dir = str(state_dir)
    return guest


@pytest.fixture
def world(tmp_path):
    """Isolated state dir + cleanup of muxes/guests it creates."""
    state = tmp_path / "state"
    state.mkdir()
    procs = []

    def make(instance_id):
        g = _guest(state, instance_id)
        procs.append(g.proc)
        return g

    yield state, make
    # stop every mux started in this state dir, then the guests
    for entry in os.listdir(state / "instances") if (state / "instances").exists() else []:
        iid = entry[:-5]
        subprocess.run([sys.executable, SERIAL_CMD, "--instance", iid,
                        "--stop"], env=_env(state), capture_output=True,
                       timeout=60)
    for p in procs:
        p.terminate()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()


# --------------------------------------------------------------------------
# Single instance
# --------------------------------------------------------------------------

def test_mux_batch_roundtrip(world):
    state, make = world
    make("t1")
    r = _run(state, "--instance", "t1", "echo hello", "date")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "hello" in r.stdout
    assert "OUT:date" in r.stdout
    assert "[batch OK 2]" in r.stdout


def test_status_reports_instance_then_stop(world):
    state, make = world
    make("t2")
    r = _run(state, "--instance", "t2", "echo hi")
    assert r.returncode == 0, r.stdout + r.stderr
    r = _run(state, "--instance", "t2", "--status")
    assert r.returncode == 0
    assert "instance: t2" in r.stdout
    assert "mux: pid" in r.stdout
    assert "link=up" in r.stdout
    # the mux log records the hypervisor so replug logic is qemu-safe
    log = os.path.join(str(state), "t2", "mux", "mux.log")
    with open(log) as f:
        body = f.read()
    assert "instance=t2" in body and "hyp=qemu" in body
    r = _run(state, "--instance", "t2", "--stop")
    assert r.returncode == 0 and "mux stopped" in r.stdout


def test_wait_option_succeeds_when_shell_up(world):
    state, make = world
    make("t3")
    r = _run(state, "--instance", "t3", "--wait", "20", "echo ready")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "ready" in r.stdout


def test_oneshot_without_mux(world):
    state, make = world
    make("t4")
    # make sure no mux holds the socket
    _run(state, "--instance", "t4", "--stop")
    r = _run(state, "--instance", "t4", "--oneshot", "date")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "OUT:date" in r.stdout
    # oneshot must not have started a mux
    r = _run(state, "--instance", "t4", "--status")
    assert "mux: not running" in r.stdout


def test_env_var_selects_instance(world):
    state, make = world
    make("t5")
    r = _run(state, "echo via-env", env_extra={"JNODE_INSTANCE": "t5"})
    assert r.returncode == 0, r.stdout + r.stderr
    assert "via-env" in r.stdout


def test_missing_instance_socket_is_a_clean_error(world):
    state, make = world
    make("t6")
    r = _run(state, "--instance", "no-such-instance", "date")
    # mux starts but can never connect: batch must fail, not hang forever
    assert r.returncode != 0
    assert "ERROR" in r.stdout or "TIMEOUT" in r.stdout or "not ready" in r.stdout


# --------------------------------------------------------------------------
# Several instances side by side
# --------------------------------------------------------------------------

def test_two_instances_run_in_parallel(world):
    import concurrent.futures
    state, make = world
    make("par-a")
    make("par-b")

    def call(instance, word):
        return _run(state, "--instance", instance, "echo " + word, "date")

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        fa = ex.submit(call, "par-a", "ALPHA")
        fb = ex.submit(call, "par-b", "BRAVO")
        ra, rb = fa.result(timeout=120), fb.result(timeout=120)

    assert ra.returncode == 0, ra.stdout + ra.stderr
    assert rb.returncode == 0, rb.stdout + rb.stderr
    assert "ALPHA" in ra.stdout and "BRAVO" not in ra.stdout
    assert "BRAVO" in rb.stdout and "ALPHA" not in rb.stdout
    # two independent muxes, two independent sockets
    assert os.path.exists(os.path.join(str(state), "par-a", "agent.sock"))
    assert os.path.exists(os.path.join(str(state), "par-b", "agent.sock"))
    pids = set()
    for iid in ("par-a", "par-b"):
        with open(os.path.join(str(state), iid, "mux", "mux.pid")) as f:
            pids.add(f.read().strip())
    assert len(pids) == 2


def test_ambiguous_instances_require_explicit_choice(world):
    state, make = world
    make("amb-1")
    make("amb-2")
    r = _run(state, "date")          # no --instance, no env
    assert r.returncode == 2
    assert "several live JNode instances" in r.stdout
    assert "amb-1" in r.stdout and "amb-2" in r.stdout
    # ...but an explicit choice works
    r = _run(state, "--instance", "amb-1", "echo picked")
    assert r.returncode == 0 and "picked" in r.stdout


# --------------------------------------------------------------------------
# Legacy default instance (fixed paths) still resolves
# --------------------------------------------------------------------------

def test_default_instance_uses_legacy_paths(world):
    state, _ = world
    r = _run(state, "--instance", "default", "--status")
    # no mux: exits 1, but reports the legacy wiring
    assert "instance: default (legacy paths)" in r.stdout
    assert "agent:    /tmp/jnode.serial2" in r.stdout
    assert "boot log: /tmp/qemu_serial.log" in r.stdout

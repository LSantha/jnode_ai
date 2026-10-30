"""End-to-end tests: serial_cmd.py + serial_mux.py against fake_guest.py.

No VM involved: fake_guest.py speaks the SerialConsolePlugin prompt
protocol on a unix socket, exactly what the mux expects.

Run:  python3 -m pytest .opencode/skills/jnode-serial/tests/ -q
"""

import json
import os
import socket
import subprocess
import sys
import threading
import time

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, "..", "scripts")
FAKE_GUEST = os.path.join(HERE, "fake_guest.py")
SERIAL_CMD = os.path.join(SCRIPTS, "serial_cmd.py")
PATHS_PY = os.path.join(SCRIPTS, "jnode_paths.py")

sys.path.insert(0, SCRIPTS)


class Guest(object):
    def __init__(self, state_dir, instance_id):
        self.id = instance_id
        self.state_dir = str(state_dir)
        self.sock = os.path.join(self.state_dir, instance_id, "agent.sock")
        # run under a qemu-system argv[1] so entry_live()'s identity
        # check (pid_owns(pid, "qemu-system"), exact basename match)
        # sees this as a live VM
        stub = os.path.join(self.state_dir, "qemu-system")
        if not os.path.exists(stub):
            os.symlink(FAKE_GUEST, stub)
        self.proc = subprocess.Popen(
            [sys.executable, stub, self.sock],
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


def test_oneshot_survives_stale_prompt(world):
    """The guest may emit the prompt twice (spontaneous console-open
    prompt, then a reply to the probe newline). The leftover prompt must
    not be mistaken for the end of the command."""
    state, make = world
    guest = make("t4c")
    _run(state, "--instance", "t4c", "--stop")

    # chatty guest: spontaneous prompt + delayed second prompt on newline
    os.unlink(guest.sock)  # the fixture's fake guest still owns the path
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(guest.sock)
    srv.listen(1)

    def chatty():
        conn, _ = srv.accept()
        conn.sendall(b"\n[JNODE_AGENT_READY]\n")  # spontaneous console-open
        conn.recv(4096)                           # probe newline
        time.sleep(0.3)
        conn.sendall(b"\n[JNODE_AGENT_READY]\n")  # stale reply to the probe
        conn.recv(4096)                           # the real command
        conn.sendall(b"OUT:date\r\n")
        conn.sendall(b"\n[JNODE_AGENT_READY]\n")
        conn.close()

    t = threading.Thread(target=chatty, daemon=True)
    t.start()

    r = _run(state, "--instance", "t4c", "--oneshot", "date")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "OUT:date" in r.stdout
    srv.close()


def test_env_var_selects_instance(world):
    state, make = world
    make("t5")
    r = _run(state, "echo via-env", env_extra={"JNODE_INSTANCE": "t5"})
    assert r.returncode == 0, r.stdout + r.stderr
    assert "via-env" in r.stdout


def test_qemu_stop_unknown_instance_is_graceful(world):
    """--stop on a never-registered instance exits 0 with a hint, not
    an error."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    r = subprocess.run(
        ["bash", script, "--stop", "--instance", "no-such-ci-xyz"],
        capture_output=True, text=True, timeout=30, env=_env(state))
    assert r.returncode == 0, r.stdout + r.stderr
    assert "nothing to stop" in r.stdout


def test_qemu_bare_stop_reports_ambiguous_live_instances(world):
    """Regression: a bare --stop with two live instances must say so
    (and name them), not claim 'no live registered instance'."""
    state, make = world
    make("amb1")
    make("amb2")
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    r = subprocess.run(["bash", script, "--stop"],
                       capture_output=True, text=True, timeout=30,
                       env=_env(state))
    assert r.returncode == 0, r.stdout + r.stderr
    assert "no live registered instance" not in r.stdout
    assert "several live instances" in r.stdout + r.stderr
    assert "amb1" in r.stdout + r.stderr and "amb2" in r.stdout + r.stderr
    assert "stop one explicitly" in r.stdout
    # ambiguity must not stop anything
    r = subprocess.run([sys.executable, PATHS_PY, "--list"],
                       capture_output=True, text=True, timeout=30,
                       env=_env(state))
    assert "amb1" in r.stdout and "live" in r.stdout


def test_missing_instance_socket_is_a_clean_error(world):
    state, make = world
    make("t6")
    r = _run(state, "--instance", "no-such-instance", "date")
    # fail fast on the missing agent socket instead of hanging forever
    assert r.returncode != 0
    assert "ERROR" in r.stdout or "TIMEOUT" in r.stdout or "not ready" in r.stdout


def test_instance_flag_alone_is_usage_not_traceback(world):
    """A bare `--instance X` must print usage, not die with an
    IndexError traceback."""
    state, make = world
    make("t6a")
    r = _run(state, "--instance", "t6a")
    assert r.returncode == 2
    assert "Traceback" not in r.stderr
    assert "Usage" in r.stdout


def test_list_flag_works_after_instance(world):
    """`--instance X --list` must list, not ship `--list` to the guest."""
    state, make = world
    make("t6b")
    r = _run(state, "--instance", "t6b", "--list")
    assert r.returncode == 0, r.stdout + r.stderr
    assert "t6b" in r.stdout


def test_control_flags_honoured_in_any_position(world):
    """`--timeout 5 --status` is a status request, not a guest command."""
    state, make = world
    make("t6d")
    r = _run(state, "--timeout", "5", "--instance", "t6d", "--status")
    # mux is not running here: rc is 1, but the flag must be parsed as a
    # status request (mux: not running), never shipped to the guest
    assert "instance: t6d" in r.stdout
    assert "takes no commands" not in r.stdout
    # a control flag mixed with real commands is an error, not a guest run
    r = _run(state, "--instance", "t6d", "--status", "date")
    assert r.returncode == 2
    assert "takes no commands" in r.stdout
    # two control flags are mutually exclusive, not first-wins
    r = _run(state, "--instance", "t6d", "--stop", "--status")
    assert r.returncode == 2
    assert "mutually exclusive" in r.stdout


def test_mux_sigterm_removes_pidfile(world):
    """SIGTERM must drop mux.pid; a stale pid file makes ensure_mux()
    skip the start and hang every serial_cmd for the full deadline."""
    state, make = world
    make("t7")
    r = _run(state, "--instance", "t7", "date")
    assert r.returncode == 0, r.stdout + r.stderr
    pidfile = state / "t7" / "mux" / "mux.pid"
    assert pidfile.exists()
    pid = int(pidfile.read_text().strip())
    os.kill(pid, 15)
    deadline = time.time() + 10
    while pidfile.exists() and time.time() < deadline:
        time.sleep(0.1)
    assert not pidfile.exists(), "mux.pid survived SIGTERM"


def test_duplicate_instance_flag_is_error(world):
    """`--instance a --instance b` must not leak the second one through
    as a guest command."""
    state, make = world
    make("t8")
    r = _run(state, "--instance", "t8", "--instance", "t8", "date")
    assert r.returncode == 2
    assert "--instance given twice" in r.stdout


def test_serial_stop_ignores_recycled_pid(world):
    """--stop must not signal an unrelated process whose pid recycled
    into mux.pid, and must drop the stale claim."""
    state, make = world
    make("t9")
    pidfile = state / "t9" / "mux" / "mux.pid"
    pidfile.parent.mkdir(parents=True, exist_ok=True)
    victim = subprocess.Popen([sys.executable, "-c",
                               "import time; time.sleep(120)"])
    try:
        pidfile.write_text("%d\n" % victim.pid)
        r = _run(state, "--instance", "t9", "--stop")
        assert victim.poll() is None, "unrelated pid was killed"
        assert not pidfile.exists()
        assert "not running" in r.stdout
    finally:
        victim.kill()
        victim.wait()


def test_status_and_start_self_heal_recycled_mux_pid(world):
    """A recycled-but-live pid in mux.pid is not the mux: --status must
    not report it, and a command must start a fresh mux instead of
    hanging for the full deadline (the two-mux wedge)."""
    state, make = world
    make("t10")
    pidfile = state / "t10" / "mux" / "mux.pid"
    pidfile.parent.mkdir(parents=True, exist_ok=True)
    victim = subprocess.Popen([sys.executable, "-c",
                               "import time; time.sleep(120)"])
    try:
        pidfile.write_text("%d\n" % victim.pid)
        r = _run(state, "--instance", "t10", "--status")
        assert "mux: not running" in r.stdout
        r = _run(state, "--instance", "t10", "date")
        assert r.returncode == 0, r.stdout + r.stderr
        assert "OUT:date" in r.stdout
        assert victim.poll() is None, "unrelated pid was killed"
    finally:
        victim.kill()
        victim.wait()


def test_qemu_stop_ignores_stale_vm_pid(world):
    """--stop is the post-crash recovery path: a stale entry whose
    vm_pid recycled must be cleaned up without killing the innocent."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    victim = subprocess.Popen([sys.executable, "-c",
                               "import time; time.sleep(120)"])
    try:
        env = _env(state)
        r = subprocess.run(
            [sys.executable, PATHS_PY, "--register", "--instance", "stale1",
             "--hypervisor", "qemu", "--vm-name", "JNode x86",
             "--vm-pid", str(victim.pid), "--state", "ready"],
            capture_output=True, text=True, timeout=30, env=env)
        assert r.returncode == 0, r.stdout + r.stderr
        r = subprocess.run(["bash", script, "--stop", "--instance", "stale1"],
                           capture_output=True, text=True, timeout=60,
                           env=env)
        assert r.returncode == 0, r.stdout + r.stderr
        assert victim.poll() is None, "unrelated pid was killed"
        assert "no running qemu pid" in r.stdout
    finally:
        victim.kill()
        victim.wait()


def test_qemu_stop_never_executes_crafted_vm_pid(world):
    """The registry's vm_pid reaches Python via sys.argv, never the
    program text: a crafted / non-numeric value must stop cleanly with
    no execution and no traceback."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    env = _env(state)
    pwn = state / "PWNED"
    payload = ("1'), 'qemu-system') or __import__('os').system('touch %s"
               % pwn) + "' or jp.safe_kill_pid(int('1"
    # hand-edit the registry directly: --register's --vm-pid is int-typed,
    # the injection threat is a stale/planted entry file
    instdir = state / "instances"
    instdir.mkdir(parents=True, exist_ok=True)
    (instdir / "evil1.json").write_text(json.dumps(
        {"id": "evil1", "hypervisor": "qemu", "vm": "JNode x86",
         "vm_pid": payload, "state": "ready"}))
    r = subprocess.run(["bash", script, "--stop", "--instance", "evil1"],
                       capture_output=True, text=True, timeout=60, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    assert not pwn.exists(), "crafted vm_pid executed"
    assert "Traceback" not in r.stderr
    assert "no running qemu pid" in r.stdout


def test_qemu_start_refuses_unregistered_socket_owner(world):
    """--is-live is registry-blind: an unregistered VM (hand-started,
    or registration lost) holding the agent socket must still block a
    start -- otherwise the rm -f below the guard unlinks its live
    socket and the new QEMU re-binds the path (the id collision this
    launcher exists to prevent)."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    env = _env(state)
    r = subprocess.run([sys.executable, PATHS_PY, "--mkdir",
                        "--instance", "tsock"],
                       capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    r = subprocess.run([sys.executable, PATHS_PY, "--field", "agent_sock",
                        "--instance", "tsock"],
                       capture_output=True, text=True, timeout=30, env=env)
    sock_path = r.stdout.strip()
    listener = subprocess.Popen(
        [sys.executable, "-c",
         "import socket,sys,time\n"
         "s = socket.socket(socket.AF_UNIX)\n"
         "s.bind(sys.argv[1]); s.listen(1)\n"
         "time.sleep(120)", sock_path])
    try:
        deadline = time.time() + 5
        while time.time() < deadline and not os.path.exists(sock_path):
            time.sleep(0.1)
        assert os.path.exists(sock_path), "listener never bound"
        r = subprocess.run(["bash", script, "--instance", "tsock"],
                           capture_output=True, text=True, timeout=60,
                           env=env)
        assert r.returncode == 1, r.stdout + r.stderr
        assert "already in use" in r.stdout + r.stderr
        assert listener.poll() is None, "unregistered VM was disturbed"
        assert os.path.exists(sock_path), "live agent socket was unlinked"
    finally:
        listener.kill()
        listener.wait()


def test_qemu_start_dry_run_tolerates_leftover_socket(world):
    """A leftover socket FILE (hard-killed VM, no listener) must not
    block a new start -- only a live listener does."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    env = _env(state)
    r = subprocess.run([sys.executable, PATHS_PY, "--mkdir",
                        "--instance", "tleft"],
                       capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    r = subprocess.run([sys.executable, PATHS_PY, "--field", "agent_sock",
                        "--instance", "tleft"],
                       capture_output=True, text=True, timeout=30, env=env)
    sock_path = r.stdout.strip()
    s = socket.socket(socket.AF_UNIX)
    s.bind(sock_path)
    s.close()  # leaves the path behind, no listener
    assert os.path.exists(sock_path)
    iso = state / "dummy.iso"
    iso.write_bytes(b"")
    r = subprocess.run(["bash", script, "--instance", "tleft", "--dry-run",
                        str(iso)],
                       capture_output=True, text=True, timeout=60, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "instance:  tleft" in r.stdout


def test_qemu_stop_leaves_held_socket(world):
    """--stop is the stale-entry recovery path: it must never unlink
    the agent socket of a VM this launcher never started."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    env = _env(state)
    r = subprocess.run([sys.executable, PATHS_PY, "--mkdir",
                        "--instance", "tstop1"],
                       capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    r = subprocess.run([sys.executable, PATHS_PY, "--field", "agent_sock",
                        "--instance", "tstop1"],
                       capture_output=True, text=True, timeout=30, env=env)
    sock_path = r.stdout.strip()
    listener = subprocess.Popen(
        [sys.executable, "-c",
         "import socket,sys,time\n"
         "s = socket.socket(socket.AF_UNIX)\n"
         "s.bind(sys.argv[1]); s.listen(1)\n"
         "time.sleep(120)", sock_path])
    try:
        deadline = time.time() + 5
        while time.time() < deadline and not os.path.exists(sock_path):
            time.sleep(0.1)
        assert os.path.exists(sock_path), "listener never bound"
        # stale entry: registered with a pid that is dead, nothing to
        # kill -- the normal case for the recovery path
        dead = subprocess.Popen([sys.executable, "-c", "pass"])
        dead.wait()
        r = subprocess.run(
            [sys.executable, PATHS_PY, "--register", "--instance",
             "tstop1", "--hypervisor", "qemu", "--vm-name", "JNode",
             "--vm-pid", str(dead.pid), "--state", "ready"],
            capture_output=True, text=True, timeout=30, env=env)
        assert r.returncode == 0, r.stdout + r.stderr
        r = subprocess.run(["bash", script, "--stop", "--instance",
                            "tstop1"],
                           capture_output=True, text=True, timeout=60,
                           env=env)
        assert r.returncode == 0, r.stdout + r.stderr
        assert "left in place" in r.stdout
        assert listener.poll() is None, "foreign holder was disturbed"
        assert os.path.exists(sock_path), "held socket was unlinked"
    finally:
        listener.kill()
        listener.wait()


def test_qemu_stop_no_entry_reports_held_socket(world):
    """The state the start guard can produce (live holder, no registry
    entry) must be reported by --stop, not reported as a clean no-op
    while the socket is actually taken."""
    state, _ = world
    script = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                          "start_qemu.sh")
    env = _env(state)
    r = subprocess.run([sys.executable, PATHS_PY, "--mkdir",
                        "--instance", "tstop2"],
                       capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    r = subprocess.run([sys.executable, PATHS_PY, "--field", "agent_sock",
                        "--instance", "tstop2"],
                       capture_output=True, text=True, timeout=30, env=env)
    sock_path = r.stdout.strip()
    listener = subprocess.Popen(
        [sys.executable, "-c",
         "import socket,sys,time\n"
         "s = socket.socket(socket.AF_UNIX)\n"
         "s.bind(sys.argv[1]); s.listen(1)\n"
         "time.sleep(120)", sock_path])
    try:
        deadline = time.time() + 5
        while time.time() < deadline and not os.path.exists(sock_path):
            time.sleep(0.1)
        assert os.path.exists(sock_path), "listener never bound"
        r = subprocess.run(["bash", script, "--stop", "--instance",
                            "tstop2"],
                           capture_output=True, text=True, timeout=60,
                           env=env)
        assert r.returncode == 0, r.stdout + r.stderr  # no-op semantics
        assert "holds the agent socket" in r.stdout
        assert listener.poll() is None
        assert os.path.exists(sock_path)
    finally:
        listener.kill()
        listener.wait()


_VBOX_START_DRIVER = """\
import os, sys, types
sys.path.insert(0, sys.argv[1])
import jnode_vbox as jv
# no VirtualBox in tests: a non-running VM state, and startvm is never
# reached without passing the pipe guard under test
jv.info = lambda name: {"VMState": "poweroff"}
jv.vbox = lambda *a, **k: None
a = types.SimpleNamespace(name=sys.argv[3], instance=sys.argv[2],
                          entry=0, wait_ready=0)
rc = jv.cmd_start(a)
sys.exit(rc if rc else 0)
"""


def _vbox_cmd_start(state, instance, name="JNode-test"):
    vbox_scripts = os.path.abspath(
        os.path.join(HERE, "..", "..", "jnode-vbox", "scripts"))
    return subprocess.run(
        [sys.executable, "-c", _VBOX_START_DRIVER, vbox_scripts,
         instance, name],
        capture_output=True, text=True, timeout=60, env=_env(state))


def test_vbox_start_refuses_held_agent_socket(world):
    """cmd_start's stale-pipe cleanup must not unlink a path another
    process holds (the hand-started-VM-on-legacy-default-paths
    collision, reachable from the documented quick start)."""
    state, _ = world
    env = _env(state)
    r = subprocess.run([sys.executable, PATHS_PY, "--mkdir",
                        "--instance", "tvx1"],
                       capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    r = subprocess.run([sys.executable, PATHS_PY, "--field", "agent_sock",
                        "--instance", "tvx1"],
                       capture_output=True, text=True, timeout=30, env=env)
    sock_path = r.stdout.strip()
    listener = subprocess.Popen(
        [sys.executable, "-c",
         "import socket,sys,time\n"
         "s = socket.socket(socket.AF_UNIX)\n"
         "s.bind(sys.argv[1]); s.listen(1)\n"
         "time.sleep(120)", sock_path])
    try:
        deadline = time.time() + 5
        while time.time() < deadline and not os.path.exists(sock_path):
            time.sleep(0.1)
        assert os.path.exists(sock_path), "listener never bound"
        r = _vbox_cmd_start(state, "tvx1")
        assert r.returncode == 1, r.stdout + r.stderr
        assert "held by another process" in r.stdout + r.stderr
        assert listener.poll() is None, "holder was disturbed"
        assert os.path.exists(sock_path), "held path was unlinked"
    finally:
        listener.kill()
        listener.wait()


def test_vbox_start_removes_stale_pipe(world):
    """A stale pipe FILE with no holder is still cleaned up -- the
    guard must not block the normal path it exists to serve."""
    state, _ = world
    env = _env(state)
    r = subprocess.run([sys.executable, PATHS_PY, "--mkdir",
                        "--instance", "tvx2"],
                       capture_output=True, text=True, timeout=30, env=env)
    assert r.returncode == 0, r.stdout + r.stderr
    r = subprocess.run([sys.executable, PATHS_PY, "--field", "agent_sock",
                        "--instance", "tvx2"],
                       capture_output=True, text=True, timeout=30, env=env)
    sock_path = r.stdout.strip()
    os.mkfifo(sock_path)  # leftover pipe, nobody holds it
    assert os.path.exists(sock_path)
    r = _vbox_cmd_start(state, "tvx2")
    assert r.returncode == 0, r.stdout + r.stderr
    assert not os.path.exists(sock_path), "stale pipe not cleaned"


_VBOX_CONFIG_DRIVER = """\
import os, sys, types
sys.path.insert(0, sys.argv[1])
import jnode_vbox as jv
calls = []
jv.info = lambda name: {}
jv.vbox = lambda *a, **k: calls.append(a)
a = types.SimpleNamespace(name="JNode-cfg", instance=sys.argv[2],
                          iso=None, memory=2048,
                          kdb=(sys.argv[3] == "kdb"))
rc = jv.cmd_config(a)
for c in calls:
    print("CALL " + " ".join(str(x) for x in c))
sys.exit(rc if rc else 0)
"""


def _vbox_cmd_config(state, instance, kdb=False):
    vbox_scripts = os.path.abspath(
        os.path.join(HERE, "..", "..", "jnode-vbox", "scripts"))
    return subprocess.run(
        [sys.executable, "-c", _VBOX_CONFIG_DRIVER, vbox_scripts,
         instance, "kdb" if kdb else "file"],
        capture_output=True, text=True, timeout=60, env=_env(state))


def _field(state, name, instance):
    r = subprocess.run([sys.executable, PATHS_PY, "--field", name,
                        "--instance", instance],
                       capture_output=True, text=True, timeout=30,
                       env=_env(state))
    assert r.returncode == 0, r.stdout + r.stderr
    return r.stdout.strip()


def test_vbox_config_uart1_default_is_file_log(world):
    """UART1 must default to a raw file boot log, not a KDB server
    pipe: an unread pipe wedges the guest (VBox has no reader until a
    client attaches, unlike QEMU's logfile= tee) and the log is the
    always-on use while KDB is the rare one."""
    state, _ = world
    r = _vbox_cmd_config(state, "tvc1")
    assert r.returncode == 0, r.stdout + r.stderr
    modify = [ln for ln in r.stdout.splitlines() if ln.startswith("CALL modifyvm")]
    assert len(modify) == 1, r.stdout + r.stderr
    boot_log = _field(state, "boot_log", "tvc1")
    kdb_sock = _field(state, "kdb_sock", "tvc1")
    args = modify[0].split()
    assert args[args.index("--uartmode1") + 1] == "file"
    assert args[args.index("--uartmode1") + 2] == boot_log
    assert kdb_sock not in args, "default config wired UART1 to the KDB pipe"


def test_vbox_config_kdb_flag_selects_pipe(world):
    """--kdb keeps the opt-in pipe layout: server at kdb_sock."""
    state, _ = world
    r = _vbox_cmd_config(state, "tvc2", kdb=True)
    assert r.returncode == 0, r.stdout + r.stderr
    modify = [ln for ln in r.stdout.splitlines() if ln.startswith("CALL modifyvm")]
    assert len(modify) == 1, r.stdout + r.stderr
    kdb_sock = _field(state, "kdb_sock", "tvc2")
    args = modify[0].split()
    assert args[args.index("--uartmode1") + 1] == "server"
    assert args[args.index("--uartmode1") + 2] == kdb_sock


def test_grub_pick_reports_menu_gone_not_timeout(tmp_path):
    """wait_band also bails the moment the boot log moves: on a
    timeout-0 ISO the 25s search never runs -- the failure reason must
    say the menu is gone, not that it timed out."""
    grub = os.path.join(HERE, "..", "..", "jnode-qemu", "scripts",
                        "grub_pick.py")
    log = tmp_path / "boot.log"
    log.write_text("Serial console available\n")
    r = subprocess.run([sys.executable, grub, "--monitor", "/nonexistent.sock",
                        "--entry", "1", "--boot-log", str(log)],
                       capture_output=True, text=True, timeout=30)
    assert r.returncode == 4, r.stdout + r.stderr
    assert "menu is gone" in r.stderr
    assert "never appeared within" not in r.stderr


def test_serial_mux_single_instance_guard(world):
    """Two concurrent serial_mux starts must leave one mux, not two on
    the same FIFO + guest socket; a later start is idempotent."""
    state, make = world
    make("t11")
    env = _env(state)
    mux = os.path.join(SCRIPTS, "serial_mux.py")
    procs = [subprocess.Popen([sys.executable, mux, "--instance", "t11"],
                              env=env, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL)
             for _ in range(2)]
    try:
        deadline = time.time() + 15
        while time.time() < deadline:
            if sum(1 for p in procs if p.poll() is None) <= 1:
                break
            time.sleep(0.2)
        alive = [p for p in procs if p.poll() is None]
        assert len(alive) <= 1, "two serial muxes running on one FIFO"
        # idempotent: a later start reports, does not error or double up
        r = subprocess.run([sys.executable, mux, "--instance", "t11"],
                           env=env, capture_output=True, text=True,
                           timeout=30)
        assert r.returncode == 0, r.stdout + r.stderr
        assert "already running" in r.stdout + r.stderr
        assert sum(1 for p in procs if p.poll() is None) <= 1
    finally:
        for p in procs:
            if p.poll() is None:
                p.terminate()
        for p in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()


def test_write_keeps_earlier_commands(world):
    """`serial_cmd date --write PATH` must run date AND write the file."""
    state, make = world
    make("t6c")
    target = os.path.join(str(state), "t6c", "written.txt")
    r = subprocess.run(
        [sys.executable, SERIAL_CMD, "--instance", "t6c",
         "date", "--write", target],
        input="hello\n", capture_output=True, text=True, timeout=90,
        env=_env(state))
    assert r.returncode == 0, r.stdout + r.stderr
    assert "OUT:date" in r.stdout
    assert "writing " in r.stdout


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

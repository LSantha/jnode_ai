"""Unit tests for jnode_paths (instance model + registry).

Run:  python3 -m pytest .opencode/skills/jnode-serial/tests/ -q
"""

import os
import subprocess
import sys

import pytest

SCRIPTS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scripts"))
sys.path.insert(0, SCRIPTS)

import jnode_paths as jp  # noqa: E402


@pytest.fixture(autouse=True)
def isolated_state(tmp_path, monkeypatch):
    """Point the registry/root at a temp dir so /tmp/jnode is untouched."""
    state = tmp_path / "jnode"
    monkeypatch.setattr(jp, "STATE_DIR", str(state))
    monkeypatch.setattr(jp, "REGISTRY_DIR", str(state / "instances"))
    monkeypatch.delenv("JNODE_INSTANCE", raising=False)
    # a leftover /tmp/jnode.serial2 on the test host must not flip
    # alloc_instance()'s hand-started-VM check
    monkeypatch.setattr(jp, "_legacy_sock_busy", lambda: False)
    return state


# --------------------------------------------------------------------------
# Path derivation
# --------------------------------------------------------------------------

def test_default_instance_keeps_legacy_paths():
    p = jp.paths_for("default")
    assert p.legacy is True
    assert p.root is None
    assert p.agent_sock == "/tmp/jnode.serial2"
    assert p.kdb_sock == "/tmp/jnode.kdb"
    assert p.boot_log == "/tmp/qemu_serial.log"
    assert p.monitor_sock == "/tmp/qemu_monitor.sock"
    assert p.fifo == "/tmp/jnode_serial_cmd.fifo"
    assert p.req_dir == "/tmp/jnode_serial_req"
    assert p.resp_dir == "/tmp/jnode_serial_resp"
    assert p.mux_pid == "/tmp/jnode_serial_mux.pid"
    assert p.mux_status == "/tmp/jnode_serial_mux.status"
    assert p.mux_log == "/tmp/jnode_serial_mux.log"
    assert p.kdb_fifo == "/tmp/kdb_cmd.fifo"
    assert p.kdb_resp == "/tmp/kdb_resp.log"


def test_named_instance_lives_under_its_own_root():
    p = jp.paths_for("qemu-2")
    root = os.path.join(jp.STATE_DIR, "qemu-2")
    assert p.legacy is False
    assert p.root == root
    assert p.agent_sock == os.path.join(root, "agent.sock")
    assert p.kdb_sock == os.path.join(root, "kdb.sock")
    assert p.boot_log == os.path.join(root, "boot.log")
    assert p.monitor_sock == os.path.join(root, "monitor.sock")
    assert p.mux_dir == os.path.join(root, "mux")
    assert p.fifo == os.path.join(root, "mux", "cmd.fifo")
    assert p.req_dir == os.path.join(root, "mux", "req")
    assert p.resp_dir == os.path.join(root, "mux", "resp")
    assert p.mux_log == os.path.join(root, "mux", "mux.log")
    assert p.kdb_fifo == os.path.join(root, "kdb", "cmd.fifo")
    assert p.kdb_resp == os.path.join(root, "kdb", "resp.log")


def test_default_state_layout_constant():
    env = dict(os.environ)
    env.pop("JNODE_STATE_DIR", None)
    out = subprocess.run(
        [sys.executable, "-c",
         "import sys; sys.path.insert(0, %r); import jnode_paths as j; "
         "print(j.STATE_DIR, j.REGISTRY_DIR)" % SCRIPTS],
        capture_output=True, text=True, env=env).stdout.split()
    assert out == ["/tmp/jnode", "/tmp/jnode/instances"]


def test_two_named_instances_do_not_share_a_path():
    a, b = jp.paths_for("qemu-1"), jp.paths_for("vbox-JNode")
    for field in ("agent_sock", "kdb_sock", "boot_log", "monitor_sock",
                  "fifo", "req_dir", "resp_dir", "mux_pid", "mux_status",
                  "mux_log", "kdb_fifo", "kdb_resp", "kdb_log"):
        assert getattr(a, field) != getattr(b, field), field


def test_named_instance_never_collides_with_legacy():
    legacy = jp.paths_for("default")
    named = jp.paths_for("qemu-1")
    for field in ("agent_sock", "kdb_sock", "boot_log", "monitor_sock",
                  "fifo", "req_dir", "resp_dir", "mux_pid", "mux_status",
                  "mux_out", "mux_log", "kdb_fifo", "kdb_resp", "kdb_log",
                  "kdb_out"):
        assert getattr(named, field) != getattr(legacy, field), field


@pytest.mark.parametrize("bad", ["", "has space", "sl/ash", "-leading",
                                 "..", "a" * 65])
def test_invalid_instance_ids_rejected(bad):
    with pytest.raises(jp.InstanceError):
        jp.paths_for(bad)


def test_env_exports_mention_every_socket():
    out = jp.paths_for("qemu-1").env_exports()
    root = os.path.join(jp.STATE_DIR, "qemu-1")
    assert "export JNODE_INSTANCE=qemu-1" in out
    assert "export JNODE_AGENT_SOCK=%s" % os.path.join(root, "agent.sock") in out
    assert "export JNODE_BOOT_LOG=%s" % os.path.join(root, "boot.log") in out


# --------------------------------------------------------------------------
# Registry + liveness
# --------------------------------------------------------------------------

def _register(entry_id, hypervisor="qemu", vm_pid=None, vm=None, state="ready"):
    return jp.write_entry({
        "id": entry_id, "hypervisor": hypervisor, "vm_pid": vm_pid,
        "vm": vm, "pid": vm_pid, "state": state,
        "agent_sock": jp.paths_for(entry_id).agent_sock,
    })


def test_registry_roundtrip():
    _register("qemu-1", vm_pid=os.getpid())
    entry = jp.read_entry("qemu-1")
    assert entry["hypervisor"] == "qemu"
    assert jp.entry_live(entry) is True


def test_dead_pid_entry_is_not_live():
    # Spawn a child and wait for it: its pid is real but gone.
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    _register("qemu-dead", vm_pid=proc.pid)
    assert jp.entry_live(jp.read_entry("qemu-dead")) is False


def test_unregistered_instance_is_not_live():
    assert jp.entry_live(None) is False
    assert jp.entry_live({"id": "ghost", "hypervisor": "qemu"}) is False


def test_prune_stale_removes_dead_entries():
    _register("qemu-live", vm_pid=os.getpid())
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    _register("qemu-dead", vm_pid=proc.pid)
    removed = jp.prune_stale()
    assert removed == ["qemu-dead"]
    assert jp.all_ids() == ["qemu-live"]


# --------------------------------------------------------------------------
# Resolution / allocation
# --------------------------------------------------------------------------

def test_resolve_prefers_explicit_then_env(monkeypatch):
    _register("qemu-1", vm_pid=os.getpid())
    assert jp.resolve_instance("qemu-1") == "qemu-1"
    monkeypatch.setenv("JNODE_INSTANCE", "qemu-1")
    assert jp.resolve_instance() == "qemu-1"
    assert jp.resolve_instance("qemu-1") == "qemu-1"  # explicit beats env


def test_resolve_without_registry_defaults_to_default():
    assert jp.resolve_instance() == "default"
    assert jp.resolve_paths().agent_sock == "/tmp/jnode.serial2"


def test_resolve_autodetects_single_live_instance():
    _register("qemu-7", vm_pid=os.getpid())
    assert jp.resolve_instance() == "qemu-7"


def test_resolve_raises_on_ambiguous_instances():
    _register("qemu-1", vm_pid=os.getpid())
    _register("qemu-2", vm_pid=os.getpid())
    with pytest.raises(jp.InstanceError) as e:
        jp.resolve_instance()
    assert "qemu-1" in str(e.value) and "qemu-2" in str(e.value)


def test_alloc_returns_default_when_free():
    assert jp.alloc_instance() == "default"


def test_alloc_skips_busy_default():
    _register("default", vm_pid=os.getpid())
    assert jp.alloc_instance() == "qemu-1"
    _register("qemu-1", vm_pid=os.getpid())
    assert jp.alloc_instance() == "qemu-2"


def test_alloc_reuses_stale_default():
    proc = subprocess.Popen([sys.executable, "-c", "pass"])
    proc.wait()
    _register("default", vm_pid=proc.pid)   # dead
    assert jp.alloc_instance() == "default"


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def _cli(*args, state_dir=None):
    env = dict(os.environ)
    if state_dir is not None:
        env["JNODE_STATE_DIR"] = str(state_dir)
    else:
        env.pop("JNODE_STATE_DIR", None)
    return subprocess.run(
        [sys.executable, os.path.join(SCRIPTS, "jnode_paths.py")] + list(args),
        capture_output=True, text=True, env=env)


def test_cli_field_and_env_for_default():
    r = _cli("--field", "agent_sock", "--instance", "default")
    assert r.returncode == 0 and r.stdout.strip() == "/tmp/jnode.serial2"
    r = _cli("--env", "--instance", "qemu-3", state_dir="/tmp/jnode-test")
    assert ("export JNODE_AGENT_SOCK=/tmp/jnode-test/qemu-3/agent.sock"
            in r.stdout)


def test_cli_register_list_unregister(tmp_path):
    sd = tmp_path / "state"
    r = _cli("--register", "--instance", "qemu-9", "--hypervisor", "qemu",
             "--vm-pid", str(os.getpid()), "--state", "ready", state_dir=sd)
    assert r.returncode == 0 and "registered qemu-9" in r.stdout
    r = _cli("--list", state_dir=sd)
    assert r.returncode == 0 and "qemu-9" in r.stdout and "live" in r.stdout
    r = _cli("--unregister", "--instance", "qemu-9", state_dir=sd)
    assert r.returncode == 0
    assert not (sd / "instances" / "qemu-9.json").exists()


def test_cli_alloc_and_probe(tmp_path):
    sd = tmp_path / "state"
    r = _cli("--alloc", state_dir=sd)
    # a leftover /tmp/jnode.serial2 on the host means a hand-started VM
    # owns the legacy default socket, so alloc must skip it
    if os.path.exists("/tmp/jnode.serial2"):
        assert r.returncode == 0 and r.stdout.strip() == "qemu-1"
    else:
        assert r.returncode == 0 and r.stdout.strip() == "default"
    # --probe reads the legacy socket path, which is host-global: a
    # hand-started VM (or leftover socket) on /tmp/jnode.serial2 flips it
    r = _cli("--probe", "--instance", "default", state_dir=sd)
    assert "agent_sock=" in r.stdout
    assert r.returncode == (0 if os.path.exists("/tmp/jnode.serial2") else 1)


def test_cli_rejects_bad_instance(tmp_path):
    r = _cli("--field", "agent_sock", "--instance", "bad id",
             state_dir=tmp_path / "state")
    assert r.returncode == 2 and "invalid instance id" in r.stderr


def test_alloc_skips_handstarted_legacy_sock(monkeypatch):
    # the legacy default socket exists but no registry entry knows about
    # it: alloc must not hand 'default' out (review nit)
    monkeypatch.setattr(jp, "_legacy_sock_busy", lambda: True)
    assert jp.alloc_instance() == "qemu-1"


def test_resolve_instance_autodetect():
    # nothing live -> legacy default
    assert jp.resolve_instance() == "default"
    _register("qemu-5", vm_pid=os.getpid())
    # exactly one live -> that instance
    assert jp.resolve_instance() == "qemu-5"
    _register("qemu-6", vm_pid=os.getpid())
    # several live -> explicit choice required
    with pytest.raises(jp.InstanceError):
        jp.resolve_instance()


def test_cli_resolve(isolated_state):
    sd = isolated_state
    r = _cli("--resolve", state_dir=sd)
    assert r.returncode == 1 and "no live" in r.stderr
    _register("qemu-7", vm_pid=os.getpid())
    r = _cli("--resolve", state_dir=sd)
    assert r.returncode == 0 and r.stdout.strip() == "qemu-7"


def test_vbox_register_writes_vm_key(monkeypatch):
    """PR #761 review finding 1: jnode_vbox.register() must write the
    registry key "vm" -- entry_live(), --list/--is-live and
    serial_mux.configure() all read entry["vm"], not "vm_name"."""
    import importlib.util
    vbox_path = os.path.abspath(os.path.join(
        os.path.dirname(__file__), "..", "..", "jnode-vbox", "scripts",
        "jnode_vbox.py"))
    spec = importlib.util.spec_from_file_location("jnode_vbox_under_test",
                                                  vbox_path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    paths = jp.Paths("vbox-rt")
    mod.register(paths, "JNode-RT", "ready")
    entry = jp.read_entry("vbox-rt")
    assert entry.get("vm") == "JNode-RT"
    # with the key fixed, a running vbox VM survives liveness and prune
    monkeypatch.setattr(jp, "_vbox_running", lambda name: name == "JNode-RT")
    assert jp.entry_live(entry) is True
    assert "vbox-rt" in [i for i, _ in jp.live_entries()]
    assert "vbox-rt" not in jp.prune_stale()

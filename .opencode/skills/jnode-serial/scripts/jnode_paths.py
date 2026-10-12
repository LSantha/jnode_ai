#!/usr/bin/env python3
"""Per-instance resource paths and instance registry for the JNode skills.

One running JNode VM is one "instance". Every host-side resource an instance
owns (agent socket, KDB socket, boot log, QEMU monitor, mux IPC, KDB mux IPC)
is derived from its instance id, so any number of VMs -- VirtualBox or QEMU,
local or CI -- can run side by side without touching each other's files.

Instance id
    explicit argument / --instance  >  $JNODE_INSTANCE  >  autodetect.
Autodetect scans the registry: exactly one live instance -> that id,
none -> "default", several -> error (the caller must choose).

Legacy default
    The id "default" keeps the historical fixed paths
    (/tmp/jnode.serial2, /tmp/jnode_serial_*, /tmp/jnode.kdb,
    /tmp/qemu_serial.log, /tmp/qemu_monitor.sock, /tmp/kdb_*)
    so existing single-VM setups, tests/jdwp and the local/*.sh helper
    scripts keep working unchanged. Launchers only allocate a different id
    when "default" is already taken by a live instance.

Layout for any other id (say "qemu-2"), root=/tmp/jnode/qemu-2:
    root/agent.sock    UART2  guest agent console (the main console)
    root/kdb.sock      UART1  interactive KDB socket
    root/boot.log      UART1  boot / serial log (file mode)
    root/monitor.sock  QEMU monitor (GRUB entry selection), QEMU only
    root/mux/          serial mux IPC: cmd.fifo req/ resp/ mux.{pid,status,out,log}
    root/kdb/          kdb mux IPC: cmd.fifo resp.log mux.{log,out}

Registry
    /tmp/jnode/instances/<id>.json written by the launchers
    (jnode-qemu start_qemu.sh, jnode-vbox jnode_vbox.py). Field "vm_pid"
    is the QEMU pid for qemu entries; hypervisor "vbox" entries are checked
    with `vboxmanage showvminfo`. Both fall back to "agent socket exists".

CLI (shell friendly):
    eval "$(python3 jnode_paths.py --env [--instance ID])"
    python3 jnode_paths.py --field agent_sock [--instance ID]
    python3 jnode_paths.py --list
    python3 jnode_paths.py --alloc --prefix qemu
    python3 jnode_paths.py --resolve            # the single live id
                                                # (exit 1 none, 2 several)
    python3 jnode_paths.py --register --instance ID --hypervisor qemu \
        --vm-name NAME [--vm-pid PID] [--iso PATH] [--state ready]
    python3 jnode_paths.py --unregister --instance ID
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

DEFAULT_INSTANCE = "default"
# State root: registry + per-instance dirs. Overridable so a sandboxed run
# (tests, containers) never touches /tmp/jnode. Legacy "default" paths are
# literals and do NOT move with it.
STATE_DIR = os.environ.get("JNODE_STATE_DIR") or "/tmp/jnode"
REGISTRY_DIR = os.path.join(STATE_DIR, "instances")

_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

# Historical fixed paths, used verbatim for the "default" instance.
_LEGACY = {
    "agent_sock": "/tmp/jnode.serial2",
    "kdb_sock": "/tmp/jnode.kdb",
    "boot_log": "/tmp/qemu_serial.log",
    "monitor_sock": "/tmp/qemu_monitor.sock",
    "fifo": "/tmp/jnode_serial_cmd.fifo",
    "req_dir": "/tmp/jnode_serial_req",
    "resp_dir": "/tmp/jnode_serial_resp",
    "mux_pid": "/tmp/jnode_serial_mux.pid",
    "mux_status": "/tmp/jnode_serial_mux.status",
    "mux_out": "/tmp/jnode_serial_mux.out",
    "mux_log": "/tmp/jnode_serial_mux.log",
    "kdb_fifo": "/tmp/kdb_cmd.fifo",
    "kdb_resp": "/tmp/kdb_resp.log",
    "kdb_log": "/tmp/kdb_mux.log",
    "kdb_out": "/tmp/kdb_mux.out",
    "kdb_mux_pid": "/tmp/kdb_mux.pid",
    "stderr_log": "/tmp/qemu_launch.log",
}

ENV_FIELDS = (
    "JNODE_INSTANCE",
    "JNODE_AGENT_SOCK",
    "JNODE_KDB_SOCK",
    "JNODE_BOOT_LOG",
    "JNODE_MONITOR_SOCK",
    "JNODE_MUX_FIFO",
    "JNODE_MUX_DIR",
    "JNODE_KDB_FIFO",
    "JNODE_KDB_DIR",
)


class InstanceError(Exception):
    """Ambiguous or invalid instance selection."""


class Paths(object):
    """All host-side resources owned by one JNode instance."""

    def __init__(self, instance_id):
        self.id = _check_id(instance_id)
        self.legacy = self.id == DEFAULT_INSTANCE
        if self.legacy:
            self.root = None
            for name, value in _LEGACY.items():
                setattr(self, name, value)
            # Legacy IPC files are flat in /tmp (see _LEGACY), so the
            # "dir" form is /tmp itself; keep it shell-eval friendly.
            self.mux_dir = "/tmp"
            self.kdb_dir = "/tmp"
        else:
            self.root = os.path.join(STATE_DIR, self.id)
            self.agent_sock = os.path.join(self.root, "agent.sock")
            self.kdb_sock = os.path.join(self.root, "kdb.sock")
            self.boot_log = os.path.join(self.root, "boot.log")
            self.stderr_log = os.path.join(self.root, "qemu.log")
            self.monitor_sock = os.path.join(self.root, "monitor.sock")
            self.mux_dir = os.path.join(self.root, "mux")
            self.kdb_dir = os.path.join(self.root, "kdb")
            self.fifo = os.path.join(self.mux_dir, "cmd.fifo")
            self.req_dir = os.path.join(self.mux_dir, "req")
            self.resp_dir = os.path.join(self.mux_dir, "resp")
            self.mux_pid = os.path.join(self.mux_dir, "mux.pid")
            self.mux_status = os.path.join(self.mux_dir, "mux.status")
            self.mux_out = os.path.join(self.mux_dir, "mux.out")
            self.mux_log = os.path.join(self.mux_dir, "mux.log")
            self.kdb_fifo = os.path.join(self.kdb_dir, "cmd.fifo")
            self.kdb_resp = os.path.join(self.kdb_dir, "resp.log")
            self.kdb_log = os.path.join(self.kdb_dir, "mux.log")
            self.kdb_out = os.path.join(self.kdb_dir, "mux.out")
            self.kdb_mux_pid = os.path.join(self.kdb_dir, "kdb_mux.pid")

    @property
    def registry_path(self):
        return registry_path(self.id)

    @property
    def ipc_dirs(self):
        """Directories the mux clients/daemons must create."""
        if self.legacy:
            return (self.req_dir, self.resp_dir)
        return (self.root, self.mux_dir, self.req_dir, self.resp_dir)

    @property
    def kdb_ipc_dirs(self):
        if self.legacy:
            return ()
        return (self.root, self.kdb_dir)

    def env_exports(self):
        lines = ["JNODE_INSTANCE=%s" % self.id,
                 "JNODE_AGENT_SOCK=%s" % self.agent_sock,
                 "JNODE_KDB_SOCK=%s" % self.kdb_sock,
                 "JNODE_BOOT_LOG=%s" % self.boot_log,
                 "JNODE_MONITOR_SOCK=%s" % self.monitor_sock,
                 "JNODE_MUX_FIFO=%s" % self.fifo,
                 # same meaning for every instance: the mux working
                 # directory (the legacy layout keeps its flat /tmp files)
                 "JNODE_MUX_DIR=%s" % self.mux_dir,
                 "JNODE_KDB_FIFO=%s" % self.kdb_fifo,
                 "JNODE_KDB_DIR=%s" % (self.kdb_dir)]
        return "\n".join("export %s" % l for l in lines) + "\n"

    def as_dict(self):
        d = {"id": self.id, "legacy": self.legacy, "root": self.root}
        for name in ("agent_sock", "kdb_sock", "boot_log", "monitor_sock",
                     "fifo", "req_dir", "resp_dir", "mux_pid", "mux_status",
                     "mux_out", "mux_log", "kdb_fifo", "kdb_resp", "kdb_log",
                     "kdb_out", "kdb_mux_pid", "mux_dir", "kdb_dir",
                     "stderr_log", "registry_path"):
            d[name] = getattr(self, name)
        return d


def _check_id(instance_id):
    if not instance_id:
        raise InstanceError("empty instance id")
    if not _ID_RE.match(instance_id):
        raise InstanceError(
            "invalid instance id %r (want [A-Za-z0-9][A-Za-z0-9._-]{0,63})"
            % instance_id)
    return instance_id


def paths_for(instance_id):
    return Paths(instance_id)


def makedirs_for(paths):
    """Create every directory this instance's files live in."""
    made = []
    for d in set(list(paths.ipc_dirs) + list(paths.kdb_ipc_dirs) +
                 [os.path.dirname(paths.agent_sock),
                  os.path.dirname(paths.boot_log),
                  os.path.dirname(paths.stderr_log)]):
        if d and d != "/":
            os.makedirs(d, exist_ok=True)
            made.append(d)
    return made


# --------------------------------------------------------------------------
# Registry
# --------------------------------------------------------------------------

def registry_path(instance_id):
    return os.path.join(REGISTRY_DIR, _check_id(instance_id) + ".json")


def read_entry(instance_id):
    try:
        with open(registry_path(instance_id), "r") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write_entry(entry):
    _check_id(entry["id"])
    try:
        os.makedirs(REGISTRY_DIR, exist_ok=True)
    except OSError:
        pass
    entry["updated"] = int(time.time())
    path = registry_path(entry["id"])
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(entry, f, indent=1, sort_keys=True)
    os.replace(tmp, path)
    return entry


def unregister(instance_id):
    try:
        os.unlink(registry_path(instance_id))
        return True
    except OSError:
        return False


def all_ids():
    try:
        names = sorted(os.listdir(REGISTRY_DIR))
    except OSError:
        return []
    return [n[:-5] for n in names if n.endswith(".json")]


def _pid_alive(pid):
    try:
        os.kill(int(pid), 0)
        return True
    except (TypeError, ValueError, OSError):
        return False


def _signal(pid, sig):
    try:
        os.kill(int(pid), sig)
        return True
    except (OSError, TypeError, ValueError):
        return False


def pid_owns(pid, needle):
    """True only if `pid` is alive AND really is a `needle` process.

    Matches argv[0]'s basename (or argv[1] for `python3 script.py`), so
    a `tail -f .../serial_mux.py` is not mistaken for the mux. Without
    /proc (macOS, masked containers) identity cannot be proven; fall
    back to the existence check rather than declaring a live process
    stale -- the two-mux wedge is worse than a stale kill on a platform
    this code does not support.
    """
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    # test-only: let a test suite stand a pid in for a real qemu-system
    # without needing a VM (the CLI is a subprocess, so an in-process
    # monkeypatch cannot reach it)
    fake = os.environ.get("JNODE_TEST_FAKE_QEMU_PID")
    if fake and needle == "qemu-system":
        try:
            if pid == int(fake) and _pid_alive(pid):
                return True
        except ValueError:
            pass
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            argv = [a for a in f.read().split(b"\0") if a]
    except FileNotFoundError:
        return _pid_alive(pid)
    except (OSError, TypeError, ValueError):
        return False
    if not argv:
        return False
    a0 = os.path.basename(argv[0].decode("utf-8", "replace"))
    if needle in a0:
        return True
    # `python3 /path/script.py`: exact basename, so a `-c` code string
    # that merely mentions the needle does not match
    if len(argv) > 1 and a0.split(".")[0].startswith("python"):
        return needle == os.path.basename(
            argv[1].decode("utf-8", "replace"))
    return False


def safe_kill_pid(pid, needle, sig=15):
    """Signal `pid` only if it is really a `needle` process.

    A stale pid file can name a recycled pid; killing it would take out
    an unrelated process. Returns True when the signal was sent.
    """
    return pid_owns(pid, needle) and _signal(pid, sig)


def _vbox_binary():
    # installs differ only in case; a miss here would make every vbox
    # entry read back as dead (the quiet form of the vm/vm_name bug)
    return (shutil.which("VBoxManage") or shutil.which("vboxmanage"))


def _vbox_running(vm_name):
    if not vm_name:
        return False
    exe = _vbox_binary()
    if not exe:
        return False
    try:
        out = subprocess.run(
            [exe, "showvminfo", vm_name, "--machinereadable"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=15).stdout.decode("utf-8", "replace")
    except (OSError, subprocess.SubprocessError):
        return False
    for line in out.splitlines():
        if line.startswith("VMState="):
            return line.split("=", 1)[1].strip('"') == "running"
    return False


def entry_live(entry):
    """Is the VM behind this registry entry still there?

    qemu   -> QEMU pid alive (fallback: agent socket still present when the
              launcher did not record a pid)
    vbox   -> `vboxmanage showvminfo` says running (a hard stop leaves the
              UART pipe behind, so a dead-looking VM is never resurrected
              from the socket)
    either -> a registered path that exists counts as live only when nothing
              better is known, so a hand-started VM with a stale entry still
              resolves.
    """
    if not entry:
        return False
    hyp = entry.get("hypervisor")
    if hyp == "qemu":
        pid = entry.get("vm_pid") or entry.get("pid")
        if pid:
            # identity, not just liveness: a recycled pid makes --list,
            # --resolve, --alloc and start_qemu.sh --stop act on a
            # phantom (and kill an innocent)
            return pid_owns(pid, "qemu-system")
        return os.path.exists(entry.get("agent_sock") or "")
    if hyp == "vbox":
        if _vbox_running(entry.get("vm")):
            return True
        # Socket still there right after a hard stop: treat as dead so the
        # instance id becomes reusable.
        return False
    if entry.get("pid"):
        return _pid_alive(entry["pid"])
    return os.path.exists(entry.get("agent_sock") or "")


def live_entries():
    out = []
    for entry_id in all_ids():
        entry = read_entry(entry_id)
        if entry_live(entry):
            out.append((entry_id, entry))
    return out


def live_ids():
    return [i for i, _ in live_entries()]


def resolve_instance(explicit=None):
    """Pick the instance id: explicit > $JNODE_INSTANCE > autodetect > default."""
    if explicit:
        return _check_id(explicit)
    env = os.environ.get("JNODE_INSTANCE")
    if env:
        return _check_id(env)
    live = live_ids()
    if len(live) == 1:
        return live[0]
    if not live:
        return DEFAULT_INSTANCE
    raise InstanceError(
        "several live JNode instances: %s -- pick one with --instance or "
        "$JNODE_INSTANCE (jnode_paths.py --list shows them)" % ", ".join(live))


def resolve_paths(explicit=None):
    return Paths(resolve_instance(explicit))


def _legacy_sock_busy():
    """A hand-started VM may own the legacy default socket without ever
    registering; never hand 'default' out while that socket is actually
    in use. A mere leftover socket FILE (hard-killed VM) is not busy --
    checking /proc/net/unix avoids both the collision and the
    connect-probe that would steal the guest console's single client
    slot."""
    path = Paths(DEFAULT_INSTANCE).agent_sock
    if not os.path.exists(path):
        return False
    try:
        with open("/proc/net/unix") as f:
            for line in f:
                parts = line.split()
                if parts and parts[-1] == path:
                    return True
        return False
    except OSError:
        # no /proc: stay conservative
        return True


def alloc_instance(prefix="qemu"):
    """First free id: "default" when free, else <prefix>-1, -2, ...

    Serialized with a lock file so two concurrent launchers cannot pick
    the same id.
    """
    import fcntl
    os.makedirs(STATE_DIR, exist_ok=True)
    lock_path = os.path.join(STATE_DIR, ".alloc.lock")
    with open(lock_path, "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX)
        except OSError:
            pass
        prune_stale()
        live = set(live_ids())
        if DEFAULT_INSTANCE not in live and not _legacy_sock_busy():
            return DEFAULT_INSTANCE
        n = 1
        while True:
            candidate = "%s-%d" % (prefix, n)
            if candidate not in live:
                return candidate
            n += 1


def prune_stale():
    """Drop registry entries whose VM is gone. Returns removed ids."""
    removed = []
    for entry_id in all_ids():
        entry = read_entry(entry_id)
        if entry is None or not entry_live(entry):
            unregister(entry_id)
            removed.append(entry_id)
    return removed


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def _build_parser():
    p = argparse.ArgumentParser(description="JNode instance paths/registry")
    p.add_argument("--instance", help="instance id (default: $JNODE_INSTANCE "
                                      "or autodetect)")
    p.add_argument("--env", action="store_true",
                   help="print `export VAR=...` lines for the instance")
    p.add_argument("--field", help="print one field (agent_sock, kdb_sock, "
                                   "boot_log, monitor_sock, fifo, ...)")
    p.add_argument("--paths", action="store_true", help="dump paths as JSON")
    p.add_argument("--list", action="store_true", dest="list_instances",
                   help="list registered instances and their liveness")
    p.add_argument("--alloc", action="store_true",
                   help="print a free instance id")
    p.add_argument("--resolve", action="store_true",
                   help="print the single live instance id "
                        "(exit 1 none, exit 2 several)")
    p.add_argument("--prefix", default="qemu",
                   help="prefix for --alloc (default: qemu)")
    p.add_argument("--register", action="store_true")
    p.add_argument("--unregister", action="store_true")
    p.add_argument("--prune", action="store_true",
                   help="forget instances whose VM is gone")
    p.add_argument("--hypervisor", choices=("qemu", "vbox"))
    p.add_argument("--vm-name", dest="vm_name")
    p.add_argument("--vm-pid", dest="vm_pid", type=int)
    p.add_argument("--iso")
    p.add_argument("--state", default="ready")
    p.add_argument("--mkdir", action="store_true",
                   help="create every directory this instance needs")
    p.add_argument("--probe", action="store_true",
                   help="exit 0 when the instance's agent socket is present")
    p.add_argument("--is-live", action="store_true",
                   help="exit 0 when the instance's VM is still running")
    return p


def main(argv=None):
    args = _build_parser().parse_args(argv)

    if args.alloc:
        print(alloc_instance(args.prefix))
        return 0

    if args.resolve:
        live = live_ids()
        if len(live) == 1:
            print(live[0])
            return 0
        if not live:
            print("no live instances", file=sys.stderr)
            return 1
        print("several live instances: %s (jnode_paths.py --list)"
              % ", ".join(live), file=sys.stderr)
        return 2

    if args.prune:
        removed = prune_stale()
        print("pruned: %s" % (", ".join(removed) if removed else "nothing"))
        return 0

    if args.list_instances:
        ids = all_ids()
        if not ids:
            print("no registered instances (a hand-started VM still uses "
                  "the legacy 'default' paths)")
            return 0
        for entry_id in ids:
            entry = read_entry(entry_id) or {}
            live = entry_live(entry)
            owner = entry.get("vm") or entry.get("vm_pid") or "-"
            print("%-14s %-6s %-6s %-18s agent=%s" % (
                entry_id,
                entry.get("hypervisor", "?"),
                "live" if live else "dead",
                str(owner)[:18],
                entry.get("agent_sock", "-")))
        return 0

    if args.mkdir:
        try:
            paths = resolve_paths(args.instance)
        except InstanceError as e:
            print("ERROR: %s" % e, file=sys.stderr)
            return 2
        makedirs_for(paths)
        print(paths.root or "/tmp")
        return 0

    if args.probe:
        try:
            paths = resolve_paths(args.instance)
        except InstanceError as e:
            print("ERROR: %s" % e, file=sys.stderr)
            return 2
        ok = os.path.exists(paths.agent_sock)
        print("instance=%s agent_sock=%s present=%s"
              % (paths.id, paths.agent_sock, "yes" if ok else "no"))
        return 0 if ok else 1

    if args.is_live:
        # resolve like --probe/--field: explicit > $JNODE_INSTANCE >
        # the single live instance > default (never silently pick
        # 'default' while other instances are live)
        try:
            paths = resolve_paths(args.instance)
        except InstanceError as e:
            print("ERROR: %s" % e, file=sys.stderr)
            return 2
        live = entry_live(read_entry(paths.id))
        print("instance=%s %s" % (paths.id, "live" if live else "dead"))
        return 0 if live else 1

    if args.register:
        try:
            instance_id = _check_id(args.instance or
                                    os.environ.get("JNODE_INSTANCE") or
                                    DEFAULT_INSTANCE)
        except InstanceError as e:
            print("ERROR: %s" % e, file=sys.stderr)
            return 2
        paths = Paths(instance_id)
        entry = read_entry(instance_id) or {"id": instance_id}
        entry.update({
            "id": instance_id,
            "hypervisor": args.hypervisor or entry.get("hypervisor"),
            "vm": args.vm_name or entry.get("vm"),
            "vm_pid": args.vm_pid if args.vm_pid is not None
                      else entry.get("vm_pid"),
            "pid": args.vm_pid if args.vm_pid is not None
                   else entry.get("pid"),
            "iso": args.iso or entry.get("iso"),
            "state": args.state,
            "agent_sock": paths.agent_sock,
            "kdb_sock": paths.kdb_sock,
            "boot_log": paths.boot_log,
            "monitor_sock": paths.monitor_sock,
            "mux_dir": paths.mux_dir,
            "created": entry.get("created", int(time.time())),
        })
        write_entry(entry)
        print("registered %s (%s)" % (instance_id, args.state))
        return 0

    if args.unregister:
        instance_id = args.instance or os.environ.get("JNODE_INSTANCE")
        if not instance_id:
            print("ERROR: --unregister needs --instance", file=sys.stderr)
            return 2
        print("unregistered %s" % instance_id if unregister(instance_id)
              else "no registry entry for %s" % instance_id)
        return 0

    try:
        paths = resolve_paths(args.instance)
    except InstanceError as e:
        print("ERROR: %s" % e, file=sys.stderr)
        return 2

    if args.field:
        value = getattr(paths, args.field, None)
        if value is None:
            print("ERROR: unknown field %r (known: %s)"
                  % (args.field, ", ".join(sorted(paths.as_dict()))),
                  file=sys.stderr)
            return 2
        print(value)
        return 0
    if args.paths:
        print(json.dumps(paths.as_dict(), indent=1, sort_keys=True))
        return 0
    if args.env:
        sys.stdout.write(paths.env_exports())
        return 0

    _build_parser().print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())

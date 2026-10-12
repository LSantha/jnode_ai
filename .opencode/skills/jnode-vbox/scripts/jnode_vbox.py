#!/usr/bin/env python3
"""
VirtualBox lifecycle for JNode VMs (jnode-vbox skill).

Wires both UARTs into the jnode-serial instance model (jnode_paths.py):

  UART1 0x3F8 -> server pipe at <kdb_sock>     (KDB)
  UART2 0x2F8 -> server pipe at <agent_sock>   (agent console)

so the shared serial_cmd.py / kdb_mux.py / jnode_kdb_cmd.py work
unchanged against VirtualBox and QEMU alike. Several VMs can run side by
side as long as each uses its own --instance id (one VM per instance:
the pipes are owned by whichever VM was configured last).

Usage:
    jnode_vbox.py config  --name VM [--iso FILE] [--instance ID] [--memory MB]
    jnode_vbox.py start   --name VM [--instance ID] [--entry N] [--wait-ready SECS]
    jnode_vbox.py stop    --name VM [--instance ID]
    jnode_vbox.py status  --name VM [--instance ID]

config is idempotent and only touches VM settings (never disks).
start boots headless; --entry N picks a GRUB 0.97 menu entry through the
verified vbox_grub_pick.py (scancodes + screenshot). stop does
acpipowerbutton, then poweroff as a fallback.

For the legacy "default" instance the pipes stay at the historical
/tmp/jnode.kdb and /tmp/jnode.serial2.
"""

import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "jnode-serial", "scripts"))
import jnode_paths as jp  # noqa: E402

SERIAL_CMD = os.path.join(HERE, "..", "..", "jnode-serial", "scripts",
                          "serial_cmd.py")
PICKER = os.path.join(HERE, "vbox_grub_pick.py")


def vbox(*args, check=True):
    try:
        r = subprocess.run(["VBoxManage"] + [str(a) for a in args],
                           capture_output=True, text=True)
    except FileNotFoundError:
        raise RuntimeError("VBoxManage not found -- install VirtualBox "
                           "or run this on a host that has it")
    if check and r.returncode != 0:
        raise RuntimeError("VBoxManage %s failed: %s"
                           % (" ".join(str(a) for a in args),
                              (r.stderr or r.stdout or "").strip()))
    return (r.returncode, (r.stdout or "") + (r.stderr or ""))


def info(name):
    rc, out = vbox("showvminfo", name, "--machinereadable", check=False)
    if rc != 0:
        return {}
    d = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            d[k.strip('"')] = v.strip().strip('"')
    return d


def is_running(name):
    return info(name).get("VMState") == "running"


def register(paths, name, state):
    entry = jp.read_entry(paths.id) or {"id": paths.id}
    # the registry key is "vm": jnode_paths.entry_live(), --list/--is-live
    # and serial_mux.configure() all read entry["vm"], so anything else
    # makes a running VM read back as dead
    entry.update(hypervisor="vbox", vm=name, state=state,
                 agent_sock=paths.agent_sock, boot_log=paths.boot_log,
                 kdb_sock=paths.kdb_sock)
    jp.write_entry(entry)


def refuse_live_instance(paths, name=None):
    """Same guard start_qemu.sh has: never bind config/start to an
    instance whose VM is still alive, or autodetect would overwrite its
    registry entry and hand its pipes to this VM."""
    entry = jp.read_entry(paths.id)
    if jp.entry_live(entry):
        vm = (entry or {}).get("vm") or "unknown"
        hyp = (entry or {}).get("hypervisor") or "unknown"
        print("ERROR: instance '%s' already has a live %s VM (%s)"
              % (paths.id, hyp, vm))
        if name is not None and name == vm:
            if hyp == "vbox":
                print("       stop that VM first: %s stop --name %s "
                      "--instance %s" % (sys.argv[0], vm, paths.id))
            else:
                # a QEMU instance: jnode-vbox stop only talks to VBoxManage
                print("       stop it first: start_qemu.sh --stop "
                      "--instance %s" % paths.id)
        else:
            print("       stop it (jnode_paths.py --list) or pick another "
                  "id (--instance)")
        return True
    return False


def cmd_config(a):
    paths = jp.resolve_paths(a.instance)
    # is_running first so the self-conflict case (same VM, same
    # instance) gets the VM-naming message below, not the generic guard
    if is_running(a.name):
        print("ERROR: %s is running; stop it before reconfiguring" % a.name)
        return 1
    if refuse_live_instance(paths, name=a.name):
        return 1
    d = info(a.name)
    if not d:
        print("creating VM %s" % a.name)
        vbox("createvm", "--name", a.name, "--ostype", "Other", "--register")
        d = info(a.name)
    common = ["modifyvm", a.name,
              "--memory", str(a.memory),
              "--acpi", "on", "--ioapic", "on",
              "--boot1", "dvd", "--boot2", "disk",
              "--boot3", "none", "--boot4", "none",
              "--uart1", "0x3F8", "4", "--uartmode1", "server", paths.kdb_sock,
              "--uart2", "0x2F8", "3", "--uartmode2", "server",
              paths.agent_sock]
    vbox(*common)
    if a.iso:
        d = info(a.name)
        ctls = [v for k, v in d.items()
                if k.startswith("storagecontrollername")]
        if "IDE" in ctls:
            ctl, slot = "IDE", ["--port", "0", "--device", "1"]
        elif "SATA" in ctls:
            ctl, slot = "SATA", ["--port", "1"]
        else:
            # a fresh createvm has no controller at all
            vbox("storagectl", a.name, "--name", "IDE", "--add", "ide",
                 "--controller", "PIIX4")
            ctl, slot = "IDE", ["--port", "0", "--device", "1"]
        rc, out = vbox("storageattach", a.name, "--storagectl", ctl,
                       *slot, "--type", "dvddrive",
                       "--medium", a.iso, check=False)
        if rc != 0:
            print("WARNING: could not attach %s to %s: %s"
                  % (a.iso, ctl, out.strip()))
        else:
            print("attached %s on %s %s" % (a.iso, ctl, " ".join(slot)))
    jp.makedirs_for(paths)
    register(paths, a.name, "configured")
    print("instance %s -> %s" % (paths.id, a.name))
    print("  kdb pipe:   %s (UART1 0x3F8)" % paths.kdb_sock)
    print("  agent pipe: %s (UART2 0x2F8)" % paths.agent_sock)
    return 0


def cmd_start(a):
    paths = jp.resolve_paths(a.instance)
    # is_running first so the self-conflict case (same VM, same
    # instance) gets the VM-naming message below, not the generic guard
    if is_running(a.name):
        print("ERROR: %s already running" % a.name)
        return 1
    if refuse_live_instance(paths, name=a.name):
        return 1
    if not info(a.name):
        print("ERROR: VM %s does not exist (run config first)" % a.name)
        return 1
    jp.makedirs_for(paths)
    # stale pipe files make VirtualBox refuse to open the UART
    for p in (paths.kdb_sock, paths.agent_sock):
        if os.path.exists(p):
            os.remove(p)
    register(paths, a.name, "starting")
    vbox("startvm", a.name, "--type", "headless")
    print("VM %s started (instance %s)" % (a.name, paths.id))
    # entry 0 (the ISO default) skips the picker, exactly like
    # start_qemu.sh's `if [ "$ENTRY" != "0" ]`; choices= rejects
    # out-of-range values up front
    if a.entry:
        rc = subprocess.call([sys.executable, PICKER, "--vm", a.name,
                              "--entry", str(a.entry)])
        if rc != 0:
            print("WARNING: GRUB entry selection failed (rc=%d); "
                  "the VM may boot the default entry" % rc)
    if a.wait_ready:
        print("waiting up to %ss for the agent console..." % a.wait_ready)
        rc = subprocess.call([sys.executable, SERIAL_CMD, "--instance",
                              paths.id, "--wait", str(a.wait_ready), "date"])
        if rc != 0:
            print("ERROR: agent console not ready after %ss" % a.wait_ready)
            return 1
        print("agent console is up")
    register(paths, a.name, "ready")
    print("next: python3 %s --instance %s --wait 300 'date'"
          % (SERIAL_CMD, paths.id))
    return 0


def stop_mux(paths):
    # tear the muxes down too, or they idle forever with a dead UART
    # pipe (same F2 as in start_qemu.sh --stop); paths.mux_pid covers
    # both the legacy and the namespaced layout. safe_kill_pid checks
    # the /proc cmdline, so a stale pid file cannot SIGTERM a recycled
    # (unrelated) pid.
    for pidfile, needle in ((paths.mux_pid, "serial_mux.py"),
                            (paths.kdb_mux_pid, "kdb_mux.py")):
        try:
            with open(pidfile) as f:
                pid = int(f.read().strip())
        except (OSError, ValueError):
            continue
        jp.safe_kill_pid(pid, needle)


def cmd_stop(a):
    paths = jp.resolve_paths(a.instance)
    # a wrong --name (typo, or the "other VM" of a two-VM setup) must
    # not unregister a different VM's entry and kill its muxes; and a
    # non-vbox instance must never be stopped through the vbox tool --
    # is_running() only queries VBoxManage, so a live QEMU VM would
    # fall through to "not running" and get unregistered anyway
    entry = jp.read_entry(paths.id)
    if entry and entry.get("hypervisor") not in (None, "vbox"):
        print("ERROR: instance '%s' is hypervisor=%s"
              % (paths.id, entry.get("hypervisor")))
        print("       stop it with: start_qemu.sh --stop --instance %s"
              % paths.id)
        return 1
    if entry and entry.get("vm") and entry["vm"] != a.name:
        print("ERROR: instance '%s' is bound to VM '%s', not '%s'"
              % (paths.id, entry["vm"], a.name))
        print("       stop it with: %s stop --name %s --instance %s"
              % (sys.argv[0], entry["vm"], paths.id))
        return 1
    if not is_running(a.name):
        print("%s is not running" % a.name)
        jp.unregister(paths.id)
        stop_mux(paths)
        return 0
    vbox("controlvm", a.name, "acpipowerbutton")
    for _ in range(15):
        if not is_running(a.name):
            break
        time.sleep(1)
    if is_running(a.name):
        print("ACPI poweroff timed out, forcing poweroff")
        vbox("controlvm", a.name, "poweroff")
        for _ in range(5):
            if not is_running(a.name):
                break
            time.sleep(1)
    jp.unregister(paths.id)
    stop_mux(paths)
    print("stopped %s (instance %s)" % (a.name, paths.id))
    return 0


def cmd_status(a):
    paths = jp.resolve_paths(a.instance)
    d = info(a.name)
    if not d:
        print("VM %s does not exist" % a.name)
        return 1
    print("vm:        %s (%s)" % (a.name, d.get("VMState")))
    print("instance:  %s" % paths.id)
    entry = jp.read_entry(paths.id)
    if entry and entry.get("vm") and entry["vm"] != a.name:
        print("note:      instance '%s' is bound to VM '%s'"
              % (paths.id, entry["vm"]))
    print("kdb pipe:  %s (%s)" % (paths.kdb_sock, d.get("uartmode1")))
    print("agent pipe:%s (%s)" % (paths.agent_sock, d.get("uartmode2")))
    if entry:
        print("registry:  hypervisor=%s state=%s"
              % (entry.get("hypervisor"), entry.get("state")))
    else:
        print("registry:  (not registered)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("config", "start", "stop", "status"):
        p = sub.add_parser(name)
        p.add_argument("--name", default="JNode")
        p.add_argument("--instance", default=None)
        if name == "config":
            p.add_argument("--iso", default=None)
            p.add_argument("--memory", type=int, default=2048)
        if name == "start":
            p.add_argument("--entry", type=int, default=0,
                           choices=range(6),
                           help="GRUB menu entry (0-5) of a standard ISO; "
                                "0 = boot the ISO default, picker only "
                                "runs for 1-5")
            p.add_argument("--wait-ready", type=int, default=0, metavar="SECS",
                           help="block until the agent console answers")
    a = ap.parse_args()
    try:
        rc = {"config": cmd_config, "start": cmd_start,
              "stop": cmd_stop, "status": cmd_status}[a.cmd](a)
    except (jp.InstanceError, RuntimeError) as e:
        print("ERROR: %s" % e)
        rc = 2
    sys.exit(rc or 0)


if __name__ == "__main__":
    main()

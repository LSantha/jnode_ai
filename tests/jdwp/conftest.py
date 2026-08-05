"""
conftest.py - Shared fixtures for JDWP integration tests.

This module provides pytest fixtures that manage the full lifecycle of
a JNode VM running in VirtualBox, the JDWP listener, and the jdb session.

Fixtures (in dependency order):
    jnode_vm          - Boot JNode in VirtualBox, wait for serial console
    serial_cmd        - Helper to send commands via serial console
    network_configured - Configure bridged networking on JNode
    jdwp_listener     - Start JDWP listener on JNode via serial console
    jdb_session       - Start jdb_agent.py daemon and connect to JDWP
    jdb               - Helper to send jdb commands and get output
    jdb_with_locals   - jdb helper with class cache pre-populated

Usage:
    pytest tests/jdwp/ -v
    pytest tests/jdwp/ -m "not slow"
    pytest tests/jdwp/test_vm_commands.py -v
"""
import os
import re
import sys
import time
import socket
import subprocess
import signal
import pytest

# ---------------------------------------------------------------------------
# Paths and configuration
# ---------------------------------------------------------------------------

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SKILLS_DIR = os.path.abspath(os.path.expanduser(
    "~/.config/opencode/skills"
))

# Try multiple paths for jnode_agent_cmd.py (jnode-serial or jnode-interact skill)
_JNODE_AGENT_CANDIDATES = [
    os.path.join(SKILLS_DIR, "jnode-serial", "scripts", "jnode_agent_cmd.py"),
    os.path.join(SKILLS_DIR, "jnode-interact", "scripts", "jnode_agent_cmd.py"),
    os.path.join(ROOT_DIR, ".opencode/skills/jnode-interact/scripts/jnode_agent_cmd.py"),
]
JNODE_AGENT_CMD = next(
    (p for p in _JNODE_AGENT_CANDIDATES if os.path.exists(p)),
    _JNODE_AGENT_CANDIDATES[0],  # fallback to first candidate
)

JDB_AGENT = os.path.join(
    SKILLS_DIR, "jnode-jdwp", "scripts", "jdb_agent.py"
)

ISO_PATH = os.path.join(ROOT_DIR, "all", "build", "cdroms", "jnode-x86-lite.iso")

# VirtualBox VM configuration
VM_NAME = "JNode"

# Network configuration (known-good lab setup from jnode-jdwp skill)
# These can be overridden via environment variables
GUEST_IP = os.environ.get("JNODE_GUEST_IP", "192.168.1.10")
GATEWAY_IP = os.environ.get("JNODE_GATEWAY_IP", "192.168.1.1")
NETMASK = "255.255.255.0"
JDWP_PORT = int(os.environ.get("JNODE_JDWP_PORT", "2000"))

# Serial console socket
SERIAL_SOCKET = "/tmp/jnode_com2"
SERIAL_PIPE = "/tmp/jnode.serial2"

# Timeouts
BOOT_TIMEOUT = 120          # seconds to wait for JNode to boot
SERIAL_READY_TIMEOUT = 60   # seconds to wait for serial console
JDWP_START_TIMEOUT = 15     # seconds to wait for JDWP listener
JDB_CONNECT_TIMEOUT = 90   # seconds for jdb to connect (slow on JNode)

# JDK path for jdb binary (can be overridden via environment variable)
# jdb_agent.py spawns `jdb` which must be on PATH
_JDK_CANDIDATES = [
    "/media/ext4/prg/jdk1.6.0_45/bin",
    "/media/ext4/prg/jdk1.8.0_271/bin",
    "/usr/lib/jvm/default/bin",
]
JDB_JDK_PATH = os.environ.get("JDB_JDK_PATH") or next(
    (p for p in _JDK_CANDIDATES if os.path.exists(p)),
    None,
)

# Build environment with JDK on PATH for jdb_agent.py
def _build_env():
    """Build environment dict with JDK bin on PATH for jdb_agent.py."""
    env = os.environ.copy()
    if JDB_JDK_PATH:
        env["PATH"] = JDB_JDK_PATH + os.pathsep + env.get("PATH", "")
    return env


# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

def _run_serial_cmd(cmd, timeout=10):
    """Run a command via the serial console agent script."""
    if not os.path.exists(JNODE_AGENT_CMD):
        pytest.fail(
            f"jnode_agent_cmd.py not found at {JNODE_AGENT_CMD}. "
            "Ensure the jnode-serial or jnode-interact skill is installed."
        )
    result = subprocess.run(
        [sys.executable, JNODE_AGENT_CMD, cmd],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result.stdout.strip()


def _run_jdb_agent_cmd(cmd, timeout=120):
    """Run a jdb command via the jdb_agent.py daemon."""
    if not os.path.exists(JDB_AGENT):
        pytest.fail(
            f"jdb_agent.py not found at {JDB_AGENT}. "
            "Ensure the jnode-jdwp skill is installed."
        )
    result = subprocess.run(
        [sys.executable, JDB_AGENT, "cmd", cmd],
        capture_output=True,
        text=True,
        timeout=timeout,
        env=_build_env(),
    )
    output = result.stdout.strip()
    if not output and result.returncode != 0:
        raise RuntimeError(
            f"jdb agent failed (rc={result.returncode}): {result.stderr.strip()}"
        )
    return output


SOCK_PATH = "/tmp/jdb_agent.sock"


def _kill_jdb_agent():
    """Stop the jdb_agent.py daemon if running, and clean up stale sockets."""
    # Try to stop gracefully
    if os.path.exists(JDB_AGENT):
        try:
            subprocess.run(
                [sys.executable, JDB_AGENT, "stop"],
                capture_output=True,
                text=True,
                timeout=10,
                env=_build_env(),
            )
        except Exception:
            pass
    # Always remove stale socket file
    if os.path.exists(SOCK_PATH):
        try:
            os.unlink(SOCK_PATH)
        except OSError:
            pass
    # Kill any lingering daemon processes
    try:
        subprocess.run(
            ["pkill", "-9", "-f", "jdb_agent.py _daemon"],
            capture_output=True, timeout=5,
        )
    except Exception:
        pass


def _vboxmanage(*args):
    """Run a vboxmanage command."""
    result = subprocess.run(
        ["vboxmanage"] + list(args),
        capture_output=True,
        text=True,
        timeout=30,
    )
    return result


def _is_vm_running():
    """Check if the JNode VM is running in VirtualBox."""
    result = _vboxmanage("list", "runningvms")
    return VM_NAME in result.stdout


def _start_vm():
    """Start the JNode VM in VirtualBox headless mode."""
    if _is_vm_running():
        return

    # Attach the ISO if not already attached
    _vboxmanage(
        "storageattach", VM_NAME,
        "--storagectl", "IDE Controller",
        "--port", "0", "--device", "0",
        "--type", "dvddrive",
        "--medium", ISO_PATH,
    )

    # Start headless
    result = _vboxmanage("startvm", VM_NAME, "--type", "headless")
    if result.returncode != 0:
        pytest.fail(f"Failed to start VM: {result.stderr}")


def _stop_vm():
    """Stop the JNode VM in VirtualBox."""
    if _is_vm_running():
        _vboxmanage("controlvm", VM_NAME, "poweroff")
        time.sleep(5)


def _select_grub_entry(entry=1):
    """
    Select a GRUB boot menu entry by sending keyboard scancodes.

    GRUB is VGA-only, so we use vboxmanage keyboardputscancode to
    stop the timeout, navigate to the desired entry, and boot.

    Entry 1 = "JNode (all plugins)" which includes the LANCE driver.
    """
    # SPACE (stop GRUB timeout)
    _vboxmanage("controlvm", VM_NAME, "keyboardputscancode", "39", "B9")
    time.sleep(1)

    # DOWN (entry 1 = all plugins)
    for _ in range(entry):
        _vboxmanage("controlvm", VM_NAME, "keyboardputscancode", "50", "D0")
        time.sleep(0.5)

    # ENTER (boot)
    _vboxmanage("controlvm", VM_NAME, "keyboardputscancode", "1C", "9C")
    time.sleep(1)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def jnode_vm():
    """
    Boot JNode in VirtualBox and wait for the serial console to be ready.

    This fixture:
    1. Stops any existing jdb_agent daemon
    2. Starts the JNode VM in VirtualBox (headless)
    3. Selects GRUB entry 1 (all plugins, includes LANCE driver)
    4. Waits for the serial console to become available
    5. Verifies the shell is responsive

    Teardown: Stops the VM and jdb_agent daemon.
    """
    # Clean up any existing jdb_agent daemon
    _kill_jdb_agent()

    # Verify ISO exists
    if not os.path.exists(ISO_PATH):
        pytest.fail(
            f"JNode ISO not found at {ISO_PATH}. "
            "Build it first: sh build.sh cd-x86-lite"
        )

    # Start the VM (or use existing running VM)
    vm_was_running = _is_vm_running()
    if not vm_was_running:
        _start_vm()
        # Select GRUB entry 1 (all plugins)
        time.sleep(4)  # Wait for GRUB menu to appear
        _select_grub_entry(1)

    # Wait for serial console to be ready
    start_time = time.time()
    while time.time() - start_time < BOOT_TIMEOUT:
        if os.path.exists(SERIAL_SOCKET) or os.path.exists(SERIAL_PIPE):
            # Create symlink if needed
            if not os.path.exists(SERIAL_SOCKET) and os.path.exists(SERIAL_PIPE):
                try:
                    os.symlink(SERIAL_PIPE, SERIAL_SOCKET)
                except OSError:
                    pass

            # Verify the shell is responsive
            try:
                output = _run_serial_cmd("date", timeout=10)
                if output and "JNODE_AGENT_READY" not in output:
                    # Shell is responsive
                    break
            except Exception:
                pass
        time.sleep(2)
    else:
        pytest.fail(
            f"JNode did not boot within {BOOT_TIMEOUT}s. "
            "Check the VM console and serial log."
        )

    yield

    # Teardown: stop the jdb_agent (only stop VM if we started it)
    _kill_jdb_agent()
    if not vm_was_running:
        _stop_vm()


@pytest.fixture(scope="session")
def serial_cmd(jnode_vm):
    """
    Provide a helper function to send commands via the serial console.

    Usage in tests:
        def test_something(serial_cmd):
            output = serial_cmd("date")
            assert "202" in output
    """
    def _send(cmd, timeout=10):
        return _run_serial_cmd(cmd, timeout=timeout)
    return _send


@pytest.fixture(scope="session")
def network_configured(serial_cmd):
    """
    Configure networking on the JNode VM.

    If the VM already has an IP address, use it. Otherwise, set up the
    bridged network configuration from the jnode-jdwp skill:
    - ifconfig eth-pci(0,3,0) <ip> <netmask>
    - route --add 0.0.0.0 eth-pci(0,3,0) <gateway>

    Verifies connectivity by pinging the gateway.
    """
    # Check if network is already configured
    ifconfig_output = serial_cmd("ifconfig", timeout=10)

    # Try to detect existing IP from ifconfig output (use last IP on eth interface,
    # as multiple IPs may be stacked from previous configurations)
    import re as _re
    eth_block = ifconfig_output.split("loopback")[0] if "loopback" in ifconfig_output else ifconfig_output
    ip_matches = _re.findall(r'(\d+\.\d+\.\d+\.\d+)', eth_block)
    # Filter out null/0.0.0.0 and take the last one (most recently configured)
    valid_ips = [ip for ip in ip_matches if ip != "0.0.0.0" and ip != "null"]
    if valid_ips:
        detected_ip = valid_ips[-1]
        # Network is already configured, use the detected IP
        global GUEST_IP
        GUEST_IP = detected_ip
        # Verify connectivity (JNode ping doesn't support -c/-W flags)
        ping_output = serial_cmd(f"ping {GATEWAY_IP}", timeout=15)
        if "packets received" not in ping_output:
            # Network may need route configuration
            serial_cmd(f"route --add 0.0.0.0 eth-pci(0,3,0) {GATEWAY_IP}")
            time.sleep(2)
            ping_output = serial_cmd(f"ping {GATEWAY_IP}", timeout=15)
            if "packets received" not in ping_output:
                pytest.fail(
                    f"Network connectivity check failed. Ping output: {ping_output}"
                )
        yield
        return

    # Network not configured, set it up
    serial_cmd(f"ifconfig eth-pci(0,3,0) {GUEST_IP} {NETMASK}")
    serial_cmd(f"route --add 0.0.0.0 eth-pci(0,3,0) {GATEWAY_IP}")

    # Wait for network to settle
    time.sleep(3)

    # Verify network is up (JNode ping doesn't support -c/-W flags)
    ping_output = serial_cmd(f"ping {GATEWAY_IP}", timeout=15)
    if "packets received" not in ping_output:
        pytest.fail(
            f"Network configuration failed. Ping output: {ping_output}"
        )

    yield

    # No teardown needed for network config


@pytest.fixture(scope="session")
def jdwp_listener(network_configured, serial_cmd):
    """
    Start the JDWP listener on the JNode VM.

    Uses the `debug` command via serial console to start the listener.
    The listener runs as a daemon and stays alive for the session.

    Teardown: Stops the JDWP listener.
    """
    # Check if listener is already running
    output = serial_cmd(f"debug -p {JDWP_PORT}", timeout=10)
    if "already running" in output:
        # Listener is already running, that's fine
        pass
    elif "JDWP listener started" in output:
        # Listener was started successfully
        pass
    else:
        pytest.fail(
            f"Failed to start JDWP listener. Output: {output}"
        )

    # Wait for the listener to be ready
    time.sleep(2)

    yield JDWP_PORT

    # Teardown: stop the JDWP listener
    serial_cmd("debug --stop", timeout=10)


@pytest.fixture(scope="session")
def jdb_session(jdwp_listener):
    """
    Start the jdb_agent.py daemon and connect to the JDWP listener.

    This fixture:
    1. Starts the jdb_agent.py daemon
    2. Waits for jdb to connect to the JDWP listener
    3. Provides the daemon for the test session

    Teardown: Stops the jdb_agent.py daemon.
    """
    # Check if daemon is already running
    if os.path.exists("/tmp/jdb_agent.sock"):
        # Daemon is already running, try to use it
        try:
            test_output = _run_jdb_agent_cmd("version", timeout=30)
            if test_output:
                yield
                return
        except Exception:
            pass
        # Daemon is stale, stop it
        _kill_jdb_agent()
        time.sleep(2)

    # Start the daemon
    target = f"{GUEST_IP}:{JDWP_PORT}"
    result = subprocess.run(
        [sys.executable, JDB_AGENT, "start", target],
        capture_output=True,
        text=True,
        timeout=120,
        env=_build_env(),
    )
    if "jdb session started" not in result.stdout and "already running" not in result.stdout:
        pytest.fail(
            f"Failed to start jdb session. stdout: {result.stdout}, "
            f"stderr: {result.stderr}"
        )

    # Wait for jdb to be ready (it's slow on JNode)
    time.sleep(5)

    yield

    # Teardown: stop the daemon
    _kill_jdb_agent()


@pytest.fixture
def jdb(jdb_session):
    """
    Provide a helper function to send jdb commands and get output.

    This fixture is function-scoped but does NOT check daemon health
    before each test. Health checks spawn a subprocess and send an extra
    JDWP command per test, which adds ~52 extra round-trips and can
    crash the serial console after ~30 tests. Instead, we only restart
    the daemon if a command actually fails.

    Usage in tests:
        def test_version(jdb):
            output = jdb("version")
            assert "JNode" in output or "jdwp" in output.lower()
    """
    def _send(cmd, timeout=120):
        """Send a jdb command, restarting daemon only on failure."""
        try:
            output = _run_jdb_agent_cmd(cmd, timeout=timeout)
            if not output:
                raise Exception("Empty output from daemon")
            return output
        except Exception:
            # Daemon may have died, restart it
            _kill_jdb_agent()
            time.sleep(3)
            target = f"{GUEST_IP}:{JDWP_PORT}"
            result = subprocess.run(
                [sys.executable, JDB_AGENT, "start", target],
                capture_output=True,
                text=True,
                timeout=120,
                env=_build_env(),
            )
            if "jdb session started" not in result.stdout and "already running" not in result.stdout:
                pytest.fail(
                    f"Failed to restart jdb session. stdout: {result.stdout}, "
                    f"stderr: {result.stderr}"
                )
            # Wait for jdb to fully connect before retrying
            time.sleep(10)
            # Probe daemon health before retrying
            for _ in range(3):
                try:
                    probe = _run_jdb_agent_cmd("version", timeout=30)
                    if probe:
                        break
                except Exception:
                    time.sleep(3)
            # Retry the command
            return _run_jdb_agent_cmd(cmd, timeout=timeout)
    return _send


@pytest.fixture(scope="session")
def jdb_with_locals(jdb_session):
    """
    Provide a jdb helper that has already triggered the class cache
    by running `locals` on a selected thread.

    This is needed for static field access tests, as documented in the
    jnode-jdwp skill: jdb's expression parser cannot resolve class names
    until `VirtualMachine.allClasses()` is called, which happens when
    `locals` or `where` is run.

    This fixture is session-scoped so the class cache is only triggered
    once, avoiding repeated `threads` calls that can cause timeouts.
    The returned helper function includes daemon health checking.
    """
    # Select a thread and trigger the class cache
    threads_output = _run_jdb_agent_cmd("threads", timeout=60)
    # Extract a thread ID from the output
    # Thread IDs look like 0x1d, 0x1c, etc.
    thread_match = re.search(r'(0x[0-9a-f]+)', threads_output)
    if thread_match:
        thread_id = thread_match.group(1)
        _run_jdb_agent_cmd(f"thread {thread_id}", timeout=30)
        _run_jdb_agent_cmd("locals", timeout=60)  # This triggers allClasses()

    def _send(cmd, timeout=120):
        """Send a jdb command, restarting daemon if needed."""
        try:
            output = _run_jdb_agent_cmd(cmd, timeout=timeout)
            if not output:
                raise Exception("Empty output from daemon")
            return output
        except Exception:
            # Daemon may have died, restart it
            _kill_jdb_agent()
            time.sleep(2)
            target = f"{GUEST_IP}:{JDWP_PORT}"
            result = subprocess.run(
                [sys.executable, JDB_AGENT, "start", target],
                capture_output=True,
                text=True,
                timeout=120,
                env=_build_env(),
            )
            if "jdb session started" not in result.stdout and "already running" not in result.stdout:
                pytest.fail(
                    f"Failed to restart jdb session. stdout: {result.stdout}, "
                    f"stderr: {result.stderr}"
                )
            time.sleep(5)
            # Retry the command
            return _run_jdb_agent_cmd(cmd, timeout=timeout)

    return _send

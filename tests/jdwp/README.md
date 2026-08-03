# JDWP Integration Tests

Integration tests for the JNode JDWP (Java Debug Wire Protocol) backend,
driven by `jdb` via the `jdb_agent.py` and `jnode_agent_cmd.py` Python scripts.

## Prerequisites

1. **JNode ISO**: Build the lite ISO first:
   ```bash
   sh build.sh cd-x86-lite
   ```
   Artifact: `all/build/cdroms/jnode-x86-lite.iso`

2. **Python 3** with `pytest` and `pexpect`:
   ```bash
   pip install -r tests/jdwp/requirements.txt
   ```

3. **VirtualBox** with a VM named `JNode` configured with:
   - Serial port 2: I/O 0x2f8, IRQ 3, pipe server `/tmp/jnode.serial2`
   - Bridged Adapter (for JDWP TCP connectivity)
   - ISO attached to IDE Controller port 0, device 0

4. **Skills installed** (from `~/.config/opencode/skills/`):
   - `jnode-interact` (provides `jnode_agent_cmd.py`)
   - `jnode-jdwp` (provides `jdb_agent.py`)

5. **JDK** with `jdb` on the host PATH (JDK 1.6–1.8 recommended)

## Running Tests

### All tests
```bash
cd /path/to/jnode_ai_oc
python3 -m pytest tests/jdwp/ -v
```

### Smoke tests only (fast, basic commands)
```bash
python3 -m pytest tests/jdwp/ -v -m "smoke"
```

### Skip slow tests
```bash
python3 -m pytest tests/jdwp/ -v -m "not slow"
```

### Specific test file
```bash
python3 -m pytest tests/jdwp/test_vm_commands.py -v
```

### With HTML report
```bash
python3 -m pytest tests/jdwp/ -v --html=tests/jdwp/report.html
```

## Test Categories

| Marker | Description |
|--------|-------------|
| `smoke` | Basic smoke tests that should always pass |
| `integration` | Full integration tests requiring VM + JDWP + jdb |
| `slow` | Tests that take a long time (e.g., `classes` command) |
| `network` | Tests requiring network connectivity |
| `requires_vm` | Tests requiring a running JNode VM |
| `requires_jdwp` | Tests requiring a JDWP listener |
| `requires_jdb` | Tests requiring a jdb connection |

## Test Structure

```
tests/jdwp/
├── conftest.py              # Shared fixtures (VM boot, JDWP, jdb)
├── pytest.ini               # pytest configuration
├── requirements.txt         # Python dependencies
├── README.md                # This file
├── test_vm_commands.py      # VM-level commands (version, classpath, etc.)
├── test_thread_commands.py  # Thread commands (threads, suspend, resume)
├── test_stack_commands.py   # Stack frame commands (where, locals, up/down)
├── test_class_commands.py   # Class commands (classes, methods, fields)
├── test_field_commands.py   # Field access (print, dump, set)
└── test_unsupported_commands.py  # Verify unsupported commands fail gracefully
```

## Fixtures

| Fixture | Scope | Description |
|---------|-------|-------------|
| `jnode_vm` | session | Boots JNode in VirtualBox, waits for serial console |
| `serial_cmd` | session | Helper to send commands via serial console |
| `network_configured` | session | Configures bridged networking on JNode |
| `jdwp_listener` | session | Starts JDWP listener via `debug -p 2000` |
| `jdb_session` | session | Starts jdb_agent.py daemon, connects to JDWP |
| `jdb` | function | Helper to send jdb commands and get output |
| `jdb_with_locals` | function | jdb helper with class cache pre-populated |

## Notes

- **jdb init is slow** on JNode: budget ~90s for the first response when `classes` is requested.
- **Suspend/resume required**: Stack inspection (`where`, `locals`, `up`/`down`, `print`) requires `suspend` first.
- **Class cache trigger**: jdb's expression parser cannot resolve class names until `VirtualMachine.allClasses()` is called. The `jdb_with_locals` fixture handles this by running `locals` on a selected thread.
- **Non-current-thread stack walking**: Not implemented on JNode. `where <threadid>` for non-current threads returns no frames.
- **Step/breakpoint debugging**: Not implemented. `step`, `next`, `stop in`, `stop at` are accepted but never trigger.

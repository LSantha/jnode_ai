# JDWP Test Suite — Agent Instructions

## Quick Start

```bash
# Run all tests (requires running JNode VM with JDWP listener)
python3 -m pytest tests/jdwp/ -v

# Run specific test file
python3 -m pytest tests/jdwp/test_method_invocation.py -v
```

## Prerequisites

- JNode VM running in VirtualBox with serial pipe `/tmp/jnode.serial2`
- JDWP listener started via serial: `debug -p 2000`
- Network configured: `ifconfig eth-pci(0,3,0) 192.168.1.10 255.255.255.0` + `route --add default eth-pci(0,3,0) 192.168.1.1`
- Host has `jdb` (JDK 1.6–1.8)
- Python deps: `pip install -r tests/jdwp/requirements.txt`

## Test Files

| File | Tests | What it covers |
|------|-------|----------------|
| `test_vm_commands.py` | 5 | version, classpath, connectors, use, exclude |
| `test_thread_commands.py` | 6 | threads, threadgroups, thread switch, suspend/resume, suspend_global, interrupt (skipped) |
| `test_stack_commands.py` | 6 | where, where_all, wherei, locals, up/down, where_non_current_thread |
| `test_class_commands.py` | 5 | classes, class_details, methods, fields, class_load_by_name |
| `test_field_commands.py` | 11 | static fields (int, max_int, Integer, object, err), dump, expression evaluation (arithmetic, string_concat, ternary, method_call), field assignment |
| `test_method_invocation.py` | 23 | null ClassLoader fix, parameterized instance methods, static methods with params, constructors, interface method resolution |
| `test_unsupported_commands.py` | 20 | Verify unsupported commands fail gracefully (stop_in, stop_at, clear, catch, ignore, watch, unwatch, trace, untrace, monitor, unmonitor, pop, reenter, lock, threadlocks, step, next, stepi, list) |

**Total**: 75 tests (1 skipped: `test_interrupt`)

## Key Fixtures (conftest.py)

| Fixture | Scope | Purpose |
|---------|-------|---------|
| `jnode_vm` | session | Boot JNode, wait for serial |
| `serial_cmd` | session | Send commands via serial |
| `network_configured` | session | Configure bridged networking |
| `jdwp_listener` | session | Start `debug -p 2000` |
| `jdb_session` | session | Start jdb_agent.py daemon |
| `jdb` | function | Send jdb command, return output |
| `jdb_with_locals` | function | jdb with class cache pre-populated (runs `locals` first) |

## Critical: Class Cache Trigger

jdb's expression parser cannot resolve class names until `VirtualMachine.allClasses()` is called. The `jdb_with_locals` fixture handles this by running `locals` on a selected thread.

**Without trigger**: `print java.lang.Thread.NORM_PRIORITY` → `ParseException: Name unknown`
**With trigger**: works after `locals` or `where`

## Known Limitations

- `test_interrupt` is skipped (not supported on JNode)
- jdb init is slow (~90s for first `classes` request)
- Non-current-thread stack walking returns no frames
- Step/breakpoint debugging not implemented
- Serial buffer can deadlock after heavy JDWP traffic — reboot VM if serial becomes unresponsive

## Running on Fresh VM

```bash
# 1. Start VM
VBoxManage startvm "JNode" --type headless

# 2. Wait for boot (check serial)
python3 ~/.config/opencode/skills/jnode-serial/scripts/jnode_agent_cmd.py "echo alive"

# 3. Configure network
python3 ~/.config/opencode/skills/jnode-serial/scripts/jnode_agent_cmd.py "ifconfig eth-pci(0,3,0) 192.168.1.10 255.255.255.0"
python3 ~/.config/opencode/skills/jnode-serial/scripts/jnode_agent_cmd.py "route --add default eth-pci(0,3,0) 192.168.1.1"

# 4. Start JDWP
python3 ~/.config/opencode/skills/jnode-serial/scripts/jnode_agent_cmd.py "debug -p 2000"

# 5. Run tests
python3 -m pytest tests/jdwp/ -v
```

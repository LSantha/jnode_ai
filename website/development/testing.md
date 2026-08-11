# Testing

## Test Types

| Type | Command | Description |
|------|---------|-------------|
| **JUnit** | `sh build.sh tests` | Unit tests for all subprojects |
| **Regression** | `sh build.sh regression-tests` | Shell command integration tests |
| **JDWP** | `pytest tests/jdwp/` | Remote debugging protocol tests |
| **Boot** | QEMU/VirtualBox | Full OS boot verification |

## JUnit Tests

```bash
# All tests (runs in CI)
sh build.sh tests

# Per-subproject
sh build.sh -f core/build-tests.xml all-junit
sh build.sh -f fs/build-tests.xml all-junit
sh build.sh -f net/build-tests.xml all-junit
sh build.sh -f shell/build-tests.xml all-junit
sh build.sh -f gui/build-tests.xml all-junit
```

- Framework: **JUnit 4.5** + JMock + Mockito
- Location: `<subproject>/src/test/`
- ~390 test files total
- Reports: `all/build/reports/junit/`

## Regression Tests

Shell command integration tests that run against a booted JNode:

```bash
sh build.sh regression-tests
```

Tests are defined in `tests/regression/` and executed via the shell.

## JDWP Integration Tests

**Requirements:**
- JNode running in VirtualBox with JDWP listener on port 8000
- Serial console configured (for agent interaction)
- Python 3.8+ with pytest

```bash
# 1. Start JNode in VirtualBox (see VirtualBox guide)
# 2. Verify JDWP: telnet localhost 8000 → "JDWP Handshake"
# 3. Run tests
cd tests/jdwp
python3 -m pytest -v
```

### Test Coverage (75 tests)

| Category | Tests |
|----------|-------|
| Classes, methods, fields | 18 |
| Threads, thread groups | 12 |
| Stack traces (`where`) | 8 |
| Classpath, connectors | 6 |
| Method invocation | 15 |
| Constructors | 10 |
| Version, capabilities | 6 |

**Run after any JDWP code change** — this is the regression suite for the debug backend.

See [tests/jdwp/AGENTS.md](https://github.com/LSantha/jnode_ai/blob/master/tests/jdwp/AGENTS.md) for full setup.

## Boot Testing

### QEMU (CI)

```bash
# Automated in CI (.github/workflows/ant.yml)
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -M pc -cpu pentium -boot once=d,menu=off -net none \
  -serial file:/tmp/com1.txt -display none &
# Wait for "System has finished" in /tmp/com1.txt (timeout 120s)
```

### Local Boot Test

```bash
# Quick smoke test
sh build.sh cd-x86-lite
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 512 -serial stdio -display none
# Type: help, ls /, ps, vminfo, exit
```

## Writing Tests

### JUnit Example

```java
// core/src/test/org/jnode/vm/ClassLoaderTest.java
package org.jnode.vm;

import junit.framework.TestCase;

public class ClassLoaderTest extends TestCase {
    public void testBootClassLoader() {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        assertNotNull(cl);
        assertEquals("BootClassLoader", cl.getClass().getSimpleName());
    }
}
```

### Regression Test Example

```xml
<!-- tests/regression/commands/ls-test.xml -->
<test name="ls-root">
    <command>ls /</command>
    <expect>bin</expect>
    <expect>dev</expect>
    <expect>etc</expect>
    <expect>home</expect>
</test>
```

## CI Integration

The [ant.yml](https://github.com/LSantha/jnode_ai/blob/master/.github/workflows/ant.yml) workflow runs:

1. `sh build.sh clean -Dbuild.properties.file=../.github/qemu/jnode.properties cd-x86-lite regression-tests`
2. `./test.sh all` (runs JUnit)
3. QEMU boot test with serial log capture
4. Upload JUnit reports + serial log as artifacts

## Test Utilities

| Tool | Purpose |
|------|---------|
| `jnode-interact` skill | Interactive QEMU testing via serial |
| `jnode-java-coding` skill | Compile/run Java on live JNode |
| `filesystem-debug` skill | Analyze FS test failures |
| `jnode-kdb-serial` skill | Kernel debugger via serial |

See [.opencode/skills/](https://github.com/LSantha/jnode_ai/tree/master/.opencode/skills) for available debugging skills.

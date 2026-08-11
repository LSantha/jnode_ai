# Development

## Build System

JNode uses **Apache Ant** with a `build.sh` wrapper. The build is organized as a multi-project build:

```
all/
├── build.xml              # Master build file
├── build-jop.xml          # JOP (JNode OS Platform) macros
├── build-x86.xml          # x86-specific macros
├── conf/
│   ├── default-plugin-list.xml
│   └── full-plugin-list.xml
└── lib/
    └── classlib.jar       # Downloaded GNU Classpath / OpenJDK
```

### Key Targets

```bash
# Full build + ISO
sh build.sh cd-x86-lite

# Clean build
sh build.sh clean cd-x86-lite

# Run tests only
sh build.sh tests

# Build native only
sh build.sh -Dbuild.native.only=true assemble
```

### Build Properties

Create a local `jnode.properties` to override defaults:

```properties
# jnode.properties
jnode.bits=32
jnode.memmgr.plugin.id=org.jnode.vm.memmgr.mmtk.MMTkMemoryManager
jnode.compiler=l1
jnode.enable.jnasm=true
jnode.plugin.list=default-plugin-list.xml
```

See [Building](../getting-started/building.md) for full reference.

## Testing

### JUnit Tests (Per Subproject)

```bash
# All subprojects
sh build.sh tests

# Single subproject
sh build.sh -f core/build-tests.xml all-junit
sh build.sh -f fs/build-tests.xml all-junit
sh build.sh -f net/build-tests.xml all-junit
sh build.sh -f shell/build-tests.xml all-junit
sh build.sh -f gui/build-tests.xml all-junit
```

- Framework: **JUnit 4.5** + JMock + Mockito
- Test locations: `<subproject>/src/test/`
- ~390 test files total
- Reports: `all/build/reports/junit/`

### Regression Tests

```bash
sh build.sh regression-tests
```

Runs shell command tests against a booted JNode image.

### JDWP Integration Tests

Requires a **running JNode VM with JDWP listener** (VirtualBox recommended):

```bash
# Start JNode in VirtualBox with JDWP on port 8000
# Then run:
cd tests/jdwp
python3 -m pytest -v
```

- 75 tests covering: classes, methods, fields, threads, invocation, constructors
- See [tests/jdwp/AGENTS.md](https://github.com/LSantha/jnode_ai/blob/master/tests/jdwp/AGENTS.md) for setup
- **Run these after any JDWP code change** — they are the regression suite

### Boot Testing

```bash
# 1. Build ISO
sh build.sh cd-x86-lite

# 2. Boot in QEMU
qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-lite.iso \
  -m 1024 -serial stdio -display none

# 3. Verify: "System has finished" in serial log
```

CI does this automatically in [ant.yml](https://github.com/LSantha/jnode_ai/blob/master/.github/workflows/ant.yml).

## Debugging

### KDB (Kernel Debugger)

Connect via serial (UART1):

```bash
# QEMU with serial telnet
qemu-system-x86_64 ... -serial telnet::4444,server,nowait

# Connect
telnet localhost 4444
```

Commands: `help`, `bt`, `mem`, `threads`, `isolates`, `plugins`, `devices`

See [KDB Serial Skill](https://github.com/LSantha/jnode_ai/blob/master/.opencode/skills/jnode-kdb-serial/SKILL.md).

### JDWP (Java Debug Wire Protocol)

Best with VirtualBox:

1. Enable Serial Port 1 → Host Pipe → `/tmp/jnode-com1`
2. Port forward: `VBoxManage modifyvm "JNode" --natpf1 "jdwp,tcp,,8000,,8000"`
3. Connect: `jdb -attach localhost:8000`

See [JDWP Skill](https://github.com/LSantha/jnode_ai/blob/master/.opencode/skills/jnode-jdwp/SKILL.md) and [VirtualBox Guide](../getting-started/virtualbox.md).

### Serial Console

```bash
# QEMU
qemu-system-x86_64 ... -serial stdio -display none

# VirtualBox + socat
socat -,raw,echo=0 UNIX-CONNECT:/tmp/jnode-com1
```

### GDB (Native Code)

```bash
# QEMU paused at startup
qemu-system-x86_64 ... -s -S

# GDB
gdb -ex "target remote localhost:1234" \
    -ex "set architecture i386" \
    -ex "symbol-file all/build/native/kernel.o"
```

## Contributing

### Code Style

- **File encoding**: US-ASCII (no smart quotes, em-dash, non-breaking space)
- **Java target**: 1.6 (`java.target=1.6`, `java.source=1.6`)
- **Formatter**: `jnode-eclipse-formatter-settings.xml`
- **No tabs** — 4 spaces
- **Line length**: 120 chars

### PR Process

1. Fork → feature branch
2. Run `sh build.sh tests` locally
3. Run `sh build.sh cd-x86-lite` and boot-test in QEMU
4. Open PR against `master`
5. CI runs: build + tests + QEMU boot
6. Review → merge (squash)

### Issue Labels

The project uses a label system managed by [sync-labels.js](https://github.com/LSantha/jnode_ai/blob/master/.github/scripts/sync-labels.js):

| Family | Examples |
|--------|----------|
| `kind/*` | `kind/bug`, `kind/feature`, `kind/investigate`, `kind/wiki` |
| `agent/*` | `agent/in-progress`, `agent/done`, `agent/needs-info` |
| `area/*` | `area/core`, `area/fs`, `area/net`, `area/vm`, `area/build` |

### Agent Automation

Trigger OpenCode agent via issue comments:

```bash
/oc fix        # Fix the issue
/oc investigate # Investigate and report
/oc review     # Review a PR
/oc wiki       # Update wiki
/oc triage     # Triage new issue
```

See [opencode.yml](https://github.com/LSantha/jnode_ai/blob/master/.github/workflows/opencode.yml) and [jnode-issue-resolver skill](https://github.com/LSantha/jnode_ai/blob/master/.opencode/skills/jnode-issue-resolver/SKILL.md).

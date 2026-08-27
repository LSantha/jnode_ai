# Agent Instructions for JNode

## Project Context

JNode is a Java operating system with custom JVM. ~3,300 Java files, 25 assembly files, organized into sub-projects (core, fs, net, shell, gui, builder, etc.).

## Build System

- **Command**: `sh build.sh <target>` from root
- **Main targets**: `assemble`, `x86`, `x86_64`, `cd-x86-lite`, `cd-x86_64-lite`, `cd-x86-combined-lite`, `tests`
- **Java**: 1.6, 1.7, or 1.8 required
- **Assemblers**: 32-bit uses nasm (or JNAsm if enabled), 64-bit requires yasm
- **Output**: `all/build/cdroms/jnode-*.iso`
- **Classlib**: Downloaded during build to `all/lib/classlib.jar`

## Key Directories

| Path | Purpose |
|------|---------|
| `core/src/core/` | VM, kernel (579 Java files) |
| `core/src/driver/` | Driver framework (262 files) |
| `core/src/vmmagic/` | VM magic annotations |
| `core/src/native/x86/` | Assembly (kernel.asm, vm.asm, mm32/64.asm, etc.) |
| `core/src/classpath/` | GNU Classpath integration |
| `core/src/openjdk/` | OpenJDK patches |
| `core/src/mmtk-vm/` | MMTk bindings |
| `builder/src/` | Build tools, JNasm assembler, BootImageBuilder |
| `shell/` | Shell commands |
| `fs/` | Filesystems, block drivers |
| `net/` | Network stack |
| `gui/` | AWT, video/input drivers |
| `all/build.xml` | Master build file |
| `tests/jdwp/` | JDWP integration tests (75 tests, pytest, requires VirtualBox JNode VM) |

## Running Tests

- **All tests**: `sh build.sh tests` (from root)
- **Per subproject JUnit**: `sh build.sh -f <subproject>/build-tests.xml all-junit`
- **Framework**: JUnit 4.5, JMock, Mockito
- **Test locations**: `<subproject>/src/test/` (390 test files total)
- **JDWP tests**: `python3 -m pytest tests/jdwp/ -v` (requires running JNode VM with JDWP listener; see `tests/jdwp/AGENTS.md`). **Run these after any JDWP code change** — they are the regression test suite for the debug backend.

## Boot Testing

1. Build: `sh build.sh cd-x86-lite` (32-bit), `cd-x86_64-lite` (64-bit), or `cd-x86-combined-lite` (both)
2. Run: VirtualBox or QEMU (`qemu-system-x86_64 -cdrom all/build/cdroms/jnode-x86-combined-lite.iso -m 1G -nographic`)
3. Check logs for crashes
4. VirtualBox note: 64-bit requires Long Mode and PAE enabled (`vboxmanage modifyvm <vm> --long-mode on --pae on`)

## Code Style

- **File encoding**: US-ASCII
- **Java target**: 1.6
- **Formatting**: Use `jnode-eclipse-formatter-settings.xml`

## Constraints

- Never commit unless explicitly requested
- Never push force to main/master
- Don't commit secrets or credentials

## Key Files

- `all/conf/default-plugin-list.xml` - Default plugins
- `all/conf/full-plugin-list.xml` - Full plugin set
- `jnode.properties` - Build configuration

## Documentation

- **Wiki Repository**: `https://github.com/LSantha/jnode_ai.wiki.git`
- **Initialization**: The wiki is a separate, gitignored repository. If the `.wiki/` directory is missing, you MUST run:
  `git clone git@github.com:LSantha/jnode_ai.wiki.git .wiki`
- **Persistence Rule**: Any changes made to the `.wiki/` directory MUST be manually pushed using `git push` from inside that directory. The OpenCode auto-push mechanism DOES NOT cover the `.wiki` folder.
- **Reporting**: After a successful push, always provide the URL to the updated wiki page in your final issue comment.
- **Wiki schema**: See `WIKI_SCHEMA.md` for structure and maintenance rules
- **Skills**: Check the `.opencode/skills/` directory. Each skill auto-loads when its `description:` matches the current task — `jnode-issue-resolver` for issue/PR work woken by `.github/workflows/opencode.yml`, `filesystem-debug` for filesystem bug diagnosis, `jnode-interact` for QEMU boot testing, `update-wiki` for wiki spoke pages. Each is also invokable on demand by name.
- **LLM agents**: follow the Wiki Knowledge Retrieval Protocol to locate relevant wiki pages for your task

### Wiki Knowledge Retrieval Protocol
You have access to a Karpathy-style wiki. This consists of highly compressed, factual, and interlinked markdown files. Follow these strict rules to query and use it efficiently:

*   **Greedy Keyword Search:** Run initial searches using single, high-density keywords rather than natural language questions.
*   **Follow Markdown Links:** If a retrieved page contains file links (e.g., `[[topic-b]]`), proactively read those linked files to gather complete context.
*   **Prioritize Code Blocks:** Treat syntax examples and raw code snippets within the wiki as the definitive ground truth for implementation.
*   **Exact Terminology:** Reuse the exact variable names, directory paths, and technical terms found in the wiki. Do not paraphrase.
*   **No Structural Assumptions:** The wiki is flat and fluid. Do not assume a hierarchical folder structure unless explicitly documented.
*   **Synthesize, Don't Exploded:** Match the wiki's tone. Provide dense, factual answers. Eliminate conversational filler and introductory fluff.

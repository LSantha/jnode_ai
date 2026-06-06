# Agent Instructions for JNode

## Project Context

JNode is a Java operating system with custom JVM. ~3,300 Java files, 25 assembly files, organized into sub-projects (core, fs, net, shell, gui, builder, etc.).

## Build System

- **Command**: `sh build.sh <target>` from root
- **Main targets**: `assemble`, `x86`, `x86_64`, `cd-x86-lite`, `cd-x86_64-lite`, `tests`
- **Java**: 1.6, 1.7, or 1.8 required
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

## Running Tests

- **All tests**: `sh build.sh tests` (from root)
- **Per subproject**: `cd <subproject> && ant test`
- **Framework**: JUnit 4.5, JMock, Mockito
- **Test locations**: `<subproject>/src/test/` (390 test files total)

## Boot Testing

1. Build: `sh build.sh cd-x86-lite` or `cd-x86_64-lite`
2. Run: VirtualBox (`.vmx`) or QEMU (`qemu.sh`)
3. Check logs for crashes

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

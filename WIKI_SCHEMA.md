# JNode Wiki Schema

## Purpose

This wiki is a Karpathy-style "compiled knowledge base" for the JNode project.
It is maintained by LLM agents and human contributors. Its goal is to give any
LLM working on JNode the architectural context it needs to be effective.

The wiki lives in `.wiki/` (a gitignored clone of `git@github.com:LSantha/jnode_ai.wiki.git`).

## Principles

1. **Compiled, not copied** — Don't paste source code. Summarize, explain, cross-reference.
2. **Atomic pages** — One concept per page, 200–500 lines max.
3. **Always link** — Use `[[wiki-links]]` liberally to connect related concepts.
4. **Ground truth is code** — If the wiki contradicts the source, the source wins.
5. **Index first** — Always update `index.md` when adding/modifying/deleting pages.
6. **Maintain the log** — Every significant wiki update should be noted in `[[Changelog]]`.

## Hub and Spoke Architecture

To adhere to the "Atomic pages" principle without bloating documents, the wiki uses a Hub and Spoke model:

1. **Hub Pages (Subsystems):** Broad, high-level pages (e.g., `Driver-Framework.md`, `Filesystem-Layer.md`) that provide an overview of a subsystem. They act as a table of contents and heavily link out to Spoke pages rather than explaining deep technical minutiae.
2. **Spoke Pages (Concepts):** Atomic, dedicated pages for specific, dense architectural concepts (e.g., `DeviceManager.md`, `VFS.md`, `SocketBuffer.md`). These pages dive deep into classes, methods, data flows, and gotchas for that single piece of the puzzle.
3. **Minor Concepts:** Terms that only require a sentence or two of explanation should be placed in `Glossary.md` rather than getting a dedicated Spoke page.

## Page Structure Template

Every wiki page should follow this structure:

```markdown
# Page Title

> One-sentence summary of this topic.

## Overview
2-3 paragraph explanation of the concept.

## Key Components
| Class / File | Role |
|---|---|
| `path/to/File.java` | Brief description |

## How It Works
Step-by-step or data-flow explanation.

## Gotchas & Non-Obvious Behavior
- Bullet list of things that trip up contributors and LLMs

## Related Pages
- [[Related Topic 1]]
- [[Related Topic 2]]
```

## Wiki Map

Required pages (every page listed here must exist):

- `Home.md` — Entry point, project overview, links to all sections
- `index.md` — Machine-readable master index (see Index Maintenance below)
- `Architecture.md` — System layers, how everything fits together
- `Boot-Sequence.md` — GRUB → assembly → Java Main()
- `Memory-Management.md` — Address spaces, paging, GC (MMTk)
- `JIT-Compilers.md` — L1/L2 compiler pipeline, register allocation
- `Plugin-System.md` — Descriptors, extensions, plugin lifecycle
- `Driver-Framework.md` — Device manager, resource management, PCI/USB
- `Filesystem-Layer.md` — VFS, partition tables, fs drivers
- `Network-Stack.md` — Protocols, sockets, network device drivers
- `GUI-AWT.md` — Video drivers, AWT implementation, input
- `Shell-Commands.md` — Command framework, built-in commands
- `Build-System.md` — Ant targets, boot image builder
- `Testing.md` — Unit tests, boot testing with QEMU
- `VM-Magic.md` — @Uninterruptible, @Inline, unsafe operations
- `Assembly-Files.md` — Role of each .asm file
- `Code-Conventions.md` — Style, patterns, anti-patterns
- `Glossary.md` — Project-specific terminology
- `Changelog.md` — Log of wiki updates

## Style Rules

- Use **bold** for class names on first mention in a section
- Use `code` for file paths, method names, annotations
- Use tables for listing classes/files and their roles
- Prefer bullet lists over paragraphs for technical details
- Include the actual file path (relative to repo root) when referencing source files
- Keep pages between 200-500 lines — split if longer

## Index Maintenance

`index.md` is the most important file for LLM consumption. It is the first file
an LLM reads to decide which wiki pages to load into context. It contains four
tables, each with specific maintenance rules:

### Pages Table

Every wiki page must have a row in this table.

| Column | Content |
|--------|---------|
| Page | `[[wiki-link]]` to the page |
| Covers | Brief (5-10 word) description of the page's scope |
| Key Source Paths | Top-level directories or packages this page covers |

**Rules:**
- Add a row when creating a new wiki page
- Remove the row when deleting a wiki page
- Update "Covers" and "Key Source Paths" if a page's scope changes
- Sort alphabetically by page name

### Concepts → Pages Table

Maps project-specific terms and concepts to their defining wiki page.

| Column | Content |
|--------|---------|
| Concept | The term or concept name |
| Defined In | `[[wiki-link]]` to the page that gives the primary explanation |
| Related | Comma-separated `[[wiki-links]]` to pages that reference this concept |

**Rules:**
- When a new concept is introduced or explained in a wiki page, add it here
- "Defined In" = points directly to the dedicated **Spoke page** (e.g., `[[DeviceManager]]`) if it exists, or to `[[Glossary]]` for minor terms.
- "Related" = points "up" to the parent **Hub page** (e.g., `[[Driver-Framework]]`) and to sibling concepts.
- Keep alphabetically sorted by concept name
- When renaming or merging wiki pages, update all references in this table

### Source Path → Pages Table

Maps source tree locations to relevant wiki pages, so LLMs know which pages
to read when working on specific parts of the codebase.

| Column | Content |
|--------|---------|
| Path Pattern | Glob pattern or directory path relative to repo root |
| Relevant Pages | Comma-separated `[[wiki-links]]` |

**Rules:**
- Use glob patterns for directories (e.g., `core/src/native/x86/*.asm`)
- A path can map to multiple pages
- When a wiki page starts covering a new source area, add the mapping
- When source directories are reorganized, update the patterns
- Keep sorted by path pattern alphabetically

### Task Routing Table

Maps common developer tasks to the wiki pages an LLM should read first.
This is the "compiled intelligence" that makes the wiki more useful than
raw documentation.

| Column | Content |
|--------|---------|
| If your task involves... | Natural language description of a task category |
| Read these pages | Comma-separated `[[wiki-links]]` in priority order |

**Rules:**
- Phrase tasks in natural language ("Fixing a compiler bug", "Adding a new shell command")
- List pages in priority order — most relevant first
- Add new routes when recurring task patterns emerge from coding sessions
- Keep to 10-15 rows — cover the most common scenarios, not every edge case
- Remove or merge routes that are too similar

## Workflow for Wiki Updates

When updating the wiki (whether as an LLM agent or human contributor):

1. Make your content changes to the relevant page(s)
2. Update `index.md` — all four tables if applicable
3. Add an entry to `Changelog.md` with the date and a brief description
4. Verify all `[[wiki-links]]` in your changes resolve to existing pages
5. Commit and push: `cd .wiki && git add . && git commit -m "<description>" && git push`. **IMPORTANT**: You must manually run `git push` inside the `.wiki` directory. Do NOT rely on the OpenCode Action infrastructure to auto-push this, because `.wiki` is a separate git repository and the auto-push will fail.

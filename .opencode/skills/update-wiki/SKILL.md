---
name: update-wiki
description: Documentation maintenance for JNode. Use this skill when creating or updating "Spoke" pages in the .wiki directory, ensuring Hub/Spoke architecture, strict index sorting, and remote verification.
license: MIT
metadata:
  version: 1.1.0
  author: Antigravity
---

# Skill: Update JNode Wiki with New Concept

This skill defines the mandatory workflow for creating a new "Spoke" page in the JNode AI Wiki.

## Prerequisites
- The `.wiki/` directory must be initialized (`git clone git@github.com:LSantha/jnode_ai.wiki.git .wiki`)
- Familiarity with `WIKI_SCHEMA.md`

## Workflow

### 0. Initialization (CRITICAL)
- **Check for .wiki**: If the `.wiki/` directory is missing or is NOT a git repository (check for `.wiki/.git`), you MUST run:
  ```bash
  git clone git@github.com:LSantha/jnode_ai.wiki.git .wiki
  ```
- **DO NOT** simply `mkdir .wiki`. It MUST be a clone of the remote repository.

### 1. Research phase
- Locate the primary source code for the concept.
- **Broaden Search**: Search for related "Helper", "Manager", "Layout", or "Constants" classes (e.g., if researching `ObjectLayout`, also look for `TIBLayout`).
- Identify:
    - Package names and key classes.
    - Architectural patterns (e.g., how it plugs into the kernel).
    - Dependencies on other subsystems.

### 2. Creation phase
- Create `.wiki/[Concept-Name].md`.
- **Constraint**: Maintain a length of 200-500 lines.
- **Structure**:
    - `# Title`
    - `> One sentence summary`
    - `## Overview`
    - `## Key Components` (with a table of classes/files)
    - `## How It Works` (with code snippets or Mermaid diagrams)
    - `## Gotchas` (non-obvious behavior)
    - `## Related Pages` (backlinks to Hubs and siblings)

### 3. Linking phase
- **Hub Link**: Identify the parent Hub page (e.g., `[[Architecture]]`, `[[Shell-Commands]]`) and add a link to the new page.
- **Index Update**: Add or update rows in **all four tables** in `.wiki/index.md`.
    1. **Pages Table**: Add the new page in alphabetical order.
    2. **Concepts → Pages**: **MANDATORY**: Add a row for the **Page Name** itself. If the concept already exists (e.g., pointing to `[[Glossary]]`), **UPDATE** it to point to your new Spoke page. Also add any discovered **Technical Terms**.
    3. **Source Path → Pages**: Map the directory path to the new page.
    4. **Task Routing**: Add the page to relevant developer tasks.
- **Glossary Update**: If you introduced or deeply explained a new project-specific term (e.g., "TIB", "IMT", "Proclet"), add a 1-sentence definition to `.wiki/Glossary.md`.
- **Strict Sorting**: You MUST maintain A-Z alphabetical order in all tables. Re-verify the sort order of the entire table after your insertion.

### 4. Persistence phase (CRITICAL)
- **Manual Push**: You MUST run the following commands in the `.wiki` directory:
    ```bash
    cd .wiki
    git add .
    git commit -m "Wiki: Add [Concept-Name] spoke page"
    git push
    ```
- **Verification**: Run `git ls-remote origin refs/heads/master` to ensure the commit is on the server.
- **Reporting**: Provide the direct URL to the new page in your final response: `https://github.com/LSantha/jnode_ai/wiki/[Concept-Name]`

### 5. Completion (MANDATORY)
- **Close the Issue**: Once the push is verified and the URL is provided, you MUST close the issue. Use the command:
  ```bash
  gh issue close [Issue-Number]
  ```
- **Verification**: The orchestrator will only consider this task finished if the issue is in the **CLOSED** state.

## Negative Constraints
- **NEVER** use subdirectories in `.wiki/`. Keep it flat.
- **NEVER** rely on OpenCode auto-push for the wiki. It only works for the main repo.
- **NEVER** skip the Index or Glossary updates.
- **NEVER** finish without closing the issue.

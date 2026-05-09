# Skill: Update JNode Wiki with New Concept

This skill defines the mandatory workflow for creating a new "Spoke" page in the JNode AI Wiki.

## Prerequisites
- The `.wiki/` directory must be initialized (`git clone git@github.com:LSantha/jnode_ai.wiki.git .wiki`)
- Familiarity with `WIKI_SCHEMA.md`

## Workflow

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
- **Index Update**: Add a new row to **all four tables** in `.wiki/index.md`.
    1. **Pages Table**: Alphabetical.
    2. **Concepts → Pages**: **MANDATORY**: Add a row for the **Page Name** itself AND any discovered **Technical Terms** (e.g., "TIB", "IMT").
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

## Negative Constraints
- **NEVER** use subdirectories in `.wiki/`. Keep it flat.
- **NEVER** rely on OpenCode auto-push for the wiki. It only works for the main repo.
- **NEVER** skip the Index update. The index is the agent's primary navigation tool.

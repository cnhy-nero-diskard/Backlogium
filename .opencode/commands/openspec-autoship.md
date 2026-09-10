---
description: "Autoship an OpenSpec change: implement every task and commit AND push each checkpoint (hands-free)"
---

Run the OpenSpec Autoship workflow for a change.

**Invocation**: Load and follow the `openspec-autoship` skill (available in
`.opencode/skills/openspec-autoship/SKILL.md`) — use the skill tool with
`name: "openspec-autoship"` to load it, then execute its full workflow.

**Input**: Optionally specify a change name plus flags (e.g.,
`/openspec-autoship add-auth`, `/openspec-autoship --dry-run`,
`/openspec-autoship --no-push`).
**Provided arguments**: $ARGUMENTS

Execute the loaded skill's autonomy contract exactly: commits and pushes happen
automatically at every coherent checkpoint, on the current non-detached branch
(including main). Pause only for the hard blockers the skill defines.

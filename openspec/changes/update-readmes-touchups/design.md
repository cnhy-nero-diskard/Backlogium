# Design

## Scope

The README should become a concise product and contributor entry point:

- what Backlogium does now,
- how the app stores and protects data,
- how to run it locally,
- where the main specs live,
- what remains future work.

The screen descriptor should remain a richer UI reference, but its app-shell
summary must match the current app shape.

## Decisions

- Prefer present-tense feature descriptions for implemented local functionality.
- Keep future Firebase/OBS content under a roadmap heading so readers do not
  mistake it for shipped behavior.
- Use the current user-facing vocabulary: Focus games, HowLongToBeat review,
  Steam history import, live presence, Settings, backup/restore.
- Do not document secrets or build artifacts beyond existing safe `local.properties`
  seed guidance.

## Non-Goals

- No UI restructuring.
- No database/schema changes.
- No changes to Steam API behavior, HLTB scraping, foreground services, or backup
  merge semantics.

## Why

The current `library-recency` main spec has a precedence statement split across the file and a recency-state requirement with no scenario. This makes the spec invalid and obscures behavior already established by the archived `add-library-recency-signals` decision record.

## What Changes

- Restore the complete precedence statement and add a scenario that makes the established ordering explicit.
- Remove the stray precedence fragment from the acquisition-announcement requirement while preserving all of its scenarios.
- Keep existing last-played and baseline scenario content unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `library-recency`: complete the mutually exclusive recency-state requirement and preserve acquisition-announcement behavior.

## Impact

Only the `openspec/specs/library-recency/spec.md` behavior contract changes. No production code, APIs, dependencies, or stored data change.

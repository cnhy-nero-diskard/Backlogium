## Context

The main `library-recency` spec has a `Three mutually exclusive recency states` requirement with no scenario and an incomplete precedence sentence. The remainder of that sentence appears after the acquisition-announcement scenarios. The archived `add-library-recency-signals` delta records the intended precedence.

OpenSpec validates modified requirements as complete replacement blocks and rejects dropping scenarios currently attached to a requirement. A first draft that moved those existing scenarios to a different requirement failed that preservation check.

## Goals / Non-Goals

**Goals:**
- Restore the complete precedence rule and give the recency-state requirement an explicit scenario.
- Remove the unrelated precedence fragment from the acquisition-announcement requirement without changing its scenarios.
- Keep this fix limited to the validation blocker and preserve existing last-played and baseline blocks.

**Non-Goals:**
- Change recency derivation, thresholds, persistence, or acquisition-announcement behavior.
- Reassign existing scenarios between requirements in this change.
- Modify application code or tests.

## Decisions

### Preserve existing scenario ownership in this fix

The delta modifies only the recency-state requirement and the acquisition-announcement requirement. It restores the precedence statement under recency states and adds a scenario that covers the ordering. All current acquisition scenarios remain intact, while the stray trailing precedence fragment is removed.

Moving scenarios out of the last-played and baseline requirements is deferred: the normal `MODIFIED` operation requires every existing scenario in that requirement to remain in its replacement block. Resolving that separate structural concern would require a broader requirement migration rather than silently dropping or duplicating scenarios.

### No implementation changes

The archived decision record already defines the intended ordering, so this is a documentation correction only. No runtime behavior, data, or migration changes are needed.

## Risks / Trade-offs

- **Some pre-existing scenarios remain under broader-than-ideal requirement headings** → Preserve them unchanged to keep this correction narrow and avoid losing normative scenarios; any later reorganization should use an explicit requirement migration.

## Migration Plan

Sync the validated delta into `openspec/specs/library-recency/spec.md`, then run the capability and repository-wide spec validators. No code or data migration is required.

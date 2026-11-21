## Context

See `proposal.md` and `specs/app-ui/spec.md`. `HltbReviewViewModel` currently follows the selected app ID through queue reordering and clamps after removal; Next/Previous operate on the full ambiguous-plus-unmatched list. A scoped app ID is tracked separately and the route currently closes only after that app leaves the queue. Library list has a targeted review path, while both grid densities omit its action control. Gameplay settings has no Match Center shortcut.

## Goals / Non-Goals

**Goals:**

- Make session deferral explicit without changing persisted HLTB match state.
- Move forward through actionable games by identity even when queue partitions reorder.
- Reuse the targeted reviewer route from grids and provide a general Settings entry.

**Non-Goals:**

- Changing HLTB matching thresholds, data fetching, dataset application, or Library visit expiry.

## Decisions

### Keep deferral in the reviewer route's session state

Store deferred app IDs and selection in the route-scoped reviewer ViewModel, independent of HLTB repository rows. The ViewModel survives configuration recreation and a temporary detail push while its route remains on the back stack; dismissing the route or ending the process resets the set. Explicit Skip and navigation away from an unresolved game add its app ID. Review skipped clears that set to start another pass. Persisting a skip in Room would wrongly make a temporary choice look like a resolved or permanent exclusion.

### Derive the active queue by identity

Derive ambiguous and unmatched partitions from the repository as today, then form the active pass by excluding deferred IDs. Resolve the selected game by app ID, not a raw index. After a match or queue reorder, choose the next surviving unprocessed game at or after the prior position; if there are unprocessed games earlier after reordering, choose one of those before declaring exhaustion. Never automatically select a deferred game. When the active pass empties, show completion and offer Review skipped if needed. An alternative of clamping directly in the complete queue can reselect a title the player skipped.

### Keep scoped completion tied to actual resolution

The scoped route retains its original app ID as a separate identity. Resolving that app can close the route as it does today; deferring it shows an exhausted state with Review skipped or Done and leaves its stored match unresolved. Resolving another app does not complete the scoped route. This keeps a single-game lookup from silently becoming a general reviewer journey.

### Reuse existing entry/navigation wiring

Expose a labeled per-game action in both grid densities that calls the same targeted route used by the list. Add a general Match Center action in `GameplaySettingsContent` and pass its callback through Settings detail and `BacklogiumAppRoot`. Use the existing one-shot needs-attention navigation path for lookup outcomes rather than introducing another route type.

## Risks / Trade-offs

- [The reported jump has a different current cause] → Reproduce it against the identity-based selector before changing selection logic.
- [Queue emissions race with a skip or match] → Derive active selection atomically from app IDs and keep the scoped identity separate.
- [The last active title is deferred] → Show completion with deliberate Review skipped access, without wrapping.
- [Grid action competes with game opening] → Give it a distinct accessible target and verify both densities.

## Migration Plan

No stored-data migration is required because session skips are transient. A rollback restores the previous reviewer navigation and entry points without changing HLTB match rows.

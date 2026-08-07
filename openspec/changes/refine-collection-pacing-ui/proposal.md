## Why

Collection cards currently spend their limited Home-space explaining mode and estimate details, while deadline planning treats every minute before the target date as playable time. Collections should stay glanceable and should judge feasibility against the player's observed gaming habits instead of an impossible 24-hours-per-day assumption.

## What Changes

- Condense Home collection cards around the collection name, one mode-relevant status line, compact progress, and a maximum of three game thumbnails plus overflow.
- Give every collection containing the currently played game a faint accent-colored border glow, with reduced-motion behavior and a short fade after play ends.
- Reuse the Library card's right-aligned, softly faded Steam header-art treatment on game cards inside collection overviews and management surfaces.
- Introduce a local-only Personal Pace forecast derived from recent synthesized sessions, including expected active gaming days, typical active-day duration, projected gaming capacity, confidence, and required pace.
- Replace deadline feasibility's calendar-minute comparison with Personal Pace capacity while continuing to subtract stored playtime from the selected HLTB estimate.
- Show the collection-overview `Change deadline` shortcut only when unfinished work remains and either a sufficiently confident forecast says the target is infeasible or the target date has arrived/passed.
- Surface useful Personal Pace outcomes in deadline goals, completion goals, and ordered queues while keeping basic lists free of unsolicited forecasting.
- Preserve uncertainty: missing HLTB estimates or insufficient play history prevent definitive fit claims and deadline-change recommendations.

## Capabilities

### New Capabilities

- `personal-pace-forecasting`: Derive a robust, confidence-aware gaming-habit profile and future playable-capacity forecast from locally stored session history.

### Modified Capabilities

- `custom-collections`: Use Personal Pace for mode-relevant projections and deadline feasibility, including conditional deadline intervention and explicit uncertainty.
- `app-ui`: Refine Home collection-card hierarchy, bound previews to three games, add active-play collection glow, share Library-style game artwork, and present concise pacing states.

## Impact

- Affects collection summary/domain models, session-history queries or repository projections, Home and Collections view models, and Compose collection-card/member-card rendering.
- Reuses existing synthesized session, HLTB, library artwork, collection membership, live-status, theme, and reduced-motion signals; no network request is added to forecast derivation.
- The forecast should remain a pure, injected-time/domain calculation with deterministic JVM tests. No new dependency is expected; persisted schema changes are not required unless implementation chooses to cache derived values.
- Existing collection data and modes remain compatible. The change modifies presentation and deadline-feasibility semantics without removing stored fields.

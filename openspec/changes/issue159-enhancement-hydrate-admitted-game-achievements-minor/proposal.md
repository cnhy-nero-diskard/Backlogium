# Proposal

## Why

The active-game watch and on-open catalogue browsing have shipped. A confirmed new admission can still wait for the ordinary achievement tier or for the user to open detail. [Issue #159](https://github.com/cnhy-nero-diskard/Backlogium/issues/159)

## What Changes

- Queue targeted achievement hydration after a committed new owned-game admission and verify the equivalent automatic Family Sharing path.
- Bound admission traffic and retries, preserve first-sync and restore behavior, and reuse the existing coordinated per-game fetch and merge.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `steam-achievements` — Admission eligibility, bounded work, and merge semantics.

## Impact

Android library commit and achievement work. Builds on `add-achievement-watch` and `fix-shared-game-achievement-visibility`. The separately reported full-refresh defect remains tracked in [#207](https://github.com/cnhy-nero-diskard/Backlogium/issues/207).

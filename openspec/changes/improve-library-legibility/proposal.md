## Why

Library Focus/Your games headers still omit their section sizes, and achievement counts are hard to compare at a glance. The original PR #84 plan remains useful, but must accommodate today's hidden/shared/wishlist behavior, recency badges, refurbished layout, and nested Settings destinations.

## What Changes

- Add an achievement-progress bar alongside existing trophy counts in the list and ordinary grid, including collection member surfaces sharing that field.
- Preserve the density ladder: the compact grid gains no trophy count/bar, and existing XP/recency/HLTB treatments stay intact.
- Keep missing achievement data distinct from known zero; preserve the completed-game indicator rather than adding a redundant full bar.
- Show per-section displayed/matching counts and their pre-filter denominators.
- Expose consistently defined all-time cached-library scale in Analytics and Settings → Data & privacy, with hidden/wishlist scope stated.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: density-compatible trophy progress and shared library-count presentation.

## Impact

Planning only; no acquisition/achievement fetching, storage migration, or classification of what counts as a purchased game. Implementation affects shared game-cell presentation and a repository/domain count summary. Do not reopen the newer Library layout/density redesign.

Recovered from #84 at `5c1296e`, reconciled against master `d46a711c`. Related to #167 and #174, but keyword preservation, wishlist search, and acquisition sorting remain separate unresolved work. Counts must follow those changes if implemented later; this draft does not implement them.

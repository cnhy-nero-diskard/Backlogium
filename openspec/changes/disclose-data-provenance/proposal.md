## Why

Analytics, History, and pacing figures can look equally certain even though they combine remote reports, recorded activity, and app estimates. The old PR #84 proposal assumed every session amount came directly from Steam and that cloud access had not landed. Both assumptions are outdated.

## What Changes

- Use a small shared vocabulary: Observed for an unmodified source report, Tracked for recorded activity assigned to app periods, Inferred for app calculations or estimates.
- Put compact source explanations beside figures, with a shared accessible explanation for the actual mechanism.
- Explain mixed/unknown activity conservatively, including family-shared elapsed observations and proven cloud-assisted timing.
- Classify quest-met counts and achievement-tier aggregates as inferred; keep raw remote percentages separate.
- Preserve Personal Pace confidence states and explain HLTB remaining work, deadline/capacity, and tracked history together.
- Give time-of-day figures a specific timing caveat that remains accurate after post-play sync and cloud re-filing.

## Capabilities

### New Capabilities

- `derived-data-provenance`: figure classification, evidence-aware explanations, confidence, accessibility, and disclosure coverage.

### Modified Capabilities

- `app-ui`: Analytics, History, and collection pacing disclosures.

## Impact

Planning only; no storage migration, arithmetic, new request, or cloud authorization change. Implementation will expose existing domain evidence to presentation rather than inferring old session sources from current ownership. A figure inventory and localized explanation mapping are required.

Recovered from #84 at `5c1296e`, reconciled against master `d46a711c`. Supports #169/#170 via #174, but does not itself redesign the misleading Analytics banner or create share exports. The recovered History/XP proposal can reuse these labels without depending on its merge order.

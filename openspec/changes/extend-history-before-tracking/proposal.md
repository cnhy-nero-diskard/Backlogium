## Why

History currently groups sessions and daily progress, then attaches unlocks to those existing dates. It therefore drops unlock-only dates and cannot page to the oldest dated unlock. PR #84 also proposed daily XP, but its replay omitted newer manual/shared inputs and assumed a bounded viewport contained the full cumulative history.

## What Changes

- Include dated unlocks in History's date union and paging floor alongside sessions and progress-only rows.
- Represent unlock-only dates as evidence-only days: minutes unknown, no fabricated sessions, quest state unavailable.
- Use “before tracking” only when a separate reliable tracking boundary proves it; otherwise state the evidence that exists.
- Attribute daily XP by differences in the existing cumulative per-game engine, using all current inputs and rules.
- Show undated XP separately, including imported/manual playtime and unlocks with no usable date.
- Present attributed daily XP in History and today's Home card without changing earned progress, quests, streaks, or stored totals.

## Capabilities

### New Capabilities

- `untracked-history`: evidence-only days and the complete dated-evidence paging boundary.
- `daily-xp-attribution`: current-rule marginal replay and explicit undated reconciliation.

### Modified Capabilities

- `app-ui`: History day states/paging and Home/History XP attribution presentation.
- `playtime-backfill`: disclose the undated imported contribution without inventing a dated distribution.

## Impact

Planning only. Implementation adds domain/read models and a minimum dated-unlock query; no new daily-progress rows, per-day XP column, or migration. Current cloud contribution detail, session-start attribution, timeline choice, and expansion/reveal behavior remain intact.

Recovered from #84 at `5c1296e`, reconciled against master `d46a711c`. Supports part of #169 via #174. The broader History readability and Home game-breakdown issue remains open; this proposal does not resolve it merely by adding XP.

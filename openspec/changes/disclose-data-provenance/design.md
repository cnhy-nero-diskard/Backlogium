## Context

See proposal.md. Owned-game sessions can derive amounts from Steam counter differences. Family-shared play can use elapsed presence observations; a game may later become owned without rewriting its historical source. Cloud history and post-play refresh already narrow/re-file timing. Session exposes recovered-shared and timing-informed contribution flags, but legacy rows do not always identify their complete amount source. Current Game.source cannot prove an old session's source.

Existing daily attribution puts a whole session on its local start date. Personal Pace already has learning/missing/reliable states. This change describes those computations and keeps their outputs intact.

## Goals / Non-Goals

**Goals:** accurate per-figure meaning, compact visible disclosures, accessible explanations, and no unsupported certainty for legacy/mixed data.

**Non-Goals:** new provenance storage, changes to rules or period attribution, retroactive source reconstruction, accuracy tolerances, or a disclosure-hiding preference.

## Decisions

### Three classifications with source-aware parameters

Observed means an unmodified source report: a specifically labelled Steam lifetime counter, Steam unlock timestamp, raw global percentage, or dataset completion-time estimate with its source/freshness. It does not mean the source is a measurement of the player's actual completion time or that a sync-time rarity snapshot describes rarity on the historical unlock date.

Tracked means recorded activity placed into app windows. Its amount may combine counter differences and presence-derived spans. It makes no blanket exactness promise. Total played minutes in a recorded period and per-game recorded totals use this term; a ranking, share, percentage change, or derived session summary uses Inferred.

Inferred covers quest-met counts/rules, tier counts computed from observed percentages, sessions/counts/average/longest/time buckets, forecasts, XP replay, and manual estimates. Explain manually entered amounts as user estimates rather than remote observations.

Represent a classification plus figure-specific inputs/evidence. Reuse the existing domain flags when they prove a contribution. Where facts are absent, say recorded activity with approximate boundaries; do not claim Steam-only amounts, continuous recording, or cloud-assisted timing for all rows. Account/current ownership/cloud-setting state alone is insufficient evidence.

### Disclosures sit with a figure

Use one compact marker per figure or one per card only when every figure shares its classification and mechanism. Selecting it opens a localized shared explanation parameterized for that figure. A screen-wide caveat cannot substitute for different mechanisms. A provenance label remains in screenshots; essential period/source wording is inline, while longer caveats live in the explanation.

Maintain an explicit inventory for Analytics period totals, most-played totals/ranking, session summaries, time-of-day, quest history, rarity counts, History amount/start/unlocks, lifetime detail, and collection Personal Pace. New figures must extend the inventory. Presentation consumes repository/domain values, not Room entities.

### Timing and confidence stay specific

Time-of-day explains that sessions are assigned by estimated local start: cold Steam deltas may place play at an earlier check; confirmed cloud/post-play information can improve boundaries but does not make every legacy row exact. No fixed “within 15 minutes” guarantee.

Personal Pace leads with the existing learning/reliable/missing-estimate state. Its explanation names recent tracked play and active-day frequency, the members' applicable HLTB estimates and remaining work, and the deadline/time capacity where relevant. The confidence state and derivation form one legible message. A generic “sessions are estimated” explanation is insufficient.

Preserve current start-date attribution and proven per-session cloud badges/reveal actions. Provenance disclosure must not hide evidence, replace approximate-start notation, or label every cloud-configured session as a cloud contribution.

### Accessible and localized

Include the term and essential qualifier in the associated figure's semantics. Explanation actions have clear labels/targets. Use locale-aware numbers/date/period labels; do not rely on tint, a tilde, or hover. Reduced motion changes no factual content.

## Risks / Trade-offs

- Labels add density → group only equivalent mechanisms, keep explanations short, verify actual narrow screens/font scale.
- Legacy source ambiguity → conservative explanation; no invented exactness from present ownership.
- Future computation changes can stale copy → colocate the inventory with UI mapping and verify against domain behavior.
- Snapshot rarity can look historical → name the snapshot observation time/context rather than claiming historical global rarity.

## Migration Plan

No database migration. Build the figure inventory first, then shared semantics/explanation components, then wire surfaces. Existing calculations, data freshness, and cloud attribution must remain unchanged. Validate copy and representative device states before release.

## Context

See proposal.md for motivation. Master `d46a711c` already provides active-day averages, elapsed calendar-window bounds, guarded comparable-period headlines, and explicit all-time notes. `AnalyticsOverviewCard` shows a leading game's art/name/minutes above period aggregates; the full leading-game sentence is assigned to semantics, while visible content omits that role. The precise period header is outside the card. The report identifies no screenshot or incorrect app ID, so algorithm failure is unproven.

The distinction between game figures and aggregate figures benefits from an explicit presentation design even though no storage migration is needed. PR #176 separately proposes source-aware figure disclosures.

## Goals / Non-Goals

**Goals:** give visible text and semantics the same factual scope; keep artwork/identity aligned with its snapshot; establish a reusable scope convention for `4c`.

**Non-Goals:** changing headline priority, most-played ranking, window calculations, imported play attribution, session amounts, generic provenance classification, or the rest of #147's insights roadmap.

## Decisions

### Two plainly scoped blocks inside the overview

Keep the headline above secondary controls. When it is a leading-game headline, label its game panel as most played in the represented period and show that game's recorded minutes beside its identity/artwork. Place total tracked minutes, active days, and active-day average in a visually separate block labelled for all eligible visible games in the displayed period. For other headline variants, render the factual aggregate statement without an old game panel.

Put exact represented date context on or immediately beside the overview, using existing locale-aware period formatting. Current month/year context names the actual elapsed-to-date bounds; period identity for navigation stays the original full month/year. Current and longest streak and rarity keep their explicit all-time framing.

Alternative: add only an info button or accessibility sentence. Rejected because the reported confusion concerns visible association and must be understandable in a screenshot. A full page reorganization is unnecessary for this focused scope.

### Bind one snapshot and one game identity

Expose an explicit featured-game identity alongside the leading headline or use a single presentation model that derives both from the same `AnalyticsInputs` snapshot. Verify the selected app ID, name, minutes, and artwork agree; names alone are not identity. Keep deterministic tie behavior, using neutral wording that does not claim a unique leader. Preserve established artwork fallbacks.

Retain the existing `inputs.window` snapshot discipline during range changes: old figures and their labels remain together and marked updating until the new snapshot arrives. Do not replace only the artwork/period label while keeping old amounts. A comparison/no-data variant carries no featured panel.

Alternative: recompute a separate game selection in the Composable. Rejected because it can drift from the fact the headline is describing and creates a new ranking policy.

### Verification begins with evidence, then checks explicit semantics

Record representative leading-game, comparison, empty-window, tied-minute, missing-artwork, current-month/year, and rapidly changed-window states on an identified device/emulator. Record whether the reported mismatch is reproduced. Regardless of that result, verify the specified visible game/all-games/date scopes, including large fonts and both themes. Use existing headline/window tests for preservation and focused UI mapping/semantics checks for the new contract.

Coordinate figure marker placement with PR #176 if it is applied. Scope text remains essential inline content; a provenance marker is not a substitute for identifying whose minutes a number represents. `4c` will use the same explicit period convention, with its fixed comparison dates visibly identified as independent of the main selector.

## Risks / Trade-offs

- Duplicate period text can add density → use one concise represented-date label shared by the game and aggregate blocks.
- Long game titles and larger fonts → allow text wrapping and protect role/date labels from truncation.
- Artwork loading can make scope appear inconsistent → keep name/role/minutes independent of successful image loading.
- A current period's full identity differs from represented dates → name elapsed scope without changing navigation or denominators.
- Symptom not reproduced → report the evidence boundary and avoid claiming a selection-algorithm fix.

## Migration Plan

Capture the baseline, add the scope presentation model/copy, then update the overview and its checks. No migration or new request is planned. Rollback restores the overview while preserving existing data. Apply before `4c`; `1c`/`2c` require neither this change nor its implementation. Validate representative device renders, accessibility, tests, compile/lint, and strict OpenSpec consistency.

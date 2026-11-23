# Design

## Context

The existing Analytics projection already has bounded date windows, previous-range eligibility, inspected-day detail, session summaries and leading games. The framing change establishes one visible represented scope for the next two Analytics phases.

## Goals / Non-Goals

Make the underlying distribution inspectable offline. Keep one canonical local source per metric and preserve hidden-game exclusion, recovery semantics, and selected-day navigation.

## Decisions

### Reuse period and evidence projections

Resolve past comparison bounds through `AnalyticsWindow` and reuse canonical dated activity for period and game totals. Add bounded group queries plus earliest and previous positive dates per eligible title for variety and returns. Use a 30-full-day gap as the first return threshold and stable game-ID tie breaks. Use separate session-only reads for median and fixed duration buckets.

### Make comparison and denominator policy explicit

Report play and activity counts with both dates, remove percentage deltas when the baseline is zero and hide comparisons with unsupported coverage. Calculate current-tag Focus minutes over visible dated minutes and disclose the current tag membership. Calculate quest consistency over known outcomes for evidenced active days and show unavailable coverage.

### Keep day interaction shared

Reuse the selected-day presentation and its accessible alternatives in the calendar. Aggregate bars to weeks or months for long windows while retaining daily date inspection. Allocate top-game and remainder shares from the same total so rounding and hidden removals cannot create unaccounted overage.

## Risks / Trade-offs

A recorded range is bounded evidence and can contain outages. Avoid writing a complete-observation claim based on the earliest record. Keep query invalidations keyed to account, window, canonical ledger and eligibility.

## Migration Plan

Introduce bounded reads, pure derivations and presentation incrementally. Retain existing period controls and capture representative narrow and large-text baselines.

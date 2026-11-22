# Design

## Context

`AnalyticsWindow` already resolves elapsed activity bounds and comparable dates. The current UI uses an active-day average label and a start-hour session projection. Playtime recovery can provide dated minutes without a session, and the stored quest result can differ from total minutes.

## Goals / Non-Goals

Reconcile existing Analytics presentation and recorded period outcomes before adding new charts. Keep the overview snapshot and Personal Momentum date contract.

## Decisions

### Use represented date bounds everywhere in the view

Audit every denominator and combine results with their snapshot dates. Keep current calendar periods through today and clip the first represented period at the earliest supported record. Use authoritative daily quest outcomes, with unavailable dates identified. Preserve calendar period stepping.

### Make present derivations explicit

Use a localized Avg / active day label. Present the current and longest streaks plus rarity under a separate lifetime heading. For Activity days only, retain displayed dates and expose skipped dates in the accessible inspection path. Relabel the time-of-day section as session-start attribution, preserving current start buckets and timing-free exclusions. Splitting durations across clock buckets could imply timing precision unavailable in current sessions.

## Risks / Trade-offs

Calendar labels can represent the whole month while metrics use elapsed dates. Keep the selected calendar identity and included date range legible independently, including while an earlier snapshot is retained.

## Migration Plan

Update copy, scoped view models, and screenshots together. No data migration.

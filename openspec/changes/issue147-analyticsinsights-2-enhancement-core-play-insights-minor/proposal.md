# Proposal

## Why

The Analytics screen has a comparison headline and leading-game list. A compact overview should expose comparable period figures and let the user inspect the distribution behind them. [Issue #147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147)

## What Changes

- Expand period comparison, activity calendar, play mix and variety.
- Add session distribution, Focus share, and quest consistency using the existing local evidence and shared day inspection.

## Capabilities

### New Capabilities

- `play-insights` — Window-scoped local game, session and activity statistics.

### Modified Capabilities

- `app-ui` — Accessible core Analytics insights and date inspection.

## Impact

Second change in `analyticsInsights`. Requires `issue147-analyticsinsights-1-fix-analytics-period-framing-patch`. Android Analytics presentation and bounded local reads.

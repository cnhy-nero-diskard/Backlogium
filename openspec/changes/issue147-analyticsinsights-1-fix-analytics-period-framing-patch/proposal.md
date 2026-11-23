# Proposal

## Why

The featured-game scope and Personal Momentum shipped. The selected period still needs clear elapsed-day, activity-average, clock-bucket, and lifetime framing before adding more metrics. [Issue #147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147)

## What Changes

- Correct period labels, denominators and active-only date presentation.
- Put lifetime figures in a visibly separate group and describe the existing clock attribution accurately.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui` — Correct Analytics period, lifetime, active-day and clock framing.

## Impact

First change in `analyticsInsights`. Builds on `improve-activity-insights-accessibility` and `3c-clarify-analytics-featured-game-scope`. Keeps `4c-add-personal-play-momentum` on its existing fixed dates. Android Analytics UI and window model.

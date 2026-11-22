# Design

## Context

The app already has an earned-event write/recovery contract, achievement unlock outbox, Android permission handling and shared-game admission notices. The first `progressAlerts` change supplies validated cold/warm game navigation.

## Goals / Non-Goals

Add the requested event categories and recap with stable delivery, preserving the current in-app flow and Android controls.

## Decisions

### Put category deliveries behind committed boundaries

Observe committed quest and streak events without consuming their existing in-app claims. Capture owned acquisitions from the finalized poll arrival set. Move automatic shared admissions into the same dispatch policy while preserving the existing channel and fallback announcement. Use stable account-scoped event keys and notification tags so process recovery or worker retry cannot create a second visible delivery. Notification suppression is terminal for an event under the current preference.

### Use authoritative completion evidence

Check for a known incomplete to fully unlocked transition within the coordinated achievement merge. Only a complete confirmed player-state set can qualify. Keep the smart collection's estimate-based rule for its current surface, with no notification inferred from estimates.

### Schedule the recap by local date

Use best-effort local-day work with one receipt per account and date. Schedule the next eligible day as time-zone, account or preference state changes and recheck when the worker executes. Read existing daily activity and supported dated unlock evidence. Use absolute local dates in the payload and summarize missing coverage.

### Preserve channel choices

Keep the Family Shared channel ID and existing delivery default. Add a separate owned-acquisition preference and channel. Introduce the other new categories default-off and expose their Android channel settings. Keep shared-channel and achievement-alert migrations independent.

## Risks / Trade-offs

Android can defer background work. A missed day can safely be skipped. Claim a delivery durably before posting and use stable notification tags with only-alert-once replacement. If a crash leaves posting ambiguous, suppress a retry that could repeat an alert. Permission denial and channel suppression still leave in-app results available.

## Migration Plan

Use additive local preferences and delivery storage with an empty dispatch baseline. Honor the existing shared channel during upgrade and exclude operational dispatch state from export so restore cannot replay events.

# Design

## Context

The app already has an earned-event write/recovery contract, achievement unlock outbox, Android permission handling and shared-game admission notices. The first `progressAlerts` change supplies validated cold/warm game navigation.

## Goals / Non-Goals

Add the requested event categories and recap with stable delivery, preserving the current in-app flow and Android controls.

## Decisions

### Persist notification obligations independently

Every committed quest-met or streak-broken event gets a durable notification receipt keyed by account, category, and stable event identity (the quest date or the streak-break transition/date). Persist it with earned-event finalization, or retain an idempotent crash-recovery handoff until the receipt exists; the handoff must survive independently of the foreground pending-event list. `ProgressEventRepository.acknowledge()` may clear the in-app claim but must not remove or acknowledge this receipt. A worker recovers pending receipts after process death and uses the account-scoped event key as its stable notification identity.

Earned-event finalization is the eligibility boundary: create each receipt as pending only when its category preference and Android notification permission/channel allow delivery; otherwise persist it as terminally suppressed. Recheck the active account and current controls immediately before posting, and terminally suppress a receipt if they no longer allow delivery. Later enabling a preference or permission does not recreate or reopen receipts from in-app claims or historical progress. Restore excludes notification receipts and does not reconstruct them.

### Put category deliveries behind committed boundaries

Capture owned acquisitions from the finalized poll arrival set. Move automatic shared admissions into the same dispatch policy while preserving the existing channel and fallback announcement. Use stable notification tags with only-alert-once replacement so a recovered worker cannot create a second visible delivery.

### Use authoritative completion evidence

Check for a known incomplete to fully unlocked transition within the coordinated achievement merge. Only a complete confirmed player-state set can qualify. Keep the smart collection's estimate-based rule for its current surface, with no notification inferred from estimates.

### Schedule the recap by local date

Use best-effort local-day work with one receipt per account and date. Schedule the next eligible day as time-zone, account or preference state changes and recheck when the worker executes. Read existing daily activity and supported dated unlock evidence. Use absolute local dates in the payload and summarize missing coverage.

### Preserve channel choices

Keep the Family Shared channel ID and existing delivery default. Add a separate owned-acquisition preference and channel. Introduce the other new categories default-off and expose their Android channel settings. Keep shared-channel and achievement-alert migrations independent.

## Risks / Trade-offs

Android can defer background work. A missed day can safely be skipped. Keep a receipt pending until dispatch, and use stable notification tags with only-alert-once replacement for retries. If a crash leaves posting ambiguous, suppress a retry that could repeat an alert; this may miss a system alert but never consumes the independent in-app claim. Permission denial and channel suppression leave in-app results available.

## Migration Plan

Use additive local preferences and delivery storage with an empty dispatch baseline. Honor the existing shared channel during upgrade and exclude operational dispatch state from export so restore cannot replay events.

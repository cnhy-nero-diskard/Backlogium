# Design

## Context

The app already has an earned-event write/recovery contract, achievement unlock outbox, Android permission handling and shared-game admission notices. The first `progressAlerts` change supplies validated cold/warm game navigation.

## Goals / Non-Goals

Add the requested event categories and recap with stable delivery, preserving the current in-app flow and Android controls.

## Decisions

### Persist notification obligations independently

Every committed progress-alert event gets a durable, account-scoped receipt keyed by category and stable source-event identity. Persist it with the source commit, or persist an idempotent crash-recovery handoff in that same commit and retain it until the receipt exists. The handoff must survive independently of the foreground pending-event list and notification scheduling. Quest-met and streak-broken receipts follow earned-event finalization. An owned acquisition uses the post-baseline arrival captured by the poll commit and reuses the durable account/game admission identity from #159; it must not depend on the later best-effort `setAcquiredGames(...)` write. An automatic shared-game receipt is created only with the successful new-row admission. A completion receipt is created with the achievement merge that confirms a known-incomplete game has become complete. `ProgressEventRepository.acknowledge()` may clear an in-app claim but must not remove or acknowledge a notification receipt. A worker recovers pending receipts after process death and uses the account-scoped event key as its stable notification identity.

Each source commit is the eligibility boundary: a receipt is pending only when its category preference and Android notification permission/channel allow delivery; otherwise it is terminally suppressed. Where those controls cannot be committed with the source transaction, the durable handoff must preserve the event-boundary eligibility decision until the receipt is saved, rather than reopening historical events from current settings. Recheck the active account and current controls immediately before posting, and terminally suppress a receipt if they no longer allow delivery. Later enabling a preference or permission does not recreate or reopen receipts from in-app claims or historical progress. First-sync/initial completion baselines, restore, import, replay, and other non-admission writes create no alert obligation. Restore excludes notification receipts and does not reconstruct them.

Register every channel used by progress-alert receipts, including default-off categories, during app initialization before any source event checks channel state. Use stable IDs and idempotent registration so Android retains existing channel settings; never switch IDs to bypass a user-disabled channel. A missing channel means registration is incomplete, not that the user disabled it: retain the durable handoff and retry registration before deciding eligibility. Channel-based suppression requires a registered channel whose settings disallow delivery; denied permission, disabled app-wide notifications, or category opt-out may also terminally suppress a receipt.

### Put category deliveries behind committed boundaries

Create owned-acquisition obligations from the finalized poll arrival set under the same durable admission identity used by #159, not from a second best-effort post-commit write. Commit automatic shared-admission obligations with the successful game-row insert while preserving the existing Family Shared channel and fallback announcement. Detect verified completion at the coordinated achievement merge and commit its obligation with the confirmed incomplete-to-complete transition. Use stable notification tags with only-alert-once replacement so recovery or worker retry cannot create a second visible delivery.

### Use authoritative completion evidence

Check for a known incomplete to fully unlocked transition within the coordinated achievement merge. Only a complete confirmed player-state set can qualify; a first complete baseline is seeded without an alert. Keep the smart collection's estimate-based rule for its current surface, with no notification inferred from estimates.

### Schedule the recap by local date

Use best-effort local-day work with one receipt per account and date. Schedule the next eligible day as time-zone, account or preference state changes and recheck when the worker executes. Read existing daily activity and supported dated unlock evidence. Use absolute local dates in the payload and summarize missing coverage.

### Preserve channel choices

Keep the Family Shared channel ID and existing delivery default. Add a separate owned-acquisition preference and channel. Introduce the other new categories default-off and expose their Android channel settings. Keep shared-channel and achievement-alert migrations independent.

## Risks / Trade-offs

Android can defer background work. A missed day can safely be skipped. Keep a receipt pending until dispatch, and use stable notification tags with only-alert-once replacement for retries. If a crash leaves posting ambiguous, suppress a retry that could repeat an alert; this may miss a system alert but never consumes the independent in-app claim. Permission denial and channel suppression leave in-app results available.

## Migration Plan

Use additive local preferences and delivery storage with an empty dispatch baseline. Honor the existing shared channel during upgrade and exclude operational dispatch state from export so restore cannot replay events.

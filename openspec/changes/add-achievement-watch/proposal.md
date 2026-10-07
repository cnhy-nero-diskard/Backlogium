## Why

Achievements currently arrive through ordinary sync, targeted admission/refresh, and deferred reconciliation. Those paths do not give a running game a steady 30–60-second achievement refresh. Recover this proposal from PR #84 while keeping the existing playtime ledger and live-monitor lifecycle intact.

## What Changes

- Refresh only the visible, currently running game while the opted-in live monitor is active; default to 60 seconds, with a 30-second option.
- Compare successfully committed watch observations. The first observation seeds a baseline; later newly observed unlocks can produce grouped alerts.
- Defer a watch observation needing missing rarity data without writing it or advancing its baseline.
- Store exact pending unlock groups durably with the achievement write, then deliver through one claimed surface with stable notification identities.
- Add independent watch/alert controls, quiet error handling, diagnostics, and a restrained in-app haptic.

## Capabilities

### New Capabilities

- `achievement-watch`: lifecycle, cadence, committed baselines, cancellation, and recovery.

### Modified Capabilities

- `steam-achievements`: a targeted refresh path sharing existing serialization and merge rules.
- `progress-events`: grouped unlock payloads, durable dispatch, and explicit presentation priority.
- `app-ui`: accessible in-app alerts and game-detail notification destinations.
- `app-settings`: watch interval and independent alert preferences.
- `haptic-feedback`: an unlock intent delivered with the visible in-app event.
- `app-diagnostics`: bounded watch observations and deferred-fetch outcomes.

## Impact

Planning only. Implementation will touch the presence/achievement repositories, progress-event delivery, settings, and notification/UI boundaries. A Room outbox migration is required; this replaces the old proposal's unsupported claim that existing progress marks can store arbitrary unlock groups. The current DataStore progress-transition protocol remains in place.

Recovered from #84 at `5c1296e`, reconciled against master `d46a711c`. Addresses part of #159 and #166, tracked by #174. Missing-game coverage investigations and other notification categories remain separate. No new playtime tracker, full-library fast poll, cloud poller, or process-death cadence promise.

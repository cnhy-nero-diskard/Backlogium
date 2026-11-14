## Why

Unplayed backlog games have no achievement list because routine fetching excludes owned games with zero playtime, and playing games lack a steady 30–60-second achievement refresh. Extend the recovered PR #84 proposal with cached achievement browsing before play while keeping the existing playtime ledger and live-monitor lifecycle intact.

## What Changes

- Load and cache a visible tracked game's achievement list when its detail is opened, including games with zero playtime, without scanning the backlog.
- Keep the achievement catalogue separate from personal unlock progress; unavailable/private/no-stats responses must not become confirmed empty lists or invented locked progress.
- Render cached content offline, provide bounded retry for failed loads, and explain the scope of full achievement refresh.
- Refresh only the visible, currently running game while the opted-in live monitor is active; default to 60 seconds, with a 30-second option.
- Compare successfully committed watch observations. The first observation seeds a baseline; later newly observed unlocks can produce grouped alerts.
- Defer a watch observation needing missing rarity data without writing it or advancing its baseline.
- Store exact pending unlock groups durably with the achievement write, then deliver through one claimed surface with stable notification identities.
- Add independent watch/alert controls, quiet error handling, diagnostics, and a restrained in-app haptic.

## Capabilities

### New Capabilities

- `achievement-watch`: lifecycle, cadence, committed baselines, cancellation, and recovery.

### Modified Capabilities

- `steam-achievements`: on-demand catalogue hydration before play and a watch refresh path sharing existing coordination and merge rules.
- `progress-events`: grouped unlock payloads, durable dispatch, and explicit presentation priority.
- `app-ui`: cached achievement browsing, truthful loading/progress states, accessible alerts, and game-detail notification destinations.
- `app-settings`: watch interval, independent alert preferences, and clear full-refresh scope.
- `haptic-feedback`: an unlock intent delivered with the visible in-app event.
- `app-diagnostics`: bounded detail/watch observations with catalogue, player-state, and deferred-fetch outcomes.

## Impact

Planning only. Implementation will touch the presence/achievement repositories, game detail, progress-event delivery, settings, and notification/UI boundaries. Room migrations for independently cached catalogue data and the outbox are required; this replaces the old proposal's unsupported claim that existing progress marks can store arbitrary unlock groups. The current DataStore progress-transition protocol remains in place.

Recovered from #84 at `5c1296e`, reconciled against master `d46a711c`. Addresses part of #159 and #166, tracked by #174. The reported Remake Intergrade, Rebirth, and Far Cry 5 gaps were traced on 2026-11-14 to zero-play owned-game exclusion; the user confirmed these games are unplayed. This revision covers browsing those games before play. Proactive hydration of every newly discovered title, unrelated sync-recovery failures, and other notification categories remain separate. No new playtime tracker, full-library fast poll, cloud poller, or process-death cadence promise.

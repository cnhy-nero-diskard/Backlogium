# Tasks — Prominent now-playing presence

> **Hard constraint:** the live session timer is display-only. Nothing in `domain/` may read it,
> and `GamificationUpdater` must remain untouched. XP/quests/streaks continue to derive solely
> from `SessionDiffer`-synthesized `Session` rows.

## 1. Live session state (persisted)
- [ ] 1.1 `SettingsDataStore`: add `liveSessionAppId: Long?` and `liveSessionStartedAt: Long?`
  keys with a combined flow
- [ ] 1.2 Write on first in-game observation; replace when the observed game changes; clear when
  not in game
- [ ] 1.3 Unit test the transitions: none → in game → same game → different game → not in game

## 2. Repository rework
- [ ] 2.1 `LiveStatusRepository`: replace the cold `flow { while(true) }` with an application-scoped
  shared state flow plus an explicit `startPolling()`/`stopPolling()` (or a service-driven collector)
- [ ] 2.2 Keep the existing failure behavior: a failed fetch retains the last emitted value
- [ ] 2.3 Emit `NowPlaying` **plus** the live session start time so consumers get one coherent state
- [ ] 2.4 Verify Home still works when the service is not running (observer-only, no polling) —
  degraded but not broken
- [ ] 2.5 `LiveStatusRepositoryTest`: presence transitions drive start-time writes correctly

## 3. Foreground service
- [ ] 3.1 New `PresenceService`: foreground service, `dataSync` type, owns the 30s poll
- [ ] 3.2 Stops itself when a poll reports not-in-game
- [ ] 3.3 `AndroidManifest`: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, service
  declaration with `android:foregroundServiceType="dataSync"`
- [ ] 3.4 `SteamSyncWorker`: after its poll, start the service if the player is in a game
  (the detection path when the app was never opened)
- [ ] 3.5 Home start-on-open: start the service if in game when Home is first observed
- [ ] 3.6 Idempotent start (never stack instances); safe stop when already stopped

## 4. Ongoing notification
- [ ] 4.1 New notification channel, separate from `hltb_refresh`, `IMPORTANCE_LOW`
- [ ] 4.2 Builder: `setOngoing(true)`, `setOnlyAlertOnce(true)`, content "Playing <name>" +
  elapsed; content intent opens the app
- [ ] 4.3 Update elapsed on a ~60s cadence, independent of the poll
- [ ] 4.4 Remove the notification when the service stops
- [ ] 4.5 Skip silently when `POST_NOTIFICATIONS` is not granted (mirror `HltbRefreshWorker`)

## 5. Home card
- [ ] 5.1 Replace `NowPlayingBanner` with an enlarged card: large game art, name, elapsed time
- [ ] 5.2 Use the tertiary steel-blue container, **not** `primaryContainer` — gold stays reserved
  for milestone moments
- [ ] 5.3 Client-side 1s ticker for the displayed elapsed value (no network)
- [ ] 5.4 Reuse the existing themed loading/error art fallbacks
- [ ] 5.5 Copy must read as time since detection, not as an exact launch time
- [ ] 5.6 `HomeUiState`: add elapsed/started-at; keep the card conditional so layout is unchanged
  when not in game

## 6. Verification
- [ ] 6.1 Confirm by inspection that no `domain/` code and no `GamificationUpdater` path reads
  live session state
- [ ] 6.2 Manually verify: background the app mid-session → notification persists and ticks;
  quit the game → notification clears and the service stops
- [ ] 6.3 Kill the app mid-session → reopen → elapsed continues from the persisted start time
- [ ] 6.4 Measure battery over a ~1h session before declaring done
- [ ] 6.5 Confirm the `dataSync` foreground-service type against current Play policy
- [ ] 6.6 Update `docs/ui-screens-descriptor.md`

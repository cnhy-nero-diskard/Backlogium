# Make now-playing detection prompt and reliable

## Why

`enhance-now-playing` delivered the presence *presentation* — the full-bleed panel, the elapsed
timer, the Library dot, the ongoing notification. What it did not deliver is a reliable way to
**notice that a game started**. Presence is detected correctly when it is detected at all; the
defect is entirely in *when* the check runs.

Four independent gaps, each of which alone is enough to make the indicator absent:

1. **The sync's presence check sits behind the achievement sweep.** `SteamSyncWorker` fetches
   `GetPlayerSummaries` early (`:79-81`) — so `gameid` is in hand within about a second — then
   calls `persistPoll` (`:83`), which runs `achievementRepository.syncLibraryGames()` (`:187`):
   several hundred sequential HTTP requests. Only after that returns does `:86-88` start the
   presence service. For a 300-game library the observed effect is a presence signal that is
   already ~4 minutes stale on the syncs where the sweep fires, which is every fourth run.

2. **Nothing re-checks presence while the app is running.** `enhance-now-playing` replaced the
   observation-scoped poll (`stateIn(WhileSubscribed)`) with an app-scoped `StateFlow` plus an
   explicit `startPolling()` owned solely by `PresenceService`. Correct for background tracking,
   but it removed the mechanism that started polling when a screen began observing. The only
   remaining cold-start trigger in the app is `HomeViewModel.init` (`:65-74`), which fires once
   per ViewModel instance — and Home is a `NavHost` destination that is never popped
   (`BacklogiumAppRoot.kt:134`). **Launching a game while the app is already open is therefore
   invisible** until the periodic worker wins the race against its own sweep. Confirmed on device:
   the panel appears reliably on a cold start and not otherwise.

3. **A private games list disables worker-path detection entirely.** `SteamSyncWorker:67-71`
   returns `Result.success()` when `getOwnedGames` comes back empty, *before* the presence check
   at `:86`. Presence comes from `GetPlayerSummaries` and does not depend on the owned-games list
   at all, so this coupling is incidental — but it means a player whose games list is private
   never gets presence from the worker, only from a cold app start.

4. **Service death silently destroys session state.** `LiveStatusRepository.stopPolling()`
   (`:114-119`) resets `_liveStatus` *and* calls `settings.clearLiveSession()`. It runs from
   `PresenceService.onDestroy` (`:91`), which fires on process death, low-memory kill, and
   Android 15's `onTimeout` (`PresenceService.kt:86-88`) — not just on the game ending. The
   indicator vanishes mid-session and the elapsed timer restarts from zero on re-detection. This
   also defeats the persisted timestamp that `enhance-now-playing` added specifically to survive
   restarts.

Compounding 4: the persisted live session is **write-only with respect to visibility**. Nothing
reads `liveSession` back to reconstruct `NowPlaying`; `_liveStatus` starts at `LiveStatus()` on
every process start (`:88`). The DataStore keys preserve the elapsed *number* but never the
*panel*, so there is an unavoidable blank window on every cold start even when the app knows
a session is in progress.

Separately, `enhance-now-playing`'s proposal recorded that "`POST_NOTIFICATIONS` is already
requested for the HLTB worker." That is not the case — the permission is declared
(`AndroidManifest.xml:6`) but **never requested at runtime anywhere in the app**. On API 33+ the
ongoing notification silently no-ops (`PresenceNotifications.kt:43, 55-57`) until the user
discovers the system settings toggle unaided. The feature works only for users who go looking.

## What Changes

- **Check presence first, and unconditionally.** The presence check moves ahead of both
  `persistPoll` and the empty-games early return, so it is the first thing a sync resolves and no
  library-side condition can skip it.
- **Re-check presence when the app comes to the foreground**, replacing the one-shot
  `HomeViewModel.init` check. Every return to the app becomes a detection opportunity, which is
  the case the user actually hits.
- **Distinguish "the game ended" from "the observer died."** Stopping observation for lifecycle
  reasons stops the poll and leaves persisted session state alone; only an observation that
  reports the player is no longer in a game clears it.
- **Rehydrate presence from persisted session state on cold start**, so a known in-progress
  session shows immediately rather than after the first network round-trip.
- **Request `POST_NOTIFICATIONS` at runtime**, so the ongoing notification works on a fresh
  install without manual intervention.
- **Correct the stale `live-status` polling requirement.** The spec still mandates the
  observation-scoped, foreground-only cadence that `enhance-now-playing` removed in
  implementation, describing behaviour the code no longer has.

## Capabilities

### Modified Capabilities
- `live-status`: presence acquisition gains explicit trigger requirements (sync-first,
  app-foreground) and separates poll lifecycle from session-state lifetime. The superseded
  foreground-only cadence requirement is replaced to match the service-owned model.
- `app-ui`: notification permission is requested in-app rather than assumed.

## Impact

- **Affected code:** `SteamSyncWorker` (statement ordering only — no new calls);
  `LiveStatusRepository` (`stopPolling` semantics, cold-start rehydration);
  `HomeViewModel` (drop the `init` check); a lifecycle-scoped foreground observer at the app
  shell; a runtime permission request.
- **No new network calls.** Items 1 and 3 are reorderings of requests the sync already makes.
  Item 2 replaces one check-per-ViewModel with one check-per-foreground.
- **Relationship to `optimize-steam-sync`:** independent and complementary. This change stops
  presence from *waiting on* the sweep; `optimize-steam-sync` makes the sweep small. Either alone
  is a real improvement; neither blocks the other. Landing this one first is preferable — it is
  low-risk statement reordering and immediately observable on device.
- **Battery:** a foreground re-check is one request per app-foreground event. The 30s in-game
  cadence and its start/stop conditions are unchanged.

## Non-goals

- **Changing how presence is detected.** `GetPlayerSummaries`/`gameid` is correct and verified
  working; only trigger timing and state lifetime change.
- **Reducing achievement sweep cost.** That is `optimize-steam-sync`.
- **Polling while not in game.** Unchanged: a foreground re-check is a single request, not a
  standing loop.
- **Surfacing privacy diagnostics.** Presence still degrades silently to "not in game". Worth
  doing, but it is a distinct observability concern and the device evidence shows privacy is not
  the active problem here.
- **Replacing the foreground service.** The ongoing notification is a wanted feature; the service
  that enables it stays.
- **Notification permission rationale UI or re-prompt flows.** A single standard request at an
  appropriate moment; no custom education screens.

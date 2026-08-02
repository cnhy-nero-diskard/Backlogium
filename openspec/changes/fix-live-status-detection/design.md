# Design — reliable now-playing detection

## Context

Detection itself is sound: `GetPlayerSummaries` → `gameid` → `NowPlaying.InGame`, verified on
device (the panel appears reliably on a cold start with a game running, and the profile's game
details are public). Every defect in scope is about *when* that check runs and *how long* its
result survives. So this design is deliberately about control flow and lifecycle, and touches no
parsing, no DTOs, and no rendering.

## The trigger model

Today there are two triggers and both are the wrong shape:

```
   HomeViewModel.init  ──▶ checkNow()      once per ViewModel instance,
                                           and Home's nav entry is never popped
                                           ⇒ effectively once per process

   SteamSyncWorker     ──▶ checkNow()      after ~800 serial requests
                                           ⇒ up to ~4 min stale
```

Target:

```
   app foreground      ──▶ checkNow()      every foreground event
   sync run (first)    ──▶ checkNow()      before any library work
   PresenceService     ──▶ 30s loop        unchanged, while in game
```

### Where the foreground trigger lives

`ProcessLifecycleOwner` (`androidx.lifecycle:lifecycle-process`) over `Activity.onResume` or a
Compose `LifecycleEventEffect`:

- It is process-scoped, so it fires once per app-foreground rather than once per activity or once
  per composition — no duplicate requests from recomposition, and no dependence on which screen
  happens to be showing.
- Presence is not a Home concern. The current placement in `HomeViewModel` is why navigating away
  and back does nothing; binding the trigger to the app rather than a screen removes that class of
  bug rather than relocating it.

An `ON_START` observer registered in `BacklogiumApp` calls `checkNow()`, then starts
`PresenceService` if the result is `InGame`. On-device verification showed that one check is not
enough: returning immediately after launching a game can beat Steam's presence propagation, and a
concurrent achievement sweep then leaves no later detection trigger. The observer therefore makes
one immediate attempt plus up to three retries five seconds apart. The retry job is cancelled when
the app backgrounds, so this is a bounded foreground detection window rather than standing polling
while not in a game. `HomeViewModel` remains a pure observer of `liveStatus`, like
`ProfileHeaderViewModel` already is.

Rejected: making `liveStatus` a `WhileSubscribed` flow again. That is what `enhance-now-playing`
moved away from, and for a good reason — observation-scoped polling cannot track presence while
backgrounded, which is the feature. The fix is to add an app-lifecycle trigger, not to re-couple
the cadence to observation.

## Worker ordering

Presence needs to be resolved before both the empty-games return and `persistPoll`. The summary
fetch is already best-effort (`runCatching`), so hoisting it changes no failure semantics:

```
  BEFORE                                AFTER
  ─────────────────────────             ─────────────────────────
  getOwnedGames                         getPlayerSummaries   ◀── best-effort
  if (games.isEmpty()) return           start presence if in-game
  getSteamLevel                         ─────────────────────────
  getPlayerSummaries                    getOwnedGames
  persistPoll { ..., sweep }            if (games.isEmpty()) return
  start presence if in-game             getSteamLevel
                                        persistPoll { ..., sweep }
```

Note `getOwnedGames` is the one *fatal* call in the run (unwrapped, so a throw yields
`Result.retry()`). Moving the summary above it means presence is resolved even on runs that go on
to fail entirely — which matches the new requirement and is strictly better than today, where a
transient owned-games failure also costs you presence.

`persistPoll` still needs the summary for `mergePlayerIdentity`, so it continues to receive it as a
parameter. No duplicate request: one fetch, used by both the presence decision and the identity
merge.

## Separating poll lifetime from session lifetime

`stopPolling()` currently conflates three things:

```kotlin
// LiveStatusRepository.kt:114-119
pollingJob?.cancel()                              // 1. stop the loop
_liveStatus.value = LiveStatus()                  // 2. forget in-memory state
scope.launch { settings.clearLiveSession() }      // 3. erase persisted session   ◀── the bug
```

Callers want two distinct things, and only one of them wants (3):

| Caller | Reason | Wants |
|---|---|---|
| `PresenceService.onDestroy` | process death, low memory, Android 15 `onTimeout` | 1 only |
| observation reports not-in-game | the game actually ended | 1 + 2 + 3 |

Split into `stopPolling()` (cancel the loop; leave state) and the existing not-in-game path in
`checkNow()`, which already clears the session correctly via `LiveSessionTracker` (`:136-138`).
That is the point: **`checkNow` is already the only place that can legitimately know a game
ended**, and it already handles it. `stopPolling` should not be a second, less-informed clearing
path.

Consequence worth stating: after this change, a killed service leaves stale `InGame` state visible
until the next trigger. That is the correct trade — a foreground re-check is now guaranteed on
return, and showing a session that ended two minutes ago is a far smaller defect than resetting a
two-hour timer to zero.

## Cold-start rehydration

`_liveStatus` initialises to `LiveStatus()` (`:88`), so the panel is hidden on every cold start
until a network round-trip completes, even with a session recorded in DataStore. Rehydration reads
`settings.liveSession` once at construction and seeds `_liveStatus` with `NowPlaying.InGame` when
an `appId` is present, resolving the name and icon from `gameDao` — the same source `fetch()` uses
for the icon (`:175-177`), so no new data dependency.

The first `checkNow()` then reconciles. `LiveSessionTracker.next` already produces the right
outcome for all three cases (same game → keep start; different game → new start; not playing →
clear), so rehydration needs no new transition logic.

**Guard needed.** `LiveSessionState.appId` is nullable, and the tracker compares
`previous.appId == nowPlaying.gameId` (`LiveSessionTracker.kt:22`). When Steam's `gameid` fails to
parse (`LiveStatusRepository.kt:170`) the persisted `appId` is null, so `null == null` makes the
*next* unparseable observation look like a continuation of the same session. Rehydration must
therefore seed only when `appId` is non-null, and this comparison should require a non-null
`appId` on both sides to match. Pre-existing latent bug; rehydration is what would make it
reachable.

## Notification permission

The permission is declared but never requested; on API 33+ `PresenceNotifications.update()`
no-ops silently (`:43, 55-57`). A standard `rememberLauncherForActivityResult` +
`RequestPermission` contract at the app shell, fired once when the permission is in its initial
undetermined state.

Requesting it at first launch alongside onboarding is preferable to requesting it at first
detection: the detection moment is typically while the user is *in a game and not looking at the
phone*, which is the worst possible time to raise a system dialog.

## Verification

Device checks, since the failure modes are all lifecycle-shaped and none reproduce in a JVM test:

1. **App-open launch** — app open, launch a game, return to app. Panel appears. *(This is the
   reported bug; it currently fails.)*
2. **Sweep no longer blocks** — force a sync with achievement data stale, launch a game, confirm
   the panel appears in seconds rather than minutes.
3. **Service death** — kill `PresenceService` (`adb shell am stopservice`), re-foreground, confirm
   elapsed time continues from the original start rather than resetting.
4. **Cold start** — with a game running, cold-start the app; panel is present in the first frame,
   not after a delay.
5. **Fresh install** — notification permission is requested, and the ongoing notification appears
   without visiting system settings.

Unit-testable without a device: the tracker's non-null `appId` guard, and `checkNow` retaining
session state on a failed fetch. `LiveStatusRepositoryTest` and `LiveSessionTrackerTest` already
exist and cover adjacent behaviour.

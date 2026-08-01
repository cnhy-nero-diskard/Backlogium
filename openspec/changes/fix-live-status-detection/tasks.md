# Tasks — reliable now-playing detection

## 1. Resolve presence first in the sync

- [x] 1.1 In `SteamSyncWorker.doWork`, hoist the `getPlayerSummaries` `runCatching` block above the
      `getOwnedGames` call
- [x] 1.2 Move the `if (!summary?.gameId.isNullOrBlank()) presenceServiceStarter.start()` block to
      immediately after the summary fetch, above the empty-games early return
- [x] 1.3 Confirm `persistPoll` still receives the same `summary` instance for `mergePlayerIdentity`
      — one fetch, two consumers, no duplicate request
- [x] 1.4 Verify the empty-games path (`recordError` + `Result.success()`) now runs *after* presence
      has been acted on

## 2. Re-check presence on app foreground

- [x] 2.1 Add the `androidx.lifecycle:lifecycle-process` dependency if not already present
- [x] 2.2 Register a `ProcessLifecycleOwner` `ON_START` observer in `BacklogiumApp` that calls
      `liveStatusRepository.checkNow()` and starts `PresenceService` when the result is `InGame`
- [x] 2.3 Remove the `init` block from `HomeViewModel` and drop its `PresenceServiceStarter`
      dependency, leaving it a pure observer of `liveStatus`
- [x] 2.4 Confirm `startPolling()` idempotence covers repeat foregrounding while already in game
      (`LiveStatusRepository.kt:100`) — no duplicate loops, no session-start reset

## 3. Separate poll lifetime from session lifetime

- [x] 3.1 Reduce `LiveStatusRepository.stopPolling()` to cancelling the poll job only; remove the
      `_liveStatus` reset and the `clearLiveSession()` call
- [x] 3.2 Confirm the not-in-game path in `checkNow` (`:136-138`) remains the sole clearing path
- [x] 3.3 Verify `PresenceService.onDestroy` no longer erases session state
- [x] 3.4 Confirm a failed fetch still retains prior state via the existing `getOrDefault`
      (`:130-131`)

## 4. Rehydrate presence on cold start

- [x] 4.1 Seed `_liveStatus` from `settings.liveSession` at repository construction, only when the
      recorded `appId` is non-null, resolving name and icon from `gameDao`
- [x] 4.2 Require a non-null `appId` on both sides of the identity comparison in
      `LiveSessionTracker.next` so unparseable observations cannot chain into one session
- [x] 4.3 Confirm the first `checkNow` reconciles rehydrated state for all three cases: same game,
      different game, not playing

## 5. Request notification permission

- [x] 5.1 Add a `RequestPermission` launcher at the app shell for `POST_NOTIFICATIONS`, gated on
      API 33+ and on the permission being in its undetermined state
      *(`minSdk` is 33, so no version gate is needed; "undetermined" is recorded in DataStore,
      since `checkSelfPermission` alone cannot distinguish never-asked from declined)*
- [x] 5.2 Fire it during onboarding / first launch rather than at first game detection
- [x] 5.3 Confirm denial leaves presence tracking fully functional and posts no notification
- [x] 5.4 Confirm the request is not repeated on later launches once answered

## 6. Correct the superseded spec requirement

- [x] 6.1 Replace `live-status`'s "Foreground live polling cadence" requirement with the
      service-owned cadence wording, so the spec describes the shipped model
      *(done as REMOVED + ADDED, this repo's rename form — a `MODIFIED` block under the new name
      would have added a requirement and left the stale one in the main spec)*

## 7. Tests

- [x] 7.1 `LiveSessionTrackerTest`: a null-`appId` observation does not continue a prior session
- [x] 7.2 `LiveStatusRepositoryTest`: `stopPolling` retains in-memory and persisted session state
- [x] 7.3 `LiveStatusRepositoryTest`: construction with a recorded session seeds `InGame` before any
      fetch
- [x] 7.4 `LiveStatusRepositoryTest`: first `checkNow` after rehydration reconciles same-game,
      different-game, and not-playing
- [ ] 7.5 A `SteamSyncWorker` test asserting presence is acted on when the owned-games list is empty
      — **not done, deliberately.** `SteamSyncWorker` is a `CoroutineWorker`: unreachable from a
      pure JVM test (needs `Context` + `WorkerParameters`), and the project has no Robolectric.
      The two ways in were to add Robolectric + `work-testing`, or to extract a Context-free runner
      from `doWork` — both larger than this change's "statement ordering only" scope. Coverage for
      the ordering rests on the code (the presence block now precedes `getOwnedGames`) and on the
      on-device check 8.2 below.

## 8. On-device verification

- [ ] 8.1 App open, launch a game, return to app → panel appears *(the reported bug)*
- [ ] 8.2 With achievement data stale, launch a game during a sync → panel appears in seconds
- [ ] 8.3 Kill `PresenceService`, re-foreground → elapsed time continues, does not reset to zero
- [ ] 8.4 Cold start with a game running → panel present immediately, no blank window
- [ ] 8.5 Fresh install → notification permission requested; ongoing notification appears without
      visiting system settings

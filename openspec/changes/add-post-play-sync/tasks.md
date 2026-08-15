## 1. The Steam endpoint

- [ ] 1.1 Add `RecentlyPlayedGamesDto` mirroring `IPlayerService/GetRecentlyPlayedGames`: `total_count` plus a `games` list carrying `appid`, `name`, `playtime_forever`, and `playtime_2weeks`
- [ ] 1.2 Add `getRecentlyPlayedGames(key, steamId, count)` to `SteamApi` as a plain `@GET` with `@Query` parameters — no `input_json`
- [ ] 1.3 Confirm the new endpoint normalizes correctly under the diagnostics endpoint scheme and that neither `key` nor `steamid` reaches a stored record
- [ ] 1.4 Add a repository method that fetches with `count = 1` and returns the single observation, or nothing when the response is empty

## 2. Session-end transition

- [ ] 2.1 Publish an `InGame(appId)` → `NotPlaying` transition carrying the stopped game's app id, captured from the previous state rather than the new one
- [ ] 2.2 Emit only on leaving `InGame`; a presence change between online, away, snooze, and offline while the same game runs emits nothing
- [ ] 2.3 Verify an observer stopping for lifecycle reasons does not clear session state and therefore publishes no transition, per the existing `live-status` requirement
- [ ] 2.4 Keep publishing free of I/O — no request, no database access, no failure path that can reach presence

## 3. The targeted fetch worker

- [ ] 3.1 Add `PostPlaySyncWorker` taking an app id and an attempt index as input data
- [ ] 3.2 Read the stored `playtimeForever` baseline for that app id before fetching, so "increase" is evaluated against the same value session synthesis will use
- [ ] 3.3 Fetch, and discard the observation when the returned app id is not the one requested
- [ ] 3.4 On an observed increase, apply it through the existing session synthesis and commit path — do not synthesize sessions, credit daily progress, or write derived values in the worker
- [ ] 3.5 On no increase, enqueue the next attempt in the schedule; on the last attempt, end without enqueuing and without returning a retry
- [ ] 3.6 Take the `SteamSyncCoordinator` mutex opportunistically; never fail or skip on contention
- [ ] 3.7 Return `Result.success()` for an exhausted schedule so WorkManager does not back off and re-run a concluded schedule
- [ ] 3.8 Treat a network or API failure as an attempt that observed nothing, continuing the schedule rather than aborting it

## 4. Scheduling

- [ ] 4.1 Add `PostPlaySyncScheduler` enqueuing attempts at 0s, 1m, 3m, and 8m as one-time work with initial delays
- [ ] 4.2 Use a unique work name derived from the app id with `REPLACE`, so re-quitting the same game supersedes its pending schedule
- [ ] 4.3 Confirm two different games stopped in the same window keep independent schedules under their own names
- [ ] 4.4 Take no foreground service and set no expedited flag
- [ ] 4.5 Subscribe the scheduler to the session-end transition at the presence layer's host

## 5. Diagnostics

- [ ] 5.1 Add a post-play trigger to the sync-run trigger vocabulary, carrying the scoped app id
- [ ] 5.2 Record one run per attempt through `SyncRunRecorder`, including attempts that observed no increase
- [ ] 5.3 Confirm the existing retention bound prunes these alongside other runs and that a heavy play day cannot evict all periodic-run history — adjust the bound if it can
- [ ] 5.4 Confirm the Diagnostics screen renders the new trigger without a code change beyond its label

## 6. Tests

- [ ] 6.1 Unit-test that leaving `InGame` publishes the previous game's app id, and that presence changes with the game still running publish nothing
- [ ] 6.2 Unit-test that an observer stopping for lifecycle reasons publishes nothing
- [ ] 6.3 Unit-test the schedule: increase on attempt 1 issues one request; increase on attempt 3 issues three and does not issue a fourth; no increase issues exactly four and then stops
- [ ] 6.4 Unit-test that a response naming a different app id is discarded and attributes no playtime
- [ ] 6.5 Unit-test that a targeted fetch and a periodic poll observing the same increase credit it exactly once, with the second commit recording no session and no minutes
- [ ] 6.6 Unit-test that a playtime decrease emits no session and no negative playtime
- [ ] 6.7 Unit-test that a fetch failure leaves stored data unchanged and does not end the schedule early
- [ ] 6.8 Unit-test that an exhausted schedule returns success rather than retry
- [ ] 6.9 Unit-test that a second quit of the same game replaces the pending schedule rather than doubling it

## 7. Verification

- [ ] 7.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [ ] 7.2 On device with the live monitor enabled: play a game for a few minutes, quit, and confirm the session appears in History without a manual sync
- [ ] 7.3 Confirm Diagnostics shows the post-play attempts, their trigger, the scoped game, and how many requests were issued
- [ ] 7.4 Quit a game and immediately force-stop the app; confirm the session is still recorded
- [ ] 7.5 Launch and quit a game within a few seconds; confirm the schedule exhausts quietly with nothing recorded and no error surfaced
- [ ] 7.6 Confirm the day's total minutes match what a manual "Sync now" would have produced, with no double-count

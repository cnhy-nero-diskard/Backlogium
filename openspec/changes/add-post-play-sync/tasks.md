## 1. The Steam endpoint

- [ ] 1.1 Add `RecentlyPlayedGamesDto` mirroring `IPlayerService/GetRecentlyPlayedGames`: `total_count` plus a `games` list carrying `appid`, `name`, `playtime_forever`, and `playtime_2weeks`
- [ ] 1.2 Add `getRecentlyPlayedGames(key, steamId, count)` to `SteamApi` as a plain `@GET` with `@Query` parameters — no `input_json`
- [ ] 1.3 Confirm the new endpoint normalizes correctly under the diagnostics endpoint scheme and that neither `key` nor `steamid` reaches a stored record
- [ ] 1.4 Add a repository method that fetches with `count = 1` and returns the single observation, or nothing when the response is empty

## 2. Session-end transition

- [ ] 2.1 Publish an `InGame(appId)` → `NotPlaying` transition carrying the stopped game's app id and the time the session ended, both captured from the previous state rather than the new one
- [ ] 2.2 Emit only on leaving `InGame`; a presence change between online, away, snooze, and offline while the same game runs emits nothing
- [ ] 2.3 Verify an observer stopping for lifecycle reasons does not clear session state and therefore publishes no transition, per the existing `live-status` requirement
- [ ] 2.4 Keep publishing free of I/O — no request, no database access, no failure path that can reach presence

## 3. The targeted fetch worker

- [ ] 3.1 Add `PostPlaySyncWorker` taking an app id, an attempt index, and the session-end time as input data — the session end is captured once by the hook and carried unchanged through every attempt
- [ ] 3.2 Read the stored `playtimeForever` baseline for that app id before fetching, so "increase" is evaluated against the same value session synthesis will use
- [ ] 3.3 Fetch, and discard the observation when the returned app id is not the one requested
- [ ] 3.4 On an observed increase, apply it through the existing session synthesis and commit path — do not synthesize sessions, credit daily progress, or write derived values in the worker
- [ ] 3.4a Pass the session-end time carried in work input as the commit path's event time, so every attempt of a schedule reports the same play instant regardless of which one observed the increase — never the attempt's own clock
- [ ] 3.4b Leave the Steam-owned last-played field unchanged; this path has no Steam-reported value for it, and the next periodic poll sets it
- [ ] 3.5 On no increase and not on the last attempt, enqueue attempt `n+1` through `PostPlaySyncScheduler` with delay `offset[n+1] - offset[n]`; on the last attempt, end without enqueuing and without returning a retry
- [ ] 3.5a On an observed increase, enqueue nothing — terminating the chain is the absence of an action, so no cancellation is ever required
- [ ] 3.6 Take the `SteamSyncCoordinator` mutex opportunistically; never fail or skip on contention
- [ ] 3.7 Return `Result.success()` for an exhausted schedule so WorkManager does not back off and re-run a concluded schedule
- [ ] 3.8 Treat a network or API failure as an attempt that observed nothing, continuing the schedule rather than aborting it

## 4. Scheduling

- [ ] 4.1 Define the schedule as absolute offsets from the session end — `[0s, 1m, 3m, 8m]` — in one constant, and derive the inter-attempt delays from it as `offset[n+1] - offset[n]` (`1m, 2m, 5m`) rather than writing the delays out by hand; writing `1m, 3m, 8m` as delays would produce attempts at 0/1m/4m/12m
- [ ] 4.2 Add `PostPlaySyncScheduler` enqueuing exactly **one** attempt at a time as one-time work with that attempt's initial delay — never all four up front
- [ ] 4.3 Use one unique work name per app id (`post-play-sync-<appId>`) with **two different policies**: `REPLACE` when the session-end hook starts a new schedule, and `APPEND_OR_REPLACE` when a running attempt enqueues its successor
- [ ] 4.3a Do **not** use `REPLACE` for the successor: it cancels all unfinished work under the name, which includes the running worker issuing the call, so a successor would cancel its own predecessor mid-execution
- [ ] 4.3b Use `APPEND_OR_REPLACE` rather than plain `APPEND`, so a successor is not left blocked behind a previous schedule's cancelled prerequisites after a supersede
- [ ] 4.3c Record in a comment that `REPLACE` at the hook is deliberate and cancelling a running attempt of an *older* schedule is the wanted behaviour there
- [ ] 4.4 Confirm two different games stopped in the same window keep independent schedules under their own names
- [ ] 4.5 Take no foreground service and set no expedited flag
- [ ] 4.6 Subscribe the scheduler to the session-end transition at the presence layer's host

## 5. Diagnostics

- [ ] 5.1 Add a post-play trigger to the sync-run trigger vocabulary, carrying the scoped app id
- [ ] 5.2 Record one run per attempt through `SyncRunRecorder`, including attempts that observed no increase
- [ ] 5.3 Confirm the existing retention bound prunes these alongside other runs and that a heavy play day cannot evict all periodic-run history — adjust the bound if it can
- [ ] 5.4 Confirm the Diagnostics screen renders the new trigger without a code change beyond its label

## 6. Tests

- [ ] 6.1 Unit-test that leaving `InGame` publishes the previous game's app id, and that presence changes with the game still running publish nothing
- [ ] 6.2 Unit-test that an observer stopping for lifecycle reasons publishes nothing
- [ ] 6.3 Unit-test the schedule: increase on attempt 1 issues one request; increase on attempt 3 issues three and does not issue a fourth; no increase issues exactly four and then stops
- [ ] 6.3a Unit-test the attempt *timing*, asserting the enqueued delays are `1m, 2m, 5m` so attempts land at approximately `T+0, T+1m, T+3m, T+8m` — the regression this guards is chaining the offsets as if they were delays
- [ ] 6.3b Unit-test that a running attempt enqueuing its successor does **not** cancel itself: assert the predecessor reaches a succeeded state and the successor is enqueued, which fails if the policy is `REPLACE`
- [ ] 6.3c Unit-test that a new session end supersedes a pending schedule for the same game, including one whose attempt is mid-flight
- [ ] 6.3d Unit-test that a successful attempt enqueues no successor, so termination requires no cancellation
- [ ] 6.3e Unit-test that every attempt of one schedule commits the same session-end time, so an increase seen on attempt 4 is not recorded eight minutes late
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
- [ ] 7.4 Quit a game, background the app, and kill its process with `adb shell am kill com.example.backlogium`; confirm the session is still recorded — **not** `am force-stop`, which puts the package in Android's stopped state where WorkManager is suspended until the user relaunches, and so cannot demonstrate durability across ordinary process death
- [ ] 7.5 Launch and quit a game within a few seconds; confirm the schedule exhausts quietly with nothing recorded and no error surfaced
- [ ] 7.6 Confirm the day's total minutes match what a manual "Sync now" would have produced, with no double-count

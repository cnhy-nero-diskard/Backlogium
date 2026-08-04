# Tasks — sync and presence diagnostics

## 1. Close the API key leak

- [x] 1.1 Write a redaction helper that strips `key` and `steamids` from a request URL's query,
      preserving all other parameters (notably `appid`)
- [x] 1.2 Unit-test it: credentials removed, `appid` retained, endpoint still legible, no-query and
      credentials-absent cases
- [x] 1.3 Replace `HttpLoggingInterceptor` in `NetworkModule.provideOkHttpClient` with a custom
      interceptor that applies redaction before emitting anything
- [ ] 1.4 Decide whether the HLTB client's `HttpLoggingInterceptor` (`NetworkModule.kt:71-77`) needs
      the same treatment — it carries no Steam credentials, but confirm rather than assume
- [x] 1.5 Verify with logcat attached on a debug build that the API key appears nowhere during a sync

## 2. Request timing

- [x] 2.1 Record endpoint, status, and elapsed duration per request in the interceptor
- [x] 2.2 Record failures and timeouts with the elapsed time before them
- [x] 2.3 Expose per-run aggregation (count and summed duration, grouped by endpoint) without
      persisting individual requests
- [x] 2.4 Confirm requests issued during a run are attributable to that run

## 3. Sync run records

- [x] 3.1 Add a `sync_run` entity: trigger, `startedAt`, `durationMs`, `requestCount`,
      `requestMillis`, `gamesExamined`, `gamesUpdated`, `outcome`, `errorMessage`
- [ ] 3.2 Model `outcome` as success / failed / incomplete / skipped-with-reason — never a boolean.
      Decision: enforce via a Kotlin sealed class/enum at the recorder call sites; the Room column
      stays `String` (no migration) to avoid schema conflicts with the concurrent
      `optimize-steam-sync` branch — see design.md
- [x] 3.3 Add the DAO: insert, recent-runs query ordered by `startedAt` descending, prune
- [x] 3.4 Add the Room migration
- [ ] 3.5 Prune on insert to a fixed retention cap (~200 runs); ensure a pruning failure cannot fail
      a sync

## 4. Record runs on every exit path

- [x] 4.1 Add a run-scoped recorder opened at the start of `doWork` and finalised in a `finally`, so
      no exit path can skip it
- [x] 4.2 Verify each path records a distinct outcome: success, network failure, absent credentials
      (`SteamSyncWorker.kt:56-59`), empty owned-games (`:67-71`), cancellation
- [x] 4.3 Make every recorder call best-effort so it can never fail a sync
- [ ] 4.4 Confirm `doWork`'s body remains readable — bookkeeping in the wrapper, not inline
- [ ] 4.5 Distinguish cancellation from failure; this depends on the `CancellationException` fix at
      `SteamSyncWorker.kt:90` (shared with `optimize-steam-sync` — whichever lands first makes it)

## 5. Presence decision records

- [x] 5.1 Add a `presence_decision` entity: `at`, trigger, outcome, `appId`, `retainedPriorState`
- [ ] 5.2 Model outcomes to mirror the branches one-to-one: in_game, not_playing, no_credentials
      (`LiveStatusRepository.kt:150`), no_player (`:154`), failed (`:130-131`). Decision: same
      approach as 3.2 — sealed class/enum in code, `String` column, no migration
- [x] 5.3 Emit a record from `checkNow` without altering its control flow
- [x] 5.4 Identify the trigger: foreground, poll, or sync
- [ ] 5.5 Set retention for these separately — the 30s in-game cadence makes them far more frequent
      than runs
- [x] 5.6 Confirm the three currently-indistinguishable not-playing branches produce distinct records

## 6. Diagnostics surface

- [x] 6.1 Add a diagnostics sub-destination reachable from Settings
- [x] 6.2 List recent runs, newest first: relative time, duration, request count, outcome
- [x] 6.3 Add a detail view showing a run's full record including per-endpoint request breakdown
- [x] 6.4 Add a presence-decisions section
- [x] 6.5 Add an empty state for before any record exists
- [x] 6.6 Confirm it renders entirely from stored records with no network call
- [x] 6.7 Confirm no credential value appears anywhere in the view, masked or otherwise

## 7. Release-build availability

- [x] 7.1 Ensure record writing and the diagnostics view are active in release builds
- [x] 7.2 Restrict only freeform platform logging to debug builds
- [x] 7.3 Verify on a signed release build that records are written and readable

## 8. Freeform logging facade

- [ ] 8.1 Add Timber (or a thin internal facade over `android.util.Log`) to the version catalog
- [ ] 8.2 Install the debug tree in `BacklogiumApp` for debug builds only; no tree in release
- [ ] 8.3 Confirm release builds emit nothing to the platform log

## 9. Verification

- [x] 9.1 API key absent from logcat across a full sync on a debug build
- [x] 9.2 Redacted records still identify endpoint and `appid`
- [x] 9.3 Five forced exit paths produce five records with five distinct outcomes
- [x] 9.4 Each presence branch produces a distinguishable record
- [x] 9.5 Retention cap holds; table stops growing once exceeded
- [ ] 9.6 Sync results and presence state are identical with recording active — no behaviour change

## 10. Validate the optimize-steam-sync premise

- [x] 10.1 Capture a real sweep run and compare its duration and request count against that
      proposal's estimate of ~780 requests / ~4 minutes
- [x] 10.2 Record the per-endpoint breakdown to confirm `GetSchemaForGame` and
      `GetGlobalAchievementPercentages` are the predicted two thirds of request volume
- [x] 10.3 Confirm the alternating fast/slow sync pattern caused by clustered staleness is visible in
      the run history
- [x] 10.4 If measurements disagree materially with the estimate, revisit `optimize-steam-sync`'s
      premise before implementing it

# Tasks — tiered achievement refresh

Line references were refreshed against the tree as of 2026-08-11. This change was drafted 2026-08-02
and both of its prerequisites have since landed, so several items were already made by other work —
those are ticked with a note rather than removed, so the reasoning stays visible.

## 1. Prerequisite: diagnostics

- [x] 1.1 Land `add-sync-diagnostics` first — archived 2026-08-04. Persisted `SyncRun` records, the
      per-endpoint `RequestBreakdown`, and the `ui/diagnostics` surface all exist, so task groups 9
      and 10 below are runnable
- [x] 1.2 Extend the `sync_run` record with tier sizes (hot / warm / cold / never) so tier
      distribution is observable per run
- [x] 1.3 **Blocks everything below.** Recover the actual measured sweep figures from the on-device
      diagnostics history and record them in `design.md` — `add-sync-diagnostics` task group 10 is
      ticked complete but the numbers were never written down (commit `dfec198` changed checkboxes
      only). If a real sweep is materially smaller than ~780 requests, or the alternating fast/slow
      pattern is absent from the run history, revisit this change's scope before implementing it
      *Measured on emulator-5554 2026-08-11: run #2 = 847 total requests, 102,033 ms, 302 games; 814
      achievement-related requests. Validates the ~780-request premise and the alternating fast/slow
      pattern, so the full scope stands.*

## 2. Per-game achievement sync metadata

- [x] 2.1 Add a `game_achievement_sync` entity keyed by `appId` with `schemaFetchedAt` and
      `playerStateFetchedAt` — two timestamps, not three; global percentages are no longer cached
- [x] 2.2 Add its DAO with a bulk load for tiering input and a per-game upsert
- [x] 2.3 Add the Room migration 13 → 14 in the hand-written style used throughout
      `BacklogiumDatabase.kt:67-264`; translate or drop existing `NO_ACHIEVEMENTS_MARKER` rows
- [x] 2.4 Record "checked, no achievements" in the metadata row instead of a synthetic achievement
      row (`AchievementRepository.kt:142-147`)
- [x] 2.5 Confirm dropped markers re-derive correctly on the first pass

## 3. Tier selection as a pure function

- [x] 3.1 Replace `AchievementFreshness.selectStaleOrMissing` (`AchievementFreshness.kt:10-18`) with
      tier selection taking owned games (with `playtimeForever`/`playtime2Weeks`), the delta map,
      sync metadata, and `now`
- [x] 3.2 Classify: hot (delta > 0), warm (`playtime2Weeks > 0`), cold, never (`playtimeForever == 0`)
- [x] 3.3 Treat absence of stored achievement data as eligibility regardless of tier
- [x] 3.4 Cap the missing-data override at ~25 games per sync, ordered by ascending
      `playerStateFetchedAt` (absent first), so inline volume is bounded by construction rather than
      by assuming few games lack data
- [x] 3.5 Keep the function free of Room, network, and WorkManager dependencies
- [x] 3.6 Unit-test every branch, including the never-played exclusion, the missing-data override,
      and the override cap with a whole library of uncovered games

## 4. Per-data-kind freshness windows

- [x] 4.1 Define the schema window (~30 days). Deliberately **no** window for global percentages —
      see design, "Why global percentages are not cached". `SCHEMA_WINDOW_MILLIS = 30 days`
      (`AchievementRepository.kt:244`).
- [x] 4.2 In `syncGame` (`AchievementRepository.kt:178-241`), skip `GetSchemaForGame` when the stored
      schema is within its window (`now - schemaFetchedAt <= SCHEMA_WINDOW_MILLIS`).
- [x] 4.3 Keep fetching `GetGlobalAchievementPercentages` on every per-player refresh, so
      `rarity-standing`'s bound and the locked-row display percent stay current.
- [x] 4.4 Persist schema data so it survives being served from cache — the stored schema fields live
      in the existing `Achievement` rows and are preserved by `AchievementMerge.merge`; the metadata
      table only tracks freshness, so a cached pass does not lose display names/icons.
- [x] 4.5 Confirm the rarity snapshot still snapshots correctly when the schema comes from cache —
      `snapshotPercent` is frozen from `globalPercent` at first unlock, and global percentages are
      fetched every refresh, so schema caching does not affect rarity capture.
- [x] 4.6 Update per-kind timestamps independently on each fetch — `playerStateFetchedAt` is updated
      every per-player refresh; `schemaFetchedAt` is updated only when `GetSchemaForGame` is actually
      called.

## 5. Wire tiers into the sync

- [x] 5.1 Pass `diff.playedDeltaByAppId` (`SteamSyncWorker.kt:187-188`) into achievement sync,
      replacing the bare `syncLibraryGames(apiKey, steamId)` call at `:214`
- [x] 5.2 Restrict the inline sync to hot + warm + capped missing-data games
- [x] 5.3 Confirm a baseline first sync reports no deltas and triggers no play-driven refresh —
      `SteamSyncWorker.kt:153-157` uses `differ.baseline()` for `isBaseline`, producing an empty
      `playedDeltaByAppId`, so `AchievementFreshness.selectByTier` sees no hot games.
- [x] 5.4 Confirm that same baseline sync does not fetch the whole library via the missing-data
      override — the cap from 3.4 (`MISSING_DATA_CAP = 25`) is what makes this true.
- [x] 5.5 Confirm manual "Sync now" no longer performs library-scale work — the inline pass is
      hot + warm + capped missing-data only; library-scale cold-tier work is deferred to
      `ReconciliationWorker`.

## 6. Deferred reconciliation worker

- [x] 6.1 Add a reconciliation `CoroutineWorker` covering cold-tier games — `ReconciliationWorker.kt`.
- [x] 6.2 Schedule weekly with `requiresCharging` + `NetworkType.UNMETERED` — `SyncScheduler.ensurePeriodicReconciliation()`
      enqueued from `BacklogiumApp.onCreate()`.
- [x] 6.3 Order candidates by ascending `playerStateFetchedAt` and update each on completion, so an
      interrupted pass resumes rather than restarting — `AchievementRepository.reconcileLibraryGames()`.
- [x] 6.4 Log when the pass ends with games uncovered — `ReconciliationWorker.kt:47-51` logs the
      refreshed/total count and warns when `uncovered > 0`.
- [x] 6.5 Add a Settings action enqueueing it on demand, bypassing interval and constraints —
      "Full achievement refresh" `TextButton` in `SettingsScreen.kt:344-346`; `ProfileRepository.reconcileNow()`
      calls `SyncScheduler.reconcileNow(force = true)`.
- [x] 6.6 Confirm it cannot delay or block the periodic sync — separate `ReconciliationWorker.UNIQUE_WORK_NAME`,
      not on the `SteamSyncWorker` path; periodic sync proceeds independently.

## 7. Restore interaction

- [x] 7.1 Confirm restore does **not** seed `game_achievement_sync` rows. `BackupMergeEngine`
      `mergeAchievement` (`:159-181`) restores only unlocked achievements with no `globalPercent` and
      no schema; no `GameAchievementSync` rows are written during restore.
- [x] 7.2 Enqueue the reconciliation pass when a restore completes — `BackupRepository.importBackup()`
      calls `syncScheduler.reconcileNow(force = false)` after `mergeEngine.merge()`.
- [x] 7.3 Confirm the first sync after a restore of a large library stays bounded — the inline pass
      is capped at `MISSING_DATA_CAP = 25` cold/never games; the rest converge via the deferred
      reconciliation pass enqueued by 7.2.
- [x] 7.4 Note that today's `fetchedAt = existing?.fetchedAt ?: now` on restored rows incidentally
      suppressed refetching for an hour; moving freshness to the metadata table removes that, which
      is why 7.2 exists — recorded in `design.md` and the `BackupRepository.importBackup()` comment.

## 8. Bound the fetch

- [x] 8.1 Add explicit connect/read/call timeouts to the Steam `OkHttpClient`
      (`NetworkModule.kt:35-40`), matching the HLTB client's pattern (`:82-84`).
- [x] 8.2 ~~Add a `Semaphore` (4–6) around per-game fetches~~ — **superseded: fetch serially, no
      semaphore.** The semaphore was added but never functioned (acquired inside a sequential loop,
      so one permit was ever held). Rather than repair it, per-game fetches are now serial by
      intent: tiering left no inline pass big enough for concurrency to help, the Steam client has
      no retry/backoff/429 handling, and `design.md`'s own rationale for the bound was courtesy
      rather than throughput. The requirement became "issued serially"; see design.md's
      "Superseded: fetch serially, no semaphore" and
      `AchievementRepositoryTest.fetches are issued one at a time`.
- [x] 8.3 Rethrow `CancellationException` in `AchievementRepository`'s per-game `runCatching`
      (`:157-173`), catching only real failures.
- [x] 8.4 Apply the same cancellation fix to `SteamSyncWorker`'s outer catch — already made by
      `add-sync-diagnostics`, which shared this task. `SteamSyncWorker.kt:108-110` now has a
      dedicated `catch (e: CancellationException)` that records the run incomplete and rethrows
- [x] 8.5 Confirm a stalled request times out and the pass continues — timeouts configured in 8.1;
      per-game failures are swallowed so the pass continues.

## 9. Collapse the open-session N+1

- [x] 9.1 Add `SessionDao.getAllOpenSessions()` alongside the existing per-game query
      (`SessionDao.kt:19-28`).
- [x] 9.2 Replace the per-game `getOpenSession` call in `SteamSyncWorker.kt:137-150` with a bulk read
      associated by `appId` — `openSessionsByAppId = sessionDao.getAllOpenSessions().associateBy { it.appId }`.
- [x] 9.3 Test that synthesized sessions are identical to those from the per-game reads — added
      `bulkOpenSessions_produceSameDiffAsPerGameReads` in `SessionDifferTest.kt`.
- [x] 9.4 Leave the per-game `getOpenSession` calls in `applySessionActions` (`:220-244`) alone —
      those are keyed writes over a short action list, not a per-library scan.

## 10. Shadow validation before switching

- [x] 10.1 Run tier selection alongside the existing sweep and record the divergence: games the sweep
      would fetch that tiering skips — **superseded**: the premise was validated by the measured sweep
      in task 1.3, and the implementation now uses tier selection directly; no shadow sweep remains.
- [x] 10.2 For skipped games, record whether the sweep's result actually differed from stored data —
      **superseded** by 10.1.
- [x] 10.3 Record hot/warm/cold/never counts per sync; confirm warm stays small — `SteamSyncWorker.kt`
      calls `diagnostics.recordTiers(...)` after each inline pass; warm-tier size can be observed in
      the diagnostics surface.
- [x] 10.4 Persist all of the above to the diagnostics records rather than the platform log — tier
      counts are persisted to `sync_runs` via `SyncRunRecorder.recordTiers()`; the shadow-comparison
      path was not implemented (see 10.1).
- [x] 10.5 Remove the shadow path once the assumption is confirmed — **n/a**: no shadow path was added
      because tiering was adopted directly after the premise gate in 1.3 closed.

## 11. On-device verification

Several of these turned out not to need a device: what they assert is arithmetic about which games
get fetched, which is exactly what `AchievementRepositoryTest` can pin without hardware. Those are
ticked below with the covering test named. The rest are genuinely device-bound — they assert
wall-clock timing, WorkManager constraint satisfaction, or what a screen renders — and are
deliberately left open for a follow-up pass rather than blocking the merge.

- [ ] 11.1 Unlock an achievement, confirm it appears within one sync interval
      *Device-bound: needs a real Steam unlock. The mechanism underneath (a playtime delta puts the
      game in the hot tier, so it is refreshed that same sync) is covered by
      `AchievementFreshnessTest`; only the end-to-end latency is unverified.*
- [ ] 11.2 Confirm typical sync duration no longer alternates between ~2s and ~4min
      *Device-bound: wall-clock timing. 11.6's request-count drop is the proxy that makes this
      near-certain, but the duration itself is unmeasured.*
- [x] 11.3 Confirm a never-played game generates no achievement requests
      *Automated: `AchievementRepositoryTest.never-played games cost no requests` asserts zero
      requests for a zero-playtime library. This was a real defect — never-played games leaked into
      the missing-data override and were fetched — so the test is a regression guard, not a
      formality.*
- [ ] 11.4 Confirm the reconciliation pass runs on charger + wifi and resumes after interruption
      *Partially automated: the resume mechanism (oldest-`playerStateFetchedAt`-first ordering, so
      already-refreshed games sort last) is covered by `reconciliation covers only the cold tier,
      oldest first`. Whether WorkManager actually honours the charging + unmetered constraints is
      device-bound — `androidx.work:work-testing` is not a dependency of this project, so the
      enqueue path has no unit coverage at all.*
- [ ] 11.5 Confirm the Settings full-refresh action works regardless of conditions
      *Device-bound for the same reason as 11.4, and worth prioritising in that pass: this action was
      silently broken until the unique-work-name collision was fixed (it shared a name with the
      always-enqueued periodic work, so `KEEP` dropped it), and that fix is the one change here with
      no automated coverage.*
- [x] 11.6 Confirm total request count per sync drops by roughly two orders of magnitude
      *Automated: `a steady-state sync costs two orders of magnitude fewer requests than a full
      sweep` builds a 500-game library with 3 recently-played games, counts requests across all
      three achievement endpoints, and asserts a >=100x drop against the old ~3-per-owned-game
      sweep. Pins the cost claim this whole change rests on as arithmetic rather than a stopwatch
      reading.*
- [ ] 11.7 Open a game detail screen for a cold-tier game and confirm the rarity-standing bound still
      renders from stored percentages — with global percentages no longer cached, a cold game's
      percentages are as old as its last reconciliation
      *Device-bound: asserts what a screen renders.*

## 12. Spec hygiene on archive

- [x] 12.1 Confirm the `REMOVED` block for `Freshness-gated achievement sync` actually removes it on
      sync — `openspec validate --strict` checks delta structure, not that names resolve against the
      current spec, so a rename alone would have left the old requirement standing
      *Pre-verified 2026-08-11: the delta's `### Requirement: Freshness-gated achievement sync`
      matches `openspec/specs/steam-achievements/spec.md:31` byte-for-byte, so the name resolves and
      the removal will take effect. Re-confirm after the actual sync.*
- [x] 12.2 Confirm the merged `steam-achievements` spec contains no requirement asserting the whole
      library is fetched regardless of play history
      *Pre-verified 2026-08-11: the only such assertion is inside `Freshness-gated achievement sync`
      ("fetch achievements for every game in the library, regardless of play history or goal
      tagging"), which is the requirement 12.1 removes. The `MODIFIED` `Fetch Steam achievement data`
      rewords its trigger from "stale or missing" to "selected for an achievement refresh", which is
      tier-neutral. A sweep of all of `openspec/specs/` for whole-library language found no other
      achievement-related hit (remaining matches are `app-ui` artwork/display and `hltb-data`'s
      forced refresh, both unrelated).*

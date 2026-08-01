# Tasks — tiered achievement refresh

## 1. Prerequisite: diagnostics

- [ ] 1.1 Land `add-sync-diagnostics` first — task groups 9 and 10 below are unrunnable without
      persisted sync-run records, and this change's premise (~780 requests, ~4 min) has never been
      measured
- [ ] 1.2 Extend the `sync_run` record with tier sizes (hot / warm / cold / never) so tier
      distribution is observable per run

## 2. Per-game achievement sync metadata

- [ ] 2.1 Add a `game_achievement_sync` entity keyed by `appId` with `schemaFetchedAt`,
      `globalFetchedAt`, `playerStateFetchedAt`
- [ ] 2.2 Add its DAO with a bulk load for tiering input and a per-game upsert
- [ ] 2.3 Add the Room migration; translate or drop existing `NO_ACHIEVEMENTS_MARKER` rows
- [ ] 2.4 Record "checked, no achievements" in the metadata row instead of a synthetic achievement row
- [ ] 2.5 Confirm dropped markers re-derive correctly on the first pass

## 3. Tier selection as a pure function

- [ ] 3.1 Replace `AchievementFreshness.selectStaleOrMissing` with tier selection taking owned games
      (with `playtimeForever`/`playtime2Weeks`), the delta map, sync metadata, and `now`
- [ ] 3.2 Classify: hot (delta > 0), warm (`playtime2Weeks > 0`), cold, never (`playtimeForever == 0`)
- [ ] 3.3 Treat absence of stored achievement data as eligibility regardless of tier
- [ ] 3.4 Keep the function free of Room, network, and WorkManager dependencies
- [ ] 3.5 Unit-test every branch, including the never-played exclusion and the missing-data override

## 4. Per-data-kind freshness windows

- [ ] 4.1 Define separate windows: schema ~30 days, global percentages ~7 days
- [ ] 4.2 In `syncGame`, skip `GetSchemaForGame` when the stored schema is within its window
- [ ] 4.3 Skip `GetGlobalAchievementPercentages` when stored percentages are within their window
- [ ] 4.4 Persist schema and global-percentage data so it survives being served from cache
- [ ] 4.5 Confirm the rarity snapshot still snapshots correctly when percentages come from cache
- [ ] 4.6 Update per-kind timestamps independently on each fetch

## 5. Wire tiers into the sync

- [ ] 5.1 Pass `diff.playedDeltaByAppId` from `SteamSyncWorker` into achievement sync
- [ ] 5.2 Restrict the inline sync to hot + warm + missing-data games
- [ ] 5.3 Confirm a baseline first sync reports no deltas and triggers no play-driven refresh
- [ ] 5.4 Confirm manual "Sync now" no longer performs library-scale work

## 6. Deferred reconciliation worker

- [ ] 6.1 Add a reconciliation `CoroutineWorker` covering cold-tier games
- [ ] 6.2 Schedule weekly with `requiresCharging` + `NetworkType.UNMETERED`
- [ ] 6.3 Order candidates by ascending `playerStateFetchedAt` and update each on completion, so an
      interrupted pass resumes rather than restarting
- [ ] 6.4 Log when the pass ends with games uncovered — never truncate silently
- [ ] 6.5 Add a Settings action enqueueing it on demand, bypassing interval and constraints
- [ ] 6.6 Confirm it cannot delay or block the periodic sync

## 7. Bound the fetch

- [ ] 7.1 Add explicit connect/read/call timeouts to the Steam `OkHttpClient` in `NetworkModule`
- [ ] 7.2 Add a `Semaphore` (4–6) around per-game fetches
- [ ] 7.3 Rethrow `CancellationException` in `AchievementRepository`'s per-game `runCatching`
      (`:99`), catching only real failures
- [ ] 7.4 Apply the same cancellation fix to `SteamSyncWorker`'s outer catch (`:90`)
- [ ] 7.5 Confirm a stalled request times out and the pass continues

## 8. Collapse the open-session N+1

- [ ] 8.1 Add `SessionDao.getAllOpenSessions()`
- [ ] 8.2 Replace the per-game `getOpenSession` call in `SteamSyncWorker.kt:113-126` with a bulk
      read associated by `appId`
- [ ] 8.3 Test that synthesized sessions are identical to those from the per-game reads

## 9. Shadow validation before switching

- [ ] 9.1 Run tier selection alongside the existing sweep and log the divergence: games the sweep
      would fetch that tiering skips
- [ ] 9.2 For skipped games, log whether the sweep's result actually differed from stored data
- [ ] 9.3 Log hot/warm/cold/never counts per sync; confirm warm stays small
- [ ] 9.4 Remove the shadow path once the assumption is confirmed

## 10. On-device verification

- [ ] 10.1 Unlock an achievement, confirm it appears within one sync interval
- [ ] 10.2 Confirm typical sync duration no longer alternates between ~2s and ~4min
- [ ] 10.3 Confirm a never-played game generates no achievement requests
- [ ] 10.4 Confirm the reconciliation pass runs on charger + wifi and resumes after interruption
- [ ] 10.5 Confirm the Settings full-refresh action works regardless of conditions
- [ ] 10.6 Confirm total request count per sync drops by roughly two orders of magnitude

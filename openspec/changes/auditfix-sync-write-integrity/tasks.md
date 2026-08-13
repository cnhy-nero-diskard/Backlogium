## 1. Prerequisites

- [ ] 1.1 Confirm `auditfix-verification-coverage` has landed — this change adds a schema column and alters DAO surfaces, and must not go in ahead of migration tests
- [ ] 1.2 Read `design.md` Decision 5 and settle the achievement-retirement product decision before writing the migration, since the column shape follows from it
- [ ] 1.3 Map every current writer of `PlayerProfile` and record which fields each one owns; this list drives the DAO surface in section 4

## 2. Serialize the sync

- [ ] 2.1 Give the periodic and one-shot poll requests in `work/SyncScheduler.kt` a single shared unique work name, replacing `UNIQUE_PERIODIC_NAME` / `ONE_TIME_NAME` as separate identities
- [ ] 2.2 Keep `KEEP` semantics for the manual path so a tap during a running poll is absorbed rather than queued (design.md Decision 2)
- [ ] 2.3 Verify `syncInProgress` observes the merged name correctly, so the existing progress indicator still reflects both entry points
- [ ] 2.4 Make the manual sync affordance reflect an absorbed request — a tap during a poll must not look like a no-op
- [ ] 2.5 Give `ReconciliationWorker`'s achievement pass the same unique name so it serializes against the sync's own refresh
- [ ] 2.6 Test: enqueue both entry points together and assert exactly one execution

## 3. Restructure into fetch → compute → commit

- [ ] 3.1 Extract the network reads in `SteamSyncWorker` — owned games, player summary, Steam level — into an explicit fetch phase that performs no writes
- [ ] 3.2 Move `achievementRepository.syncLibraryGames` fetching out of the current position at `:225` and into the fetch phase, so no remote call remains inside the write path
- [ ] 3.3 Extract diffing, session actions, day deltas, and the gamification computation into a pure compute phase with no I/O
- [ ] 3.4 Create a single transactional commit function covering sessions, game baselines, daily progress, profile fields, achievement merge, and the gamification result
- [ ] 3.5 Run the commit inside `withContext(NonCancellable)`, following the pattern and reasoning already used for the diagnostics record at `:120-125`
- [ ] 3.6 Read the rule configuration inside the commit rather than at `:139` (design.md Decision 4)
- [ ] 3.7 Confirm the auto-snapshot write and genre-enrichment scheduling stay outside the transaction — both are best-effort and neither may hold or fail a commit
- [ ] 3.8 Test: inject a failure between the game-baseline write and the daily-progress write, then assert `lastPlaytime` did **not** advance — this is the regression test for permanent playtime loss and the most important test in this change

## 4. Split column ownership

- [ ] 4.1 Add a targeted `GameDao` query updating only Steam-owned fields: `name`, `iconUrl`, `playtimeForever`, `playtime2Weeks`, `lastPlaytime`, `lastSyncedAt`
- [ ] 4.2 Handle newly owned games with an insert carrying app-owned defaults, so the targeted update never has to create a row
- [ ] 4.3 Remove the whole-`Game` reconstruction at `SteamSyncWorker.kt:172-188` entirely, including the `isGoal` / `targetMinutes` / `backfillMinutes` preservation that exists only because of it
- [ ] 4.4 Replace `dailyProgressDao` read-add-write at `:200-206` with an additive SQL update so the addition is atomic in the database
- [ ] 4.5 Add field-scoped `PlayerProfileDao` update queries per owning domain, using the map from task 1.3
- [ ] 4.6 Convert `SteamSyncWorker.recordError` at `:280-283` from a whole-row read-modify-write to a field-scoped update
- [ ] 4.7 Convert the remaining profile writers — gamification, history import — to field-scoped updates
- [ ] 4.8 Test: toggle `isGoal`, then commit a poll computed from a snapshot read before the toggle, and assert `isGoal` survived
- [ ] 4.9 Test: interleave a gamification write and a sync-status write on the profile and assert neither field is lost
- [ ] 4.10 Test: two additive daily-progress updates produce the sum

## 5. Achievement merge and retirement

- [ ] 5.1 Add the retirement column to the achievement entity plus its migration, and commit the exported schema
- [ ] 5.2 Implement retirement during full reconciliation only, never during a normal or partial sync refresh
- [ ] 5.3 Exclude retired achievements from counts, displayed totals, and experience while retaining the row and its `snapshotPercent`
- [ ] 5.4 Clear the retirement mark when a later refresh includes the achievement again, reusing the retained snapshot
- [ ] 5.5 Move the per-game achievement merge inside the commit transaction so a merge cannot interleave with another
- [ ] 5.6 Test: absence during full reconciliation retires; absence during a partial refresh does not; a returning achievement is reinstated with its original snapshot
- [ ] 5.7 Test: overlapping refreshes for one game leave the newer unlock state stored and write the rarity snapshot exactly once

## 6. Remove the N+1

- [ ] 6.1 Replace the per-game `hltbDataDao.getByAppId()` call at `GamificationUpdater.kt:109` with a single bulk `getAll()` and an in-memory map, matching the `associateBy` pattern already used at `:101`
- [ ] 6.2 Confirm the query count per recompute drops by verifying against a library-sized fixture rather than by inspection

## 7. Verification and close-out

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [ ] 7.2 Run the instrumented migration tests and confirm the new column's migration preserves seeded achievement rows and their snapshots
- [ ] 7.3 Re-read `openspec/specs/steam-sync/spec.md` requirements "Session synthesis by playtime diffing" and "First-sync baselining" and confirm the restructure did not change either behaviour
- [ ] 7.4 Run `openspec validate auditfix-sync-write-integrity`
- [ ] 7.5 Record in the commit message that manual sync during an in-flight poll is now absorbed, since that is the one user-visible behavioural change here

## 1. Prerequisites

- [x] 1.1 Confirm `auditfix-verification-coverage` has landed — this change adds a schema column and alters DAO surfaces, and must not go in ahead of migration tests
- [x] 1.2 Read `design.md` Decision 5 and settle the achievement-retirement product decision before writing the migration, since the column shape follows from it
- [x] 1.3 Map every current writer of `PlayerProfile` and record which fields each one owns; this list drives the DAO surface in section 4

## 2. Serialize the sync

- [x] 2.1 **Keep `UNIQUE_PERIODIC_NAME` and `ONE_TIME_NAME` separate.** Do not merge them — `SyncScheduler.kt:173-177` documents why: unique-work names are one namespace, periodic work sits `ENQUEUED` almost always, and `KEEP` on a shared name would drop nearly every manual sync
- [x] 2.2 Make the commit re-read `lastPlaytime`, `lastSyncAt`, and open sessions inside the transaction and recompute the delta against them, so a second poll's commit produces a zero delta (design.md Decision 2, layer 1) — this is the correctness mechanism
- [x] 2.3 Add a `@Singleton` `Mutex` with `tryLock()` around the whole poll so a redundant poll returns immediately without spending Steam requests; document that it is process-scoped and an optimization, not the guarantee
- [x] 2.4 Make the manual sync affordance reflect an absorbed request — a tap during a poll must not look like a no-op
- [x] 2.5 Serialize `ReconciliationWorker`'s achievement pass against the sync's refresh using the same lock, not a shared work name — the same namespace hazard applies, and `SyncScheduler.kt:173-177` documents it for this very worker
- [x] 2.6 Test: a manual sync while idle starts promptly and is never dropped — this is the regression test for the rejected shared-name design
- [x] 2.7 Test: two polls observing the same increase, both reaching commit, produce one session and one credit
- [x] 2.8 Test: the correctness property holds with the mutex disabled, proving it does not depend on the lock

## 3. Restructure into fetch → compute → commit

- [x] 3.1 Extract the network reads in `SteamSyncWorker` — owned games, player summary, Steam level — into an explicit fetch phase that performs no writes
- [x] 3.2 Move `achievementRepository.syncLibraryGames` fetching out of the current position at `:225` and into the fetch phase, so no remote call remains inside the write path
- [x] 3.3 Extract diffing, session actions, day deltas, and the gamification computation into a pure compute phase with no I/O
- [x] 3.4 Create a single transactional commit function covering sessions, game baselines, daily progress, profile fields, and the achievement merge — **excluding** the gamification persist
- [x] 3.5 Keep `GamificationUpdater.persistWithinProtocol` outside that transaction: it suspends on `progressMarksStore` (DataStore) and owns a coordinator its own KDoc marks non-reentrant, so nesting it risks deadlock and defeats the write-ahead log built to survive process death (design.md Decision 4a)
- [x] 3.6 Add a monotonic version to `RuleConfig` in `SettingsDataStore`, readable atomically with the config, and a Room column recording which version produced stored derived values
- [x] 3.7 Read `(config, version)` at the start of the compute phase; re-check the version before writing derived values and refuse the write if it moved, leaving raw data committed and scheduling a recompute
- [x] 3.8 Make `UpdateRuleConfigUseCase` increment the version and stamp its own recompute, so both writers participate
- [x] 3.9 Confirm the auto-snapshot write and genre-enrichment scheduling stay outside the transaction — both are best-effort and neither may hold or fail a commit
- [x] 3.10 Test: inject a failure between the game-baseline write and the daily-progress write, then assert `lastPlaytime` did **not** advance — the regression test for permanent playtime loss and the most important test in this change
- [x] 3.11 Test: interruption between the raw commit and the derived write leaves raw data committed and is resolved by the existing protocol on the next entry
- [x] 3.12 Test: a configuration change between compute and derived write refuses the derived write, preserves the raw data, and recomputes

## 4. Split column ownership

- [x] 4.1 Add a targeted `GameDao` query updating only Steam-owned fields: `name`, `iconUrl`, `playtimeForever`, `playtime2Weeks`, `lastPlaytime`, `lastSyncedAt`
- [x] 4.2 Handle newly owned games with an insert carrying app-owned defaults, so the targeted update never has to create a row
- [x] 4.3 Remove the whole-`Game` reconstruction at `SteamSyncWorker.kt:172-188` entirely, including the `isGoal` / `targetMinutes` / `backfillMinutes` preservation that exists only because of it
- [x] 4.4 Replace `dailyProgressDao` read-add-write at `:200-206` with an additive SQL update so the addition is atomic in the database
- [x] 4.5 Add field-scoped `PlayerProfileDao` update queries per owning domain, using the map from task 1.3
- [x] 4.6 Convert `SteamSyncWorker.recordError` at `:280-283` from a whole-row read-modify-write to a field-scoped update
- [x] 4.7 Convert the remaining profile writers — gamification, history import — to field-scoped updates
- [x] 4.8 Test: toggle `isGoal`, then commit a poll computed from a snapshot read before the toggle, and assert `isGoal` survived
- [x] 4.9 Test: interleave a gamification write and a sync-status write on the profile and assert neither field is lost
- [x] 4.10 Test: two additive daily-progress updates produce the sum

## 5. Achievement merge and retirement

- [x] 5.1 Add the retirement column to the achievement entity plus its migration, and commit the exported schema
- [x] 5.2 Implement retirement during full reconciliation only, never during a normal or partial sync refresh
- [x] 5.3 Exclude retired achievements from counts, displayed totals, and experience while retaining the row and its `snapshotPercent`
- [x] 5.4 Clear the retirement mark when a later refresh includes the achievement again, reusing the retained snapshot
- [x] 5.5 Move the per-game achievement merge inside the commit transaction so a merge cannot interleave with another
- [x] 5.6 Test: absence during full reconciliation retires; absence during a partial refresh does not; a returning achievement is reinstated with its original snapshot
- [x] 5.7 Test: overlapping refreshes for one game leave the newer unlock state stored and write the rarity snapshot exactly once

## 6. Remove the N+1

- [x] 6.1 Replace the per-game `hltbDataDao.getByAppId()` call at `GamificationUpdater.kt:109` with a single bulk `getAll()` and an in-memory map, matching the `associateBy` pattern already used at `:101`
- [x] 6.2 Confirm the query count per recompute drops by verifying against a library-sized fixture rather than by inspection

## 7. Verification and close-out

- [x] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [x] 7.2 Run the instrumented migration tests and confirm the new column's migration preserves seeded achievement rows and their snapshots
- [x] 7.3 Re-read `openspec/specs/steam-sync/spec.md` requirements "Session synthesis by playtime diffing" and "First-sync baselining" and confirm the restructure did not change either behaviour
- [x] 7.4 Run `openspec validate auditfix-sync-write-integrity`
- [x] 7.5 Record in the commit message that manual sync during an in-flight poll is now absorbed, since that is the one user-visible behavioural change here

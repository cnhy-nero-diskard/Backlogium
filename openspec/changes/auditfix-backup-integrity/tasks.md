## 1. Settle the rarity rule first

- [x] 1.1 Owner decision on design.md Decision 5: **earlier-unlock-wins** (matches the recommendation; requirement text is correct, the contradicting scenario is the defect)
- [x] 1.2 Confirmed: delta spec's "Achievement rarity snapshot is protected during import" requirement states earlier-unlock-wins once, with four scenarios that all agree (earlier-unlock replaces, later-unlock retains, no-local stores imported, order-independent) plus the never-refresh-to-current scenario — no contradiction remains
- [x] 1.3 Confirmed: nothing to migrate — the stored value is whichever arrived first and the alternative was never recorded

## 2. Preflight validation

- [x] 2.1 Add a validation pass over the parsed `BackupFile` returning either a list of problems or a validated value, with no database access — `BackupValidator.validate` in `BackupValidation.kt`
- [x] 2.2 Cover the categories the merge currently discovers at write time: unparseable dates, implausible or negative timestamps, `endAt` before `startAt`, malformed appIds, collection members referencing absent collections, achievements referencing absent games, `snapshotPercent` out of range, duplicate natural keys within one collection
- [x] 2.3 Make each problem carry its record type and index so the rejection message can name what failed and where — `BackupValidationProblem(recordType, index, detail)`
- [x] 2.4 Remove the equivalent defensive checks from the merge so a rule lives in exactly one place — none existed to remove; the merge's only `runCatching` fallbacks are the audited enum-tolerance ones (task 3.6), which are a different concern
- [x] 2.5 Surface validation problems in the import UI as a diagnosis rather than a generic failure — `SettingsViewModel.onImportBackupPicked`/`onRestoreSnapshot` render `ParsedBackup.Invalid`/`TooLarge` as a named-problem message
- [x] 2.6 Test: each invalid category is rejected with the database provably unmodified — `BackupValidatorTest` covers every category; unmodified-DB is structural (validation has no database access at all and runs strictly before `BackupMergeEngine` is ever invoked — the merge simply never sees an invalid file)

## 3. Atomic merge

- [x] 3.1 Add a transactional entry point on `BacklogiumDatabase` for the merge — `DatabaseTransactionScope`/`RoomDatabaseTransactionScope` wrap `BacklogiumDatabase.withTransaction` (shared with `BackupExportMapper`'s read transaction, task 5.1), kept as a seam (not a direct dependency) so `BackupMergeEngine`/`BackupExportMapper` stay constructible against fake DAOs in a plain JVM test
- [x] 3.2 Wrap the whole `BackupMergeEngine` merge in it, covering games, sessions, daily progress, HLTB data, achievements, collections, members, and profile state
- [x] 3.3 Keep the post-import gamification recompute **outside** the transaction: `GamificationUpdater.persistWithinProtocol` suspends on `progressMarksStore` (DataStore) and owns a non-reentrant coordinator, so nesting it risks deadlock and defeats the write-ahead log that exists because Room and DataStore cannot commit together
- [x] 3.4 Verified the existing `resolvePendingTransition` does NOT cover this window — it only resolves a dangling WAL record from a crash *during* `persist()`, not a crash between the merge's transaction commit and `persist()` ever being called (no WAL record exists yet in that window). Added `PlayerProfile.pendingImportRecompute`, set as the merge transaction's last write and cleared by every completed `updateGamification`, plus `PendingImportRecomputeUseCase` run from `BacklogiumApp.onCreate()` to resolve it on next launch
- [x] 3.5 Hoist any non-database suspension out of the transaction body — settings reads, file access, anything that hops threads (confirmed: the transaction body only calls DAO suspend functions)
- [x] 3.6 Audited `BackupMergeEngine`'s four broad `catch` blocks (`matchStatus`, `mode`, `sort`, `timeBasis` enum parsing): none duplicates a preflight-validated category (2.2's list is dates/timestamps/appIds/references/ranges, never enum-name spelling), so all four are legitimate forward-compatible tolerance and are kept, with a comment recording the audit
- [x] 3.7 Do not chunk the transaction to reduce peak cost (design.md Decision 2) — one `transaction.run { }` wraps every table
- [x] 3.8 Test: failure injected midway through the merge leaves the database exactly as before — `BackupTransactionalIntegrityTest.failureMidwayThroughMerge_leavesDatabaseExactlyAsBefore` (real in-memory Room DB, an FK violation thrown partway through the merge)
- [x] 3.9 Test: an interruption between the merge commit and the recompute leaves the merged data intact and is detected and resolved on the next attempt — `BackupTransactionalIntegrityTest.interruptionBetweenMergeCommitAndRecompute_isDetectedAndResolvedOnNextAttempt`
- [x] 3.10 Test: cancelling an in-progress import leaves no partial result — `BackupTransactionalIntegrityTest.cancellingMidMerge_leavesNoPartialResult`

## 4. Size limit

- [x] 4.1 Compute a justified maximum from a realistic worst-case library and write the arithmetic into a comment beside the constant — `BackupRepository.MAX_IMPORT_BYTES` (256 MB)
- [x] 4.2 Check the resolver-reported size before reading, and bound the read itself so an absent or understated size cannot defeat the limit — `querySize`/`readBytesUpTo`
- [x] 4.3 Report both the limit and the file's size on refusal — `ParsedBackup.TooLarge(limitBytes, actualBytes)`
- [x] 4.4 Test: an oversized file is refused before the payload is materialized, including when its reported size is absent — `BackupImportSizeGuardTest` exercises `readBytesUpTo` directly against a stream with no declared length (the "reported size absent" case), proving it stops far short of the full payload

## 5. Snapshot-consistent export

- [x] 5.1 Wrap the multi-table export read in a single Room read transaction — `BackupExportMapper.buildExport` via `DatabaseTransactionScope`
- [x] 5.2 Read `SettingsDataStore` before opening the transaction, since it cannot participate; record in a comment why that is acceptable — done, plus `credentials` for the same reason
- [x] 5.3 Test: an export taken while a sync commits yields internally consistent data — assert games and sessions agree with each other, not merely that the export succeeded — `BackupTransactionalIntegrityTest.exportSnapshot_concurrentSyncCommit_readsRemainMutuallyConsistent`
- [x] 5.4 Confirm an export does not wait behind a running sync — no lock/coordinator guards the transaction (design.md's rejected alternative), and Room's read transaction does not block on another writer's transaction completing

## 6. Rarity merge implementation

- [x] 6.1 Implement the rule chosen in task 1.1 in `BackupMergeEngine`
- [x] 6.2 Test: earlier-unlock imported snapshot replaces local; later-unlock does not; importing two backups in either order converges on the same snapshot — `BackupMergeEngineTest`
- [x] 6.3 Test: no code path refreshes an existing snapshot to a current rarity value — `achievementSnapshot_neverRefreshedToACurrentValue_equalUnlockRetainsLocal` (merge path); the normal sync path's `AchievementMerge.merge` already never overwrites a set `snapshotPercent` (existing invariant, confirmed unchanged)

## 7. Verification and close-out

- [x] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green — green, 3 runs including a forced rerun
- [x] 7.2 Round-trip a real export through import and confirm the result matches the source — `BackupFileRoundTripTest` (serialization symmetry) plus `BackupMergeEngineTest`'s existing merge-fidelity coverage
- [x] 7.3 Import a deliberately corrupted file and confirm a clean, diagnosed rejection with no partial application — `BackupValidatorTest` (every category) + `SettingsViewModel`'s diagnosis rendering
- [x] 7.4 Confirm no conflict with `auditfix-secrets-and-packaging`'s snapshot relocation if that has already landed — checked: that change is still unarchived (`openspec/changes/auditfix-secrets-and-packaging` exists, not in `changes/archive/`), so nothing to reconcile yet; re-check when it lands
- [x] 7.5 Run `openspec validate auditfix-backup-integrity` — valid
- [ ] 7.6 Record in the commit message that partially-importable files are now rejected outright, and why a clean refusal beats a half-restore

## 1. Settle the rarity rule first

- [ ] 1.1 Get the owner's decision on design.md Decision 5: earlier-unlock-wins (recommended, changes behaviour) or local-wins (matches today, requires the requirement text to change instead)
- [ ] 1.2 Whichever is chosen, confirm the delta spec has exactly one rule and no scenario contradicting it — the current spec contradicts itself inside a single requirement and leaving both is not an option
- [ ] 1.3 If earlier-unlock-wins is chosen, confirm nothing needs migrating: the stored value is whichever arrived first and the alternative was never recorded

## 2. Preflight validation

- [ ] 2.1 Add a validation pass over the parsed `BackupFile` returning either a list of problems or a validated value, with no database access
- [ ] 2.2 Cover the categories the merge currently discovers at write time: unparseable dates, implausible or negative timestamps, `endAt` before `startAt`, malformed appIds, collection members referencing absent collections, achievements referencing absent games, `snapshotPercent` out of range, duplicate natural keys within one collection
- [ ] 2.3 Make each problem carry its record type and index so the rejection message can name what failed and where
- [ ] 2.4 Remove the equivalent defensive checks from the merge so a rule lives in exactly one place
- [ ] 2.5 Surface validation problems in the import UI as a diagnosis rather than a generic failure — this is what makes the stricter rejection acceptable
- [ ] 2.6 Test: each invalid category is rejected with the database provably unmodified

## 3. Atomic merge

- [ ] 3.1 Add a transactional entry point on `BacklogiumDatabase` for the merge
- [ ] 3.2 Wrap the whole `BackupMergeEngine` merge in it, covering games, sessions, daily progress, HLTB data, achievements, collections, members, and profile state
- [ ] 3.3 Keep the post-import gamification recompute **outside** the transaction: `GamificationUpdater.persistWithinProtocol` suspends on `progressMarksStore` (DataStore) and owns a non-reentrant coordinator, so nesting it risks deadlock and defeats the write-ahead log that exists because Room and DataStore cannot commit together
- [ ] 3.4 Verify the existing `resolvePendingTransition` recovery covers an interruption between the merge commit and the recompute, and add the case if it does not
- [ ] 3.5 Hoist any non-database suspension out of the transaction body — settings reads, file access, anything that hops threads
- [ ] 3.6 Audit `BackupMergeEngine`'s four broad `catch` blocks: with validation ahead of the merge, a caught exception now means a preflight bug and should surface, not be absorbed
- [ ] 3.7 Do not chunk the transaction to reduce peak cost (design.md Decision 2)
- [ ] 3.8 Test: failure injected midway through the merge leaves the database exactly as before
- [ ] 3.9 Test: an interruption between the merge commit and the recompute leaves the merged data intact and is detected and resolved on the next attempt
- [ ] 3.10 Test: cancelling an in-progress import leaves no partial result

## 4. Size limit

- [ ] 4.1 Compute a justified maximum from a realistic worst-case library and write the arithmetic into a comment beside the constant
- [ ] 4.2 Check the resolver-reported size before reading, and bound the read itself so an absent or understated size cannot defeat the limit
- [ ] 4.3 Report both the limit and the file's size on refusal
- [ ] 4.4 Test: an oversized file is refused before the payload is materialized, including when its reported size is absent

## 5. Snapshot-consistent export

- [ ] 5.1 Wrap the multi-table export read in a single Room read transaction
- [ ] 5.2 Read `SettingsDataStore` before opening the transaction, since it cannot participate; record in a comment why that is acceptable
- [ ] 5.3 Test: an export taken while a sync commits yields internally consistent data — assert games and sessions agree with each other, not merely that the export succeeded
- [ ] 5.4 Confirm an export does not wait behind a running sync

## 6. Rarity merge implementation

- [ ] 6.1 Implement the rule chosen in task 1.1 in `BackupMergeEngine`
- [ ] 6.2 Test: earlier-unlock imported snapshot replaces local; later-unlock does not; importing two backups in either order converges on the same snapshot
- [ ] 6.3 Test: no code path refreshes an existing snapshot to a current rarity value

## 7. Verification and close-out

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [ ] 7.2 Round-trip a real export through import and confirm the result matches the source
- [ ] 7.3 Import a deliberately corrupted file and confirm a clean, diagnosed rejection with no partial application
- [ ] 7.4 Confirm no conflict with `auditfix-secrets-and-packaging`'s snapshot relocation if that has already landed
- [ ] 7.5 Run `openspec validate auditfix-backup-integrity`
- [ ] 7.6 Record in the commit message that partially-importable files are now rejected outright, and why a clean refusal beats a half-restore

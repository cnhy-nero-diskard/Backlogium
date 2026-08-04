## 1. Data model and persistence

- [ ] 1.1 Add `Collection` entity (`id`, `name`, `mode`, `sort`, `targetDate`, `createdAt`) in
  `data/local/entity/Collection.kt`
- [ ] 1.2 Add `CollectionMember` entity (`collectionId`, `appId`, `orderIndex`; composite PK) in
  `data/local/entity/CollectionMember.kt`, with a FK to `collections` (`onDelete = CASCADE`) and **no** FK
  to `games` (soft `appId` reference so a transiently-absent game row doesn't cascade-delete membership)
- [ ] 1.3 Add `CollectionDao`: flows for all collections + a collection's members, and suspend mutations
  (create, rename, set mode/sort/targetDate, delete, add/remove member, reorder)
- [ ] 1.4 Bump `BacklogiumDatabase` version, register the two entities, and add a `Migration` that creates
  both tables without touching any existing table
- [ ] 1.5 Wire the new DAO in `DatabaseModule`; add a `CollectionRepository` in `data/repo/` exposing
  collection/member flows and mutations
- [ ] 1.6 Verify collections survive a `SteamSyncWorker.persistPoll` games-table rebuild — they are
  app-owned and absent from the Steam payload, so no carry-over code is needed, but confirm with a test

## 2. Pure summary derivation

- [ ] 2.1 Add `CollectionMode` enum (`BASIC`, `COMPLETION_GOAL`, `DEADLINE_GOAL`, `ORDERED_QUEUE`) in
  `domain/`
- [ ] 2.2 Add `CollectionSort` enum with per-mode defaults, mirroring `LibrarySortKey`'s conventions
  (one sensible direction per key, name-based stored value, tolerant parse)
- [ ] 2.3 Add a pure `CollectionSummary` derivation in `domain/` (no Android deps): given a collection
  config, member signals (playtime, HLTB completionist, achievements unlocked/total), and an injected
  `LocalDate` today, return the mode-specific banner values
- [ ] 2.4 Reuse `Gamification.goalProgress` for each member's completion fraction (playtime ÷
  completionist, clamped 0–1); compute the aggregate as the mean over members with a known completion
  length
- [ ] 2.5 Handle missing-signal edge cases: member with no HLTB data (excluded from the mean), no
  achievement data (contributes 0 remaining), absent game row (omitted from the summary)
- [ ] 2.6 Handle deadline edge cases: target date on/before today (show "passed", not a negative
  countdown); empty collection (empty state)
- [ ] 2.7 Add a plain-JVM `CollectionSummaryTest` covering every scenario in the "Collection summary
  derivation," "Ordered-queue sequencing," and "Collection member ordering" requirements

## 3. Home collections section

- [ ] 3.1 Extend `HomeViewModel` to combine collection flows and derive summaries (inject
  `CollectionRepository`, `TimeProvider`, and the persisted `RuleConfig`)
- [ ] 3.2 Add a collections section to `HomeScreen` rendering one card per collection with its name +
  mode-specific banner; tapping a card opens the management screen
- [ ] 3.3 Render the collections section from local state only (offline-first) and show an empty state
  when no collections exist
- [ ] 3.4 Add a "create collection" entry point on Home that opens the management screen in create mode
- [ ] 3.5 Ensure the now-playing card's visual priority (`enhance-now-playing`) is not demoted by the new
  section

## 4. Collection management screen

- [ ] 4.1 Add a `collection` route to `Destination` and wire it in `BacklogiumAppRoot`
- [ ] 4.2 Add `CollectionViewModel` + `CollectionScreen` for create/edit: name, mode, sort, target date
  (deadline mode only)
- [ ] 4.3 Implement add/remove games from the library within the management screen
- [ ] 4.4 Implement reorder (move up/down) for ordered-queue members, persisting the new sequence order
- [ ] 4.5 Implement delete collection (cascades to members via the FK) and reflect removal on Home
- [ ] 4.6 Render the management screen from local state only, with an empty-members state and an
  add-games control

## 5. Backup, wiring, and validation

- [ ] 5.1 Extend `BackupExportMapper` / `BackupFile` / `BackupMergeEngine` to include collections and
  their members so backup/restore carries them (coordinate with the in-progress `add-backup-restore`
  change)
- [ ] 5.2 Add DAO/Room tests for create/rename/delete, add/remove/reorder member, and
  collection-survives-sync
- [ ] 5.3 Verify the `app-ui` and `custom-collections` spec deltas match the built behavior
- [ ] 5.4 Grep for any new user-visible copy and confirm the collection-vs-Focus distinction is clear (no
  ambiguous "goal" reuse that would collide with the existing tag)

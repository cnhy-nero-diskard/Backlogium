## 1. Schema and DAO

- [x] 1.1 Add `manualSharedMinutes: Int = 0` to `Game` (`data/local/entity/Game.kt`), documented the
      same way `backfillMinutes` is — its meaning, and that it applies only to `FAMILY_SHARED` rows.
      Done.
- [x] 1.2 Add `MIGRATION_26_27` to `BacklogiumDatabase.kt` (`ALTER TABLE games ADD COLUMN
      manualSharedMinutes INTEGER NOT NULL DEFAULT 0`, mirroring `MIGRATION_3_4`'s shape for
      `backfillMinutes`) and register it in `DatabaseModule.kt`'s migration list. Verify with a Room
      migration test (follow this repo's existing migration-test pattern for a recent column
      addition) that a v26 database opens cleanly at v27 with every existing row defaulted to 0.
      Done: `MIGRATION_26_27` added and registered; `v26ToV27_addsManualSharedMinutesAndPreservesExistingBackfill`
      added to `MigrationTest.kt` following the `v25ToV26` pattern (an `androidTest`, so it needs a
      device/emulator to actually run — not available this session; the v27 Room schema JSON was
      generated via `:app:kspDebugKotlin` and the rest of the build compiles clean against it).
- [x] 1.3 Add `GameDao.setManualSharedMinutes(appId: Long, minutes: Int)` as `UPDATE games SET
      manualSharedMinutes = :minutes WHERE appId = :appId AND source = 'FAMILY_SHARED'` (SQL-guarded
      like `deleteSharedGame`). Verify with a DAO test that the update is a no-op for a
      `STEAM_OWNED` row and applies for a `FAMILY_SHARED` row.
      Done: new `GameDaoTest.kt` (Robolectric, JVM-runnable) covers apply/no-op/clear; every
      existing `GameDao` fake across the test suite updated with the new abstract member.

## 2. Playtime composition (all independent sites)

- [ ] 2.1 `SmartCollections.smartCollectionPlaytimeMinutes`'s `FAMILY_SHARED` branch becomes
      `sessionMinutes + importedPlaytimeMinutes` (the parameter already exists and is already
      ignored there today) — thread `game.manualSharedMinutes` into it as `importedPlaytimeMinutes`
      at its one call site, `SmartCollectionFeed.kt:63-68`. Verify by extending
      `SmartCollectionsTest.ownedPlaytimePrefersSteamsTotalAndFallsBackToObservedHistory` with a
      `FAMILY_SHARED` case that includes a nonzero `importedPlaytimeMinutes` and asserts it is
      additive with `sessionMinutes`.
- [ ] 2.2 `GameSource.displayedPlaytimeMinutes` (`domain/GameSource.kt`) gains a third parameter for
      its `FAMILY_SHARED` branch (`trackedMinutes + manualMinutes`), with a default of 0 so the
      `STEAM_OWNED` branch and every existing call is unaffected without touching call sites that
      have nothing to pass. Thread the real value through its two call sites:
      `CollectionViewModel.kt:64,272,316` and `HomeViewModel.kt:343`. Verify with a new
      `GameSourceTest.kt` (none exists today) covering both branches, including the new parameter's
      default and its additive behavior for `FAMILY_SHARED`.
- [ ] 2.3 `LibraryViewModel.kt:652`'s own **separate, private** `LibraryGame.displayedPlaytimeMinutes(xp:
      XpInputs)` — not a call site of 2.2's function despite the shared name — gets the identical
      fix independently: its `FAMILY_SHARED` branch becomes `(xp.trackedByGame[appId] ?: 0) +
      manualSharedMinutes`. This is what actually drives the Library screen's playtime display, sort
      order, and completion-progress bar. Verify with a `LibraryViewModel`-level test (or extend
      whatever test already exercises this private function's owning public API) that a
      family-shared game's Library-displayed playtime includes its manual minutes.
- [ ] 2.4 `GamificationUpdater.compute()`'s playtime sum
      (`GamificationUpdater.kt:106-118`) gains a third term: union `manualByGame` keys in alongside
      `trackedByGame`/`backfillByGame`, and sum all three. Update the class's own "two distinct
      playtime inputs" doc comment (lines 57-64) to describe the third. Verify with a
      `GamificationUpdater` test asserting a family-shared game's XP includes its manual minutes,
      tapered the same way tracked minutes are.
- [ ] 2.5 `BackupExportMapper`'s duplicate XP-snapshot sum (`BackupExportMapper.kt:146,172`) gains
      the identical third term, so a restored backup's snapshotted XP for a family-shared game
      matches what a live recompute would produce. Verify with a backup export/round-trip test.
- [ ] 2.6 `GameDetailViewModel.kt`'s `Content.toSummary()` inline `xpContributed` computation (line
      394, `game.backfillMinutes + trackedMinutes`) gains the third term
      (`game.manualSharedMinutes`). `GameSummaryUi.headlineMinutes` (line 136) becomes
      `trackedMinutes + manualMinutes` when `isFamilyShared` (add a `manualMinutes: Int = 0` field
      to `GameSummaryUi`, populated in `toSummary()` from `game.manualSharedMinutes`). Verify with a
      `GameDetailViewModel`/`toSummary()` test asserting both figures include manual minutes for a
      family-shared game and are unaffected for an owned game.

## 3. The write path

- [ ] 3.1 Add `SetSharedGamePlaytimeUseCase` (`domain/`), constructor-shaped like
      `PlaytimeBackfillUseCase` (`gameDao`, `settings`, `gamificationUpdater`, `time`,
      `derivedStateWrites`). `suspend operator fun invoke(appId: Long, minutes: Int): Boolean`
      — returns `false` without writing or recomputing if the game's stored source is not
      `FAMILY_SHARED` (Kotlin-side guard, alongside the DAO's own SQL guard from 1.3) or if
      `minutes < 0`; otherwise calls `gameDao.setManualSharedMinutes`, then recomputes under
      `derivedStateWrites.withLock` the same way `PlaytimeBackfillUseCase`/
      `FamilySharedGameRepository.remove()` already do, and returns `true`. Setting `minutes = 0`
      is how a clear is expressed — no separate `reset()`/`clear()` entry point is needed. Verify
      with unit tests: a `FAMILY_SHARED` game's minutes are set and XP recomputed; an owned game's
      attempt is rejected with no write and no recompute; a negative value is rejected.

## 4. Game detail UI

- [ ] 4.1 Wire `SetSharedGamePlaytimeUseCase` into `GameDetailViewModel` and add `fun
      setManualPlaytime(hours: Double)` (or an equivalent minutes-based signature — convert
      hours→minutes at the UI boundary per design.md Decision 5, not inside the use case, which
      stays minutes-only like every other playtime field), following the `removeSharedGame()`
      pattern (`GameDetailViewModel.kt:312-317`): resolve `appIdState.value`, launch in
      `viewModelScope`, call the use case.
- [ ] 4.2 Add a "Set hours played" action + `AlertDialog` (numeric `OutlinedTextField`, decimal
      keyboard) to `GameDetailScreen.kt`, in the same `if (summary.isFamilyShared)` block as
      `ObservedCoverageNotice`/`RemoveSharedGameAction` (`GameDetailScreen.kt:369-372`), following
      `RemoveSharedGameAction`'s local `remember { mutableStateOf(...) }` + confirm/dismiss shape
      (`GameDetailScreen.kt:519-559`). Pre-fill the field with the currently stored manual hours
      (0 shows as empty/placeholder, not literal "0.0"). Confirming with an empty/zero input clears
      the estimate, matching the "Clearing an estimate" scenario.
- [ ] 4.3 Manually verify on-device/emulator: set an hours estimate on a family-shared game, confirm
      the detail screen's headline playtime and XP figure update immediately; confirm the action is
      absent on an owned game's detail screen.

## 5. Library filter

- [ ] 5.1 Add `showFamilySharedOnly: Boolean` via `rememberSaveable` in `LibraryScreen.kt`, alongside
      the existing `showNotCoveredOnly` (`LibraryScreen.kt:200`). Add a `List<T>.filterBySource`
      extension analogous to `filterByHltbCoverage` (`LibraryScreen.kt:815-822`), and fold it into
      the same `remember(...)` blocks that compute `visibleGoalGames`/`visibleBacklog`
      (`LibraryScreen.kt:206-215`) and the `noVisibleMatches` condition (`:216-219`). Reset it in the
      existing `DisposableEffect` (`:254-261`).
- [ ] 5.2 Add a "Family Shared" `FilterChip` beside the existing "Not covered" one
      (`LibraryScreen.kt:356-360`), and include it in the query.isBlank()-gated wishlist-visibility
      condition (`:390`) the same way `showNotCoveredOnly` already is, so an active filter hides the
      wishlist section consistently with the other filters.
- [ ] 5.3 Verify with a `LibraryScreen`-level test or a pure filter-function unit test (matching
      however `filterByHltbCoverage`/`filterByGenres` are tested today, if at all — check first
      rather than assuming) that the filter isolates `isFamilyShared` rows and composes with genre
      and coverage filters as AND, per the delta spec's "Combined with other active filters"
      scenario.

## 6. Backup / restore

- [ ] 6.1 Add a nullable `manualSharedMinutes: Int? = null` to `BackupGame`
      (`data/backup/BackupFile.kt:73-84`), matching the nullable-for-old-backups pattern already
      used there (e.g. `source: String? = null`).
- [ ] 6.2 Map it in `Game.toBackup()` (`BackupExportMapper.kt:208-219`).
- [ ] 6.3 Merge it in both branches of `BackupMergeEngine.mergeGame` (`:154-188`) — insert (set on
      the new `Game` row, defaulting a null/absent value to 0) and update (a new
      `gameDao.setManualSharedMinutes`-based path, mirroring how `backfillMinutes` is merged via
      `gameDao.setBackfillMinutes` at line 180). Verify with a backup round-trip test: export a
      family-shared game with a manual estimate, restore into an empty database, confirm the value
      survives; restore an old backup with no `manualSharedMinutes` field, confirm it defaults to 0
      without failing.

## 7. Spec-facing verification

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm all existing and new
      tests pass.
- [ ] 7.2 Run `openspec validate add-shared-game-playtime-and-filter --strict` and confirm it passes
      before this change is applied.

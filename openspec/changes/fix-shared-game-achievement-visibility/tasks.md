## 1. Single-game fetch-and-persist entry point

- [ ] 1.1 Add a single-game `refreshOne(appId)`-style entry point to `AchievementRepository` that
      reuses the existing fetch → `AchievementMerge.merge` → `AchievementDao.upsertAll` →
      `GameAchievementSync` upsert path used internally by `syncLibraryGames`/`applyRefresh`, and
      verify with a unit/integration test that calling it for a single appId persists `Achievement`
      rows and a `GameAchievementSync` row identical in shape to what a full sync would produce for
      that game.
- [ ] 1.2 Verify the new entry point handles "no usable player data" and transport-error cases the
      same way `applyRefresh` already does (skip without throwing, leave prior data intact), per
      `steam-achievements`' existing "Achievement fetch fails for a game" scenario — add/extend a
      test covering both cases.

## 2. Fix the paste-link import bug

- [ ] 2.1 Replace `FamilySharedGameRepository.probePlayerData`'s discard-the-result behavior with a
      call to the new single-game entry point from Task 1.1, so a successful manual import persists
      real achievement data; verify with a test that after `importManually` succeeds and Steam
      returns achievement data, `AchievementDao.observeForGame(appId)` returns non-empty rows and a
      `GameAchievementSync` row exists.
- [ ] 2.2 Update `SettingsViewModel.manualImportFeedback` only if needed so its toast text still
      accurately reflects the persisted outcome (e.g. still "Steam returned N achievements; M
      unlocked" but now backed by stored data rather than a transient count) — verify by reading the
      persisted counts rather than the in-memory probe result, and confirm existing UI tests/strings
      still pass.
- [ ] 2.3 Manually verify on-device (or via emulator, per this repo's dev-emulator constraints): paste
      a shared game's Steam link, confirm the success message, then open that game's detail screen
      and confirm the achievement list is populated.

## 3. Stop excluding family-shared games from the NEVER tier

- [ ] 3.1 Thread the game's source into `AchievementFreshness.selectByTier` (or an equivalent input)
      so a family-shared game with `playtimeForever == 0` is not classified `NEVER` and is instead
      included in the missing-data-eligible set whenever it has no stored `GameAchievementSync` row;
      keep owned-game classification byte-for-byte unchanged. Verify with unit tests: an owned game
      with zero playtime still lands in `NEVER` and is excluded; a family-shared game with zero
      playtime and no stored metadata lands in the missing-data-eligible set.
- [ ] 3.2 Wire automatic presence-based admission (the `SteamSyncWorker`/`SharedGameConverter`
      admission path) to enqueue a fetch via the Task 1.1 entry point immediately after a new
      family-shared game row is created, independent of that sync's tier selection. Verify with a
      test that a freshly-admitted family-shared game gets a fetch attempt in the same sync it was
      admitted in, even with zero tracked playtime.
- [ ] 3.3 Confirm (via the Task 3.1 change) that already-admitted family-shared games with no stored
      achievement data are naturally picked up by the existing bounded, oldest-first missing-data
      selection on their library's next sync — no separate backfill mechanism needed beyond the tier
      fix. Verify with a test seeding several pre-existing family-shared games with no
      `GameAchievementSync` row and asserting they appear in `missingDataOverride` up to the existing
      cap.

## 4. Spec-facing verification

- [ ] 4.1 Verify `SmartCollections`'s Completed derivation requires no code change: add a test
      seeding a family-shared game with all achievements now persisted (post Task 2/3) and confirm it
      is classified completed, disclosed as achievement-determined, exercising the existing
      `smart-collections` "All achievements unlocked" scenario end-to-end through the fixed data path.
- [ ] 4.2 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm all existing and new
      tests pass.
- [ ] 4.3 Run `openspec validate fix-shared-game-achievement-visibility --strict` and confirm it
      passes before this change is applied.

## 1. Single-game fetch-and-persist entry point

- [x] 1.1 Add a single-game `refreshOne(appId)`-style entry point to `AchievementRepository` that
      reuses the existing fetch → `AchievementMerge.merge` → `AchievementDao.upsertAll` →
      `GameAchievementSync` upsert path used internally by `syncLibraryGames`/`applyRefresh`, and
      verify with a unit/integration test that calling it for a single appId persists `Achievement`
      rows and a `GameAchievementSync` row identical in shape to what a full sync would produce for
      that game.
      Done: `AchievementRepository.refreshOne` added, returning a `SingleGameRefresh` result;
      covered by `AchievementRepositoryTest`'s `refreshOne persists achievement data independent of
      tier`.
- [x] 1.2 Verify the new entry point handles "no usable player data" and transport-error cases the
      same way `applyRefresh` already does (skip without throwing, leave prior data intact), per
      `steam-achievements`' existing "Achievement fetch fails for a game" scenario — add/extend a
      test covering both cases.
      Done: `SingleGameRefresh` distinguishes `NoUsableData` (Steam answered, no stats) from
      `Unavailable` (transport failure) so `FamilySharedGameRepository`'s existing three-way toast
      messaging (`PlayerDataProbe.NoData` vs `Unavailable`) is preserved; covered by
      `refreshOne persists nothing when Steam returns no usable player data` and
      `refreshOne persists nothing on a transport error`.

## 2. Fix the paste-link import bug

- [x] 2.1 Replace `FamilySharedGameRepository.probePlayerData`'s discard-the-result behavior with a
      call to the new single-game entry point from Task 1.1, so a successful manual import persists
      real achievement data; verify with a test that after `importManually` succeeds and Steam
      returns achievement data, `AchievementDao.observeForGame(appId)` returns non-empty rows and a
      `GameAchievementSync` row exists.
      Done: `probePlayerData` now delegates to `AchievementRepository.refreshOne` and maps its
      result to `PlayerDataProbe`; covered by
      `importManually persists the probed achievement data, not just a summary`.
- [x] 2.2 Update `SettingsViewModel.manualImportFeedback` only if needed so its toast text still
      accurately reflects the persisted outcome (e.g. still "Steam returned N achievements; M
      unlocked" but now backed by stored data rather than a transient count) — verify by reading the
      persisted counts rather than the in-memory probe result, and confirm existing UI tests/strings
      still pass.
      Done: no change needed — `PlayerDataProbe`'s three cases (`Returned`/`NoData`/`Unavailable`)
      are unchanged, and `Returned.total`/`unlocked` are now computed from the same achievement list
      that was just persisted, so the existing toast text is already accurate. Verified by the
      unchanged `importManually ... reports achievement data` assertions still passing.
- [x] 2.3 Manually verify on-device (or via emulator, per this repo's dev-emulator constraints): paste
      a shared game's Steam link, confirm the success message, then open that game's detail screen
      and confirm the achievement list is populated.
      Done: confirmed on-device by the user.

## 3. Stop excluding family-shared games from the NEVER tier

- [x] 3.1 Thread the game's source into `AchievementFreshness.selectByTier` (or an equivalent input)
      so a family-shared game with `playtimeForever == 0` is not classified `NEVER` and is instead
      included in the missing-data-eligible set whenever it has no stored `GameAchievementSync` row;
      keep owned-game classification byte-for-byte unchanged. Verify with unit tests: an owned game
      with zero playtime still lands in `NEVER` and is excluded; a family-shared game with zero
      playtime and no stored metadata lands in the missing-data-eligible set.
      Done: `AchievementFreshness.OwnedGame` gained a `source` field (default `STEAM_OWNED`, so
      every existing call site is unaffected); `selectByTier`'s cold-tier branch now also matches
      `source == FAMILY_SHARED` regardless of `playtimeForever`. `SteamSyncWorker` and
      `AchievementRepository.fetchReconciliationGames` now pass the real source through. Covered by
      `AchievementFreshnessTest`'s new family-shared cases.
- [x] 3.2 (Revised during implementation — see design.md Decision 3 for why.) Automatic
      presence-based admission (`PresenceSessionRecorder` → `considerAdmission` → `admit`) has no
      Steam credentials on its call path and runs on a ~30s polling cadence, so it is not a suitable
      place to enqueue a network fetch. Instead, rely entirely on Task 3.1: a freshly-admitted
      family-shared game has no `GameAchievementSync` row and is never classified `NEVER`, so it is
      missing-data eligible from the moment it is admitted and is picked up at the library's next
      periodic `SteamSyncWorker` sync (which already includes shared games in its achievement scope)
      — eligible immediately, fetched at the next sync, rather than fetched at the instant of
      admission. Manual import (Task 2.1) remains the one path with a truly synchronous fetch, since
      it already has `apiKey`/`steamId` in scope.
- [x] 3.3 Confirm (via the Task 3.1 change) that already-admitted family-shared games with no stored
      achievement data are naturally picked up by the existing bounded, oldest-first missing-data
      selection on their library's next sync — no separate backfill mechanism needed beyond the tier
      fix. Verify with a test seeding several pre-existing family-shared games with no
      `GameAchievementSync` row and asserting they appear in `missingDataOverride` up to the existing
      cap.
      Done: covered by `AchievementFreshnessTest`'s
      `several already-admitted shared games with no stored data are missing-data eligible up to the
      cap` — 30 zero-playtime shared games all land in `cold`/missing-data-eligible, none in `never`,
      and the override still respects the existing `MISSING_DATA_CAP`.

## 4. Spec-facing verification

- [x] 4.1 Verify `SmartCollections`'s Completed derivation requires no code change: add a test
      seeding a family-shared game with all achievements now persisted (post Task 2/3) and confirm it
      is classified completed, disclosed as achievement-determined, exercising the existing
      `smart-collections` "All achievements unlocked" scenario end-to-end through the fixed data path.
      Done: confirmed no `SmartCollections`/`SmartCollectionGame` change was needed (it never carried
      a source field to filter on). Covered by `AchievementRepositoryTest`'s
      `a family-shared game's persisted achievements complete it in SmartCollections`, which calls
      `refreshOne`, reconstructs the same signal shape `smartCollectionSignals` derives, and asserts
      `SmartCollections.derive` reaches Completed.
- [x] 4.2 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm all existing and new
      tests pass.
      Done: `BUILD SUCCESSFUL`, all suites pass.
- [x] 4.3 Run `openspec validate fix-shared-game-achievement-visibility --strict` and confirm it
      passes before this change is applied.
      Done: `Change 'fix-shared-game-achievement-visibility' is valid`.

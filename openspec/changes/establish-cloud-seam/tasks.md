# Tasks — Establish the data-source seam

> **No behavior change, no Firebase code, no migration.** Success is defined negatively: no import of
> `data.local.entity.*` anywhere under `ui/`, and no `SettingsDataStore` in a ViewModel.
>
> **Cheapest done incrementally.** Every one of these ViewModels is already being touched by a UI
> change proposed on this branch. Folding the cleanup into those changes avoids a competing refactor.

## 1. Game / library
- [x] 1.1 `GameRepository`: return a domain `LibraryGame` instead of the Room `Game` from `library`,
  `goalGames`, and `backlog`
- [x] 1.2 `LibraryViewModel`: consume it; stop reading `HltbData` directly
- [x] 1.3 Move the HLTB match state the UI needs into a domain type, so
  `data.local.entity.HltbMatchStatus` no longer appears in `LibraryViewModel` or `LibraryScreen`

## 2. Achievements
- [x] 2.1 `AchievementRepository`: return a domain achievement type from `observeForGame`
- [x] 2.2 `GameDetailViewModel`: drop the `data.local.entity.Achievement` import and its private
  `toUi` mapper — the repository maps, the ViewModel presents
- [x] 2.3 Preserve the rarity-snapshot semantics exactly: tier and XP still derive from
  `snapshotPercent`, never the live percent

## 3. Sessions / history
- [x] 3.1 `SessionRepository`: return a domain session type
- [x] 3.2 `HistoryViewModel`: consume it instead of the Room `Session`

## 4. HLTB candidates
- [x] 4.1 Decide whether `HltbCandidate` counts as a domain type or needs a UI-facing equivalent — it
  lives in `data.hltb`, not `data.local.entity`, and is already a plain serializable class
  → **It stays.** `HltbRepository.reviewQueue` now returns `HltbReviewGame`, which carries the
  candidates already deserialized, so the entity (`HltbData`) no longer reaches the review screen.
- [x] 4.2 If it stays, note why, so the boundary rule reads as deliberate rather than unevenly applied
  → noted in the README rule section as the one explicit exception.

## 5. Settings
- [x] 5.1 A repository (or existing one) exposing the rule config and app state ViewModels need
- [x] 5.2 `HomeViewModel` and `GameDetailViewModel`: consume it instead of injecting `SettingsDataStore`

## 6. Write the rule down
- [x] 6.1 A short README section: repositories expose domain models; Room entities stay in `data/`;
  the UI never imports storage types
- [x] 6.2 Note the reason in one line — a second data source can satisfy a contract of plain types and
  cannot satisfy one made of Room entities
- [x] 6.3 Record the deferred decisions so they are not rediscovered: flavors over branches, raw-data
  mirroring over server-computed XP, and that the Steam key moves server-side if the backend polls

## 7. Verify
- [x] 7.1 `grep -rn "data.local.entity" app/src/main/java/com/example/backlogium/ui/` returns nothing
- [x] 7.2 No `SettingsDataStore` import remains under `ui/`
- [x] 7.3 Existing tests still pass; no test needed to assert a boundary that grep can check
- [x] 7.4 Confirm no screen renders differently — this change is invisible to the user
  → every mapper is field-for-field; the only structural change is that the game/HLTB join moved
  from `LibraryViewModel` into `GameRepository`, producing identical values

## 8. Beyond the original list
- [x] 8.1 `ProfileRepository` also returned Room entities (`PlayerProfile`, `DailyProgress`) to
  `HomeViewModel` and `HistoryViewModel` — via inference, so grep never caught it. Now returns
  `PlayerStats` / `DayProgress`, since a half-held boundary is not a boundary.

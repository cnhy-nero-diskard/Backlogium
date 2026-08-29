## 1. Session signals

- [x] 1.1 Add a DAO query returning, per game, the count of meaningful sessions and the end time of the most recent one, using the existing `appId` index on `sessions`
- [x] 1.2 Define `MEANINGFUL_SESSION_MINUTES = 15` in `domain/`, documented as the floor below which a launch is not play, with the relaunch case that motivated it recorded in its KDoc
- [x] 1.3 Expose the per-game session signals through a repository as domain models, keeping entities inside `data/`
- [x] 1.4 Verify the query is indexed and returns in one pass rather than per-game

## 2. The pure derivation

- [x] 2.1 Add `domain/SmartCollections.kt`: a pure object taking library games, per-game session signals, achievement counts, and `today`, returning each list's membership — no Room types, no Android, no injection
- [x] 2.2 Define the fixed thresholds as named constants beside it: dropped minimum playtime 2 hours, dropped idle period 30 days, quick-win maximum main story 6 hours, almost-done fraction 0.8
- [x] 2.3 Implement completion: all achievements unlocked; else playtime at or beyond main story where a game has no achievements; else excluded. Return the basis alongside each member
- [x] 2.4 Ensure a game whose achievement data has never been fetched is not treated as having no achievements, so the playtime fallback is not applied on that basis
- [x] 2.5 Implement quick wins, never started, almost done, and dropped per their rules
- [x] 2.6 Require at least one meaningful session for dropped, so a library with imported playtime and no session history yields no dropped games
- [x] 2.7 Exclude completed games from both almost done and dropped; allow every other overlap
- [x] 2.8 Add JVM unit tests as a table of library fixtures covering every scenario in `specs/smart-collections/spec.md`, including: the day a game crosses the idle threshold, a sub-threshold relaunch, imported playtime with no sessions, achievement data absent versus a game genuinely having none, and games missing HowLongToBeat lengths

## 3. Visibility preference

- [x] 3.1 Add a per-list visibility preference to `SettingsDataStore`, defaulting every list to visible
- [x] 3.2 Expose it through a repository as a domain model
- [x] 3.3 Confirm hiding persists across process death

## 4. Collections screen

- [x] 4.1 Present derived collections as a group visually distinct from custom collections
- [x] 4.2 Show each list's name, member count, and its rule including thresholds
- [x] 4.3 Omit any list with no members, regardless of its visibility setting
- [x] 4.4 Open a derived collection into the same member presentation custom collections use, with no management affordances
- [x] 4.5 Disclose, for each completed game, whether achievements or playtime determined it
- [x] 4.6 Convey that games without a HowLongToBeat length cannot appear in the lists that need one, so an absence reads as missing data
- [x] 4.7 Add the hide and unhide control, reachable even when every list is hidden
- [x] 4.8 Confirm nothing derived appears on Home and the Home collection banners are untouched

## 5. Non-regression

- [x] 5.1 Confirm the `collections` and `collection_members` tables, and every custom-collection behaviour — creation, membership, modes, ordering, accents, deletion, pacing — are unchanged
- [x] 5.2 Confirm no migration was added
- [x] 5.3 Confirm derived membership is never persisted anywhere

## 6. Verification

- [x] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and `./gradlew assembleDebug`
- [x] 6.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics` — no new violations were introduced; the command still reports pre-existing imports in `HomeViewModel.kt` and `SettingsViewModel.kt`.
- [ ] 6.3 Manually verify a fresh library shows no derived collections at all
- [ ] 6.4 Manually verify a game crossing the 30-day idle boundary appears among dropped games with no sync having run
- [ ] 6.5 Manually verify a brief relaunch of a dropped game leaves it dropped
- [ ] 6.6 Manually verify a game appears in both almost done and dropped where it qualifies for both
- [ ] 6.7 Manually verify hiding a list persists across a restart, and that an empty list stays absent while unhidden

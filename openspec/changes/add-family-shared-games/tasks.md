## 1. Source on the game model

- [x] 1.1 Add a `GameSource` enum in `domain/` with `STEAM_OWNED` and `FAMILY_SHARED`, documented so that adding a third value later surfaces as a compile error at each branch rather than a silent default
- [x] 1.2 Add a `source` column to `Game` with a Room migration defaulting existing rows to `STEAM_OWNED` — a widening with no data movement
- [x] 1.3 Add an excluded-app-ids table for removed shared games, with its migration
- [x] 1.4 Expose source on the domain game model and through the repositories, keeping entities inside `data/`

## 2. Presence-derived sessions

- [x] 2.1 Add `domain/PresenceSessionDeriver.kt`: a pure function over observed `(appId, observedAt)` samples plus open-session state, returning the same session-action shape `SessionDiffer` returns; no Room types, no Android
- [x] 2.2 Derive an open session on first observation, extend it across successive observations, and close it once the game is no longer observed
- [x] 2.3 Decide and document the gap tolerance that closes a session when observations stop, and record the reasoning in the deriver's KDoc
- [x] 2.4 Add JVM unit tests as a table of observation sequences: continuous play, a gap, a switch between games, an app restart mid-session, and observation resuming after a long silence
- [x] 2.5 Persist derived sessions through the same path as diffed sessions, so they are indistinguishable downstream

## 3. Partition the two mechanisms

- [x] 3.1 Feed `SessionDiffer` only games whose source is `STEAM_OWNED`, so the partition is a property of the wiring rather than a runtime check
- [x] 3.2 Feed the presence deriver only games with no Steam-reported playtime
- [x] 3.3 Add a test asserting no game can receive session actions from both mechanisms in one cycle
- [x] 3.4 Verify that XP, quest, and streak computation are unchanged for owned games

## 4. Admission

- [x] 4.1 In `LiveStatusRepository`, detect a presence app id with no row in `games` and no exclusion
- [x] 4.2 Require that a successful sync has completed since the app id was first observed before considering admission, so an unsynced owned game is never mistaken for a borrowed one
- [x] 4.3 Verify via Steam's store that the app id is a game; do not admit when the store cannot be reached, and reconsider on a later observation
- [x] 4.4 Admit with name, artwork, and genres resolved from the app id, source `FAMILY_SHARED`
- [x] 4.5 Ensure a second observation of an admitted game creates no duplicate
- [x] 4.6 Notify the player once, naming the game, when a game is admitted
- [x] 4.7 Add tests for each rejection path: already tracked, excluded, no completed sync, not a game, store unreachable

## 5. Removal and conversion

- [x] 5.1 Offer removal on a family-shared game and not on an owned one
- [x] 5.2 On removal, record the exclusion so the game is not re-admitted on subsequent play
- [x] 5.3 Add the removed-games section to Settings, hidden when empty, with reversal
- [x] 5.4 On sync, convert an admitted shared game to `STEAM_OWNED` when it appears in the library, retaining its sessions
- [x] 5.5 Store the reported lifetime playtime as the diffing baseline at conversion and create no sessions from it — mirroring first-sync baselining
- [x] 5.6 Add tests for conversion: source changes, sessions retained, no phantom session, diffing resumes on the next increase

## 6. Surfaces

- [x] 6.1 Indicate the family-shared source on game detail, subordinate to artwork and name
- [x] 6.2 Indicate it on Library rows without relying on colour alone
- [x] 6.3 Disclose that a shared game's tracked playtime is what the app observed, not a Steam total, wherever that playtime is shown
- [x] 6.4 Point the disclosure at the background presence monitoring setting when it is not enabled
- [x] 6.5 Include shared games in Analytics totals and make their contribution distinguishable
- [x] 6.6 Exclude games from metrics they cannot support rather than contributing a zero
- [x] 6.7 Confirm owned games are presented exactly as they are today, with no source marking anywhere

## 7. Achievements

- [x] 7.1 Verify against a real borrowed game whether `GetPlayerAchievements` returns data for a family-shared title, and record the finding in `design.md`
- [x] 7.2 Where achievements are reported, confirm the existing achievement, rarity, rarity-XP, and rarity-standing surfaces work unmodified
- [x] 7.3 Where no achievement data is reported, present no achievement surface rather than an empty one
- [x] 7.4 Parse a numeric app id or Steam Store URL into an app id, with JVM tests for accepted and rejected input
- [x] 7.5 Add a typed manual-import result that checks the current owned library, tracked/excluded state, Store game type, and player-achievement response before reporting success
- [x] 7.6 Add a Settings card for manual Family Shared import with busy, validation, success, and Steam-data result states
- [ ] 7.7 Add repository and Settings view-model tests covering owned, shared, excluded, invalid, unavailable, achievement-data, and no-data outcomes
- [x] 7.8 Manually verify importing a real borrowed game from its Store URL and record the achievement-data result in `design.md`

## 8. Verification

- [x] 8.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and `./gradlew assembleDebug`
- [x] 8.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [x] 8.3 Manually verify admission end to end: play a borrowed game, confirm the notification, the new entry, its artwork and genres
- [x] 8.4 Manually verify a derived session earns XP and counts toward the daily quest
- [x] 8.5 Manually verify removal, that further play does not re-admit, and that reversal works
- [x] 8.6 Manually verify that owned-game sync, sessions, and XP are unchanged throughout

## Historical verification limits and current status

The original cloud-environment limitations below are retained as historical context. They are now
superseded for tasks 3.4 and 8.1: Room generated the v21 schema, and
`./gradlew :gamification:test :app:testDebugUnitTest assembleDebug --no-daemon` completed
successfully on 2026-08-23. The passing engine suite verifies owned-game XP, quest, and streak
computation.

Tasks 7.1, 7.8, and 8.3-8.6 were completed by manual on-device verification with a real borrowed
game on 2026-08-24 (see decision 7 in `design.md` for the 7.1/7.8 achievement-data finding). Only
**7.7** remains: repository and Settings view-model tests covering the manual-import outcome
matrix (owned, shared, excluded, invalid, unavailable, achievement-data, no-data) — this is a code
task, not a manual one, and has not been picked up yet.

The remaining unchecked items all need something the cloud environment used for this work does not
have. They are listed here so the next session on hardware knows exactly what is outstanding rather
than re-deriving it.

- **8.1** — the Android Gradle Plugin cannot be fetched (`dl.google.com` is denied by the network
  policy) and no Android SDK is installed, so neither `assembleDebug` nor the Gradle unit-test tasks
  can run. As a partial substitute, the pure-JVM subset was compiled and run with a standalone
  Kotlin compiler and JUnit: `SessionDiffer`, `PresenceSessionDeriver`,
  `SharedGameAdmissionPolicy`, `DailyProgressAttribution` and their tests — 33 tests, all passing,
  including the pre-existing `SessionDifferTest` and `SteamSyncDayAttributionTest`, which is what
  establishes that the owned-game diffing path and day attribution are unchanged. Everything
  touching Room, Compose, or Hilt is unverified by compilation.
- **The exported Room schema, `app/schemas/com.example.backlogium.data.local.BacklogiumDatabase/21.json`,
  is missing and must be generated by a real build.** It is KSP output (`room.schemaLocation` in
  `app/build.gradle.kts`) and is committed per version, as 17–20 are; `MigrationTest` reads it from
  the androidTest assets, so `v20ToV21_...` fails with `FileNotFoundException` until it exists. It
  is deliberately not hand-authored: its `identityHash` is computed by the Room compiler, and a
  fabricated one would be a schema file that only looks authoritative. One command fixes it:

  ```bash
  ./gradlew :app:kspDebugKotlin
  git add app/schemas/com.example.backlogium.data.local.BacklogiumDatabase/21.json
  ```

- **3.4** — `GamificationUpdaterTest` and the rest of the engine suite are the real evidence here
  and could not be run (they depend on Room entities). By inspection the owned path is untouched:
  the engine reads tracked session minutes plus `backfillMinutes`, neither of which this change
  alters, and the diffing scope narrowed from every row to `source = 'STEAM_OWNED'`, which is the
  same set for any library with no shared games.
- **7.1, 7.8, 8.3 – 8.6** — completed 2026-08-24 via manual on-device verification with a real
  borrowed game; see decision 7 in `design.md` for the achievement-data finding.

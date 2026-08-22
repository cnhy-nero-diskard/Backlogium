## 1. Storage

- [x] 1.1 Add a `hidden_games` table keyed by app id, with a hidden-at timestamp and whether the hide came from the bulk action, and **no foreign key to `games`**
- [x] 1.2 Document in the entity's KDoc why it is a table rather than a `Game` column: `SteamSyncWorker` rebuilds each row from the Steam DTO and copies app-owned fields back by hand, and a missed line silently reverts the flag on the next sync — the same failure its `backfillMinutes` comment records
- [x] 1.3 Add the migration
- [x] 1.4 Add a DAO and a repository exposing the hidden set as domain models, keeping entities inside `data/`

## 2. Exclusion at the repository boundary

- [x] 2.1 Filter hidden games centrally in the repository layer so surfaces receive already-filtered data and cannot forget
- [x] 2.2 Exclude from library lists and search
- [x] 2.3 Exclude from custom collection contents, member counts, and summaries, retaining the membership rows
- [x] 2.4 Exclude from derived collections
- [x] 2.5 Exclude from analytics and history
- [x] 2.6 Make a hidden game unreachable by navigation to game detail
- [x] 2.7 Add tests asserting a hidden game is absent from every read path, including one per surface

## 3. Derived values

- [x] 3.1 Exclude hidden games from the XP input built in `GamificationUpdater.compute`
- [x] 3.2 Exclude hidden games from daily-progress attribution in `SteamSyncWorker` going forward
- [x] 3.3 Leave stored `DailyProgress` rows, quest results, and streaks untouched when a game is hidden; add a test pinning that a past met day stays met
- [x] 3.4 Add a `RecomputeSource` value for hiding and unhiding, declared as not earned, so it emits no progress events and reseeds the baseline including downward
- [x] 3.5 Add a test asserting unhiding restores exactly the XP and level that would have applied had the game never been hidden

## 4. Disclosure and the hide action

- [x] 4.1 Build the hide preview on `GamificationUpdater.compute`, running the real computation with the game excluded rather than estimating
- [x] 4.2 Present current and resulting XP and level, calling out a level drop explicitly
- [x] 4.3 State that a goal designation will be cleared, where it applies
- [x] 4.4 Clear the goal flag on hide; do not restore it on unhide
- [x] 4.5 Apply nothing when the confirmation is declined
- [x] 4.6 Disclose the same effect on unhide
- [x] 4.7 Add the hide action to game detail, returning the player to where they came from afterwards

## 5. Live status

- [x] 5.1 Resolve the in-game state to not-in-game in `LiveStatusRepository` when the reported game is hidden, so one resolution point governs every surface
- [x] 5.2 Verify the now-playing card, profile header presence line, and Library live indicator all follow from it without separate filtering
- [x] 5.3 Suppress the ongoing now-playing notification for a hidden game
- [x] 5.4 Confirm sessions are still recorded for a hidden game that is played
- [x] 5.5 Add a test asserting presence for a hidden game resolves to not-in-game

## 6. Remote work exclusion

- [x] 6.1 Exclude hidden games from achievement, schema, and global-percentage fetching
- [x] 6.2 Exclude hidden games from HowLongToBeat matching, individual and batch
- [x] 6.3 Exclude hidden games from store enrichment scheduling
- [x] 6.4 Confirm unhiding makes a game eligible for every enrichment path again
- [x] 6.5 Add tests asserting no request is issued for a hidden game on each path

## 7. Non-game bulk hide

- [x] 7.1 Deserialize the app `type` from the `appdetails` response into `StoreAppData` — it is already returned and currently discarded
- [x] 7.2 Record the type alongside the existing store enrichment result, adding no new request
- [x] 7.3 Add the migration for the stored type
- [x] 7.4 Identify library items whose recorded type is not a game; exclude items whose type has not been retrieved
- [x] 7.5 Present the candidates by name for review, hiding nothing without confirmation
- [x] 7.6 Disclose the combined XP and level effect for the group before confirming
- [x] 7.7 Ensure each bulk-hidden item can be unhidden individually
- [x] 7.8 Add tests: unknown types are never offered, nothing is hidden without confirmation, and a confirmed group is hidden together

## 8. Settings and backup

- [x] 8.1 Add the hidden-games section listing each hidden game with when it was hidden
- [x] 8.2 Offer unhide individually and unhide all, each disclosing its effect
- [x] 8.3 Offer the non-game review from the same section
- [x] 8.4 State plainly when nothing is hidden, rather than showing an unexplained empty list
- [x] 8.5 Keep the section reachable even when every game in the library is hidden
- [x] 8.6 Include the hidden set in backup export and apply it on restore
- [x] 8.7 Add a round-trip test: export with games hidden, restore, confirm they are hidden and their playtime has not re-entered XP

## 9. Verification

- [ ] 9.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and `./gradlew assembleDebug`
- [ ] 9.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [ ] 9.3 Manually verify hiding a heavily-played game: the disclosure states the real level change, and applying it matches
- [ ] 9.4 Manually verify unhiding restores XP, level, history, and collection membership exactly
- [ ] 9.5 Manually verify a hidden game survives a sync still hidden — the case the standalone table exists to prevent
- [ ] 9.6 Manually verify playing a hidden game shows no card, no indicator, and no notification, while its session is recorded
- [ ] 9.7 Manually verify the non-game bulk review names real candidates and hides nothing until confirmed

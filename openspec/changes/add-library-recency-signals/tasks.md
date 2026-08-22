## 1. Schema and Steam payload

- [x] 1.1 Add `firstSeenAt: Long?`, `lastPlayedAt: Long?`, and `returnedToPlayAt: Long?` to the `Game` entity, all nullable, defaulting to null, with KDoc stating that null on `firstSeenAt` means "present before tracking began" and is not the same as unknown
- [x] 1.2 Add migration 18→19 adding all three columns as nullable `INTEGER` with no backfill, and bump the database version
- [x] 1.3 Add `@SerialName("rtime_last_played") val rtimeLastPlayed: Long = 0` to `OwnedGameDto`, tolerating its absence
- [x] 1.4 Convert `rtime_last_played` from epoch seconds to epoch milliseconds at the mapping boundary, and treat `0` as unknown rather than as 1970

## 2. Sync-side recording

- [x] 2.1 Read each observed game's stored `lastPlayedAt` and most recent session end **before** computing the poll's writes — the dormancy evaluation depends on values this poll is about to destroy
- [x] 2.2 Give the commit path an explicit `observedPlayAt` parameter and make it the only source of "when the play happened" — the path must never call a clock for this, or no caller can supply a better value than the commit time
- [x] 2.2a For each game with a play increase, compute `previousPlayAt = max(mostRecentSessionEnd, storedLastPlayedAt)` and `observedPlayAt = min(newLastPlayedAtFromSteam, now)`; stamp `returnedToPlayAt = observedPlayAt` when `observedPlayAt - previousPlayAt >= 30 days`; leave it untouched otherwise
- [x] 2.2b Clamp `observedPlayAt` to the present, so a Steam clock running ahead of the device cannot record a return in the future
- [x] 2.2c Where Steam reports no new last-played time, fall back to the observation instant the caller supplies; where the caller has none either, record no return
- [x] 2.3 Where neither source has a value for `previousPlayAt`, record no return rather than one against an assumed zero
- [x] 2.4 Write `lastPlayedAt` on every poll as a Steam-owned field, alongside name, icon, and playtime — after 2.2 has read the old value
- [x] 2.5 Stamp `firstSeenAt` only when a non-baseline poll inserts an app id not already in `games`
- [x] 2.6 Ensure a baseline poll stamps `firstSeenAt` for nothing and records no returns, while still storing `lastPlayedAt`
- [x] 2.7 Ensure a poll never overwrites an existing `firstSeenAt`
- [x] 2.8 Persist all three fields inside the poll's existing atomic commit — no separate write, so a return can never be stored without the playtime that justified it
- [x] 2.9 Record the set of app ids a poll stamped as arrivals, and pass it to the acquisition announcement without a second query

## 3. Recency state derivation

- [x] 3.1 Add a `GameRecencyState` enum in `domain/`: `NEWLY_ADDED`, `NEWLY_PLAYED`, `RETURNED`
- [x] 3.2 Add the two constants — a 7-day badge window and a 30-day dormancy threshold — in one place with the reasoning recorded
- [x] 3.3 Implement the derivation as a pure function of `(firstSeenAt, returnedToPlayAt, playtimeForever, firstSessionAt, now)`, returning at most one state — note it does **not** read `lastPlayedAt`, since dormancy was already decided at observation time
- [x] 3.4 Implement precedence `NEWLY_PLAYED > RETURNED > NEWLY_ADDED`, and gate `NEWLY_ADDED` on `playtimeForever == 0`
- [x] 3.5 Derive `NEWLY_PLAYED` from the game's earliest recorded session, so it can fire only once per game for life
- [x] 3.6 Derive `RETURNED` solely from `returnedToPlayAt` falling within the badge window — no gap is re-measured at read time
- [x] 3.7 Add a bounded DAO query supplying the earliest session timestamp per game, so derivation does not become one query per row
- [x] 3.8 Expose the state as a domain field on the existing Library, Home, and game-detail read models — no storage type crosses into `ui/`

## 4. Badge presentation

- [x] 4.1 Add three Tabler glyphs and a state→glyph mapping in one place: sparkle for newly added, play-triangle for newly played, rotate arrow for returned
- [x] 4.2 Render the badge in `LibraryGameCell` at `Alignment.TopEnd`, since `TileSelectionIndicator` holds `TopStart`
- [x] 4.3 Render the badge on `LibraryGameRow`, checking it does not collide with the existing HLTB status badge on the game icon
- [x] 4.4 Render the badge at every density including `COMPACT_GRID`, without joining the `GameListField` ladder — recency is a live signal like currently-playing, not detail
- [x] 4.5 Render the badge on the game detail header
- [x] 4.6 Render the badge on Home's game surfaces
- [x] 4.7 Give every badge a `contentDescription` naming its state
- [ ] 4.8 Verify the badge does not weaken the currently-playing border or text colour on any surface

## 5. Last played on game detail

- [x] 5.1 Add a last-played row to the game summary section
- [x] 5.2 Render three distinct cases: a formatted date, "Never played" when `playtimeForever == 0`, and "Unknown" when playtime exists but no date does
- [x] 5.3 Format via `UiFormat` consistently with the rest of the app rather than introducing a new date format

## 6. New-acquisition announcement

- [x] 6.1 Add Preferences DataStore state holding the acquiring poll's timestamp, its app ids, and a dismissed flag
- [x] 6.2 Write it only when a non-baseline poll stamped at least one arrival; a poll with no arrivals leaves it untouched
- [x] 6.3 Replace the whole batch and clear the dismissed flag when a later poll acquires more games
- [x] 6.4 Compute visibility as `batch non-empty && now - timestamp < 24h && !dismissed` — no worker and no scheduled expiry
- [x] 6.5 Build the Home banner following the `StreakBrokenOverlay` precedent: non-modal, Home usable behind it
- [x] 6.6 Name up to three games and report the count of the rest
- [x] 6.7 Wire the view action to open the Library, and the dismiss action to set the flag
- [x] 6.8 Exclude this state from backup export

## 7. Backup

- [x] 7.1 Add optional `firstSeenAt`, `lastPlayedAt`, and `returnedToPlayAt` to the backup file's game shape
- [x] 7.2 Export all three, writing an explicit absence where unknown
- [x] 7.3 Import all three as recorded, leaving them absent when the backup predates the fields
- [x] 7.4 Ensure `BackupMergeEngine`'s game-insert path does **not** stamp `firstSeenAt` — a restore of 300 games is not 300 acquisitions
- [x] 7.5 Ensure the import path does **not** run the dormancy evaluation — restoring play history is not observing play
- [x] 7.6 Ensure an import writes nothing to the acquisition announcement state

## 8. Tests

- [x] 8.1 Unit-test the derivation across the full matrix, asserting exactly one state or none in every case
- [x] 8.2 Unit-test precedence: bought-and-played-today is newly played; long-owned-first-play is newly played, not returned
- [x] 8.3 Unit-test that newly played fires once per game and never again
- [x] 8.4 Unit-test expiry: a state present at day 6 is absent at day 8, with no write in between
- [x] 8.5 Unit-test the dormancy evaluation at poll time: `previousPlayAt` taken from the session when it is later, from the stored last-played time when it is later, and no return recorded when neither exists
- [x] 8.6 Unit-test the ordering hazard directly — a poll that advances `lastPlayedAt` past the dormancy threshold still records the return, proving the old value was read before the overwrite
- [x] 8.7 Unit-test that a poll with a play increase inside the dormancy threshold records no return and leaves an existing `returnedToPlayAt` untouched
- [x] 8.7a Unit-test the manufactured-return case from the review: previous play at day 0, Steam reports the next play at day 29, the poll runs at day 32 — assert **no** return is recorded, since the gap is 29 days in event time
- [x] 8.7b Unit-test the window-anchoring case: a return whose play occurred 3 days before the poll is recorded at the play time, so its badge expires 4 days later rather than 7
- [x] 8.7c Unit-test that a return whose play predates the poll by more than the badge window is recorded and yields no state
- [x] 8.7d Unit-test that `observedPlayAt` is clamped to the present when Steam reports a future time
- [x] 8.7e Unit-test that the commit path derives no time of its own — passing two different `observedPlayAt` values with identical stored state produces two different `returnedToPlayAt` values
- [x] 8.8 Unit-test that a baseline poll over a large library stamps zero arrivals, records zero returns, and stores last-played times
- [x] 8.9 Unit-test that a second poll observing a new app id stamps exactly that one
- [x] 8.10 Unit-test that a poll never overwrites an existing `firstSeenAt`
- [x] 8.11 Unit-test that `rtime_last_played` of `0` reads as unknown, and that an absent field parses without failing
- [x] 8.12 Unit-test that a restore inserting unknown games stamps no arrivals, records no returns, and produces no announcement
- [x] 8.13 Unit-test a backup round trip preserving all three fields, and an older backup importing with all three absent
- [x] 8.14 Unit-test restore timeline semantics: a backup older than the badge window restores to no states; a backup taken inside it restores to the states its recorded times imply
- [x] 8.15 Unit-test the announcement lifecycle: shown, dismissed, superseded by a later acquisition, and expired by time alone
- [x] 8.16 Migration test: an existing v18 database opens at v19 with all three columns null and no data loss

## 9. Verification

- [ ] 9.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [ ] 9.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [ ] 9.3 On device: upgrade an existing install and confirm no badges appear, no banner appears, and last-played dates populate after one sync
- [ ] 9.4 On device: confirm a game with playtime but no Steam date reads "Unknown", and a zero-playtime game reads "Never played"
- [ ] 9.5 On device: check the badge at all three densities, including that it survives compact grid and coexists with selection mode
- [ ] 9.6 On device: restore a backup older than the badge window containing games absent from the current library, and confirm no badges and no banner
- [ ] 9.7 On device: restore a backup taken within the badge window and confirm its recorded signals reappear as they stood, and that no banner is shown

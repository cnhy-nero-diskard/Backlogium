## 1. Schema and Steam payload

- [ ] 1.1 Add `firstSeenAt: Long?` and `lastPlayedAt: Long?` to the `Game` entity, both nullable, defaulting to null, with KDoc stating that null on `firstSeenAt` means "present before tracking began" and is not the same as unknown
- [ ] 1.2 Add migration 16→17 adding both columns as nullable `INTEGER` with no backfill, and bump the database version
- [ ] 1.3 Add `@SerialName("rtime_last_played") val rtimeLastPlayed: Long = 0` to `OwnedGameDto`, tolerating its absence
- [ ] 1.4 Convert `rtime_last_played` from epoch seconds to epoch milliseconds at the mapping boundary, and treat `0` as unknown rather than as 1970

## 2. Sync-side recording

- [ ] 2.1 Write `lastPlayedAt` on every poll as a Steam-owned field, alongside name, icon, and playtime
- [ ] 2.2 Stamp `firstSeenAt` only when a non-baseline poll inserts an app id not already in `games`
- [ ] 2.3 Ensure a baseline poll stamps `firstSeenAt` for nothing, while still storing `lastPlayedAt`
- [ ] 2.4 Ensure a poll never overwrites an existing `firstSeenAt`
- [ ] 2.5 Persist both fields inside the poll's existing atomic commit — no separate write
- [ ] 2.6 Record the set of app ids a poll stamped as arrivals, and pass it to the acquisition announcement without a second query

## 3. Recency state derivation

- [ ] 3.1 Add a `GameRecencyState` enum in `domain/`: `NEWLY_ADDED`, `NEWLY_PLAYED`, `RETURNED`
- [ ] 3.2 Add the two constants — a 7-day badge window and a 30-day dormancy threshold — in one place with the reasoning recorded
- [ ] 3.3 Implement the derivation as a pure function of `(firstSeenAt, lastPlayedAt, playtimeForever, firstSessionAt, previousSessionEndAt, now)`, returning at most one state
- [ ] 3.4 Implement precedence `NEWLY_PLAYED > RETURNED > NEWLY_ADDED`, and gate `NEWLY_ADDED` on `playtimeForever == 0`
- [ ] 3.5 Derive `NEWLY_PLAYED` from the game's earliest recorded session, so it can fire only once per game for life
- [ ] 3.6 Derive dormancy from the gap between the two most recent sessions; fall back to the previously observed `lastPlayedAt` when only one session exists; return no state when neither is available
- [ ] 3.7 Add a bounded DAO query supplying the earliest and two most recent session timestamps per game, so derivation does not become one query per row
- [ ] 3.8 Expose the state as a domain field on the existing Library, Home, and game-detail read models — no storage type crosses into `ui/`

## 4. Badge presentation

- [ ] 4.1 Add three Tabler glyphs and a state→glyph mapping in one place: sparkle for newly added, play-triangle for newly played, rotate arrow for returned
- [ ] 4.2 Render the badge in `LibraryGameCell` at `Alignment.TopEnd`, since `TileSelectionIndicator` holds `TopStart`
- [ ] 4.3 Render the badge on `LibraryGameRow`, checking it does not collide with the existing HLTB status badge on the game icon
- [ ] 4.4 Render the badge at every density including `COMPACT_GRID`, without joining the `GameListField` ladder — recency is a live signal like currently-playing, not detail
- [ ] 4.5 Render the badge on the game detail header
- [ ] 4.6 Render the badge on Home's game surfaces
- [ ] 4.7 Give every badge a `contentDescription` naming its state
- [ ] 4.8 Verify the badge does not weaken the currently-playing border or text colour on any surface

## 5. Last played on game detail

- [ ] 5.1 Add a last-played row to the game summary section
- [ ] 5.2 Render three distinct cases: a formatted date, "Never played" when `playtimeForever == 0`, and "Unknown" when playtime exists but no date does
- [ ] 5.3 Format via `UiFormat` consistently with the rest of the app rather than introducing a new date format

## 6. New-acquisition announcement

- [ ] 6.1 Add Preferences DataStore state holding the acquiring poll's timestamp, its app ids, and a dismissed flag
- [ ] 6.2 Write it only when a non-baseline poll stamped at least one arrival; a poll with no arrivals leaves it untouched
- [ ] 6.3 Replace the whole batch and clear the dismissed flag when a later poll acquires more games
- [ ] 6.4 Compute visibility as `batch non-empty && now - timestamp < 24h && !dismissed` — no worker and no scheduled expiry
- [ ] 6.5 Build the Home banner following the `StreakBrokenOverlay` precedent: non-modal, Home usable behind it
- [ ] 6.6 Name up to three games and report the count of the rest
- [ ] 6.7 Wire the view action to open the Library, and the dismiss action to set the flag
- [ ] 6.8 Exclude this state from backup export

## 7. Backup

- [ ] 7.1 Add optional `firstSeenAt` and `lastPlayedAt` to the backup file's game shape
- [ ] 7.2 Export both, writing an explicit absence where unknown
- [ ] 7.3 Import both, leaving them absent when the backup predates the fields
- [ ] 7.4 Ensure `BackupMergeEngine`'s game-insert path does **not** stamp `firstSeenAt` — a restore of 300 games is not 300 acquisitions
- [ ] 7.5 Ensure an import writes nothing to the acquisition announcement state

## 8. Tests

- [ ] 8.1 Unit-test the derivation across the full matrix, asserting exactly one state or none in every case
- [ ] 8.2 Unit-test precedence: bought-and-played-today is newly played; long-owned-first-play is newly played, not returned
- [ ] 8.3 Unit-test that newly played fires once per game and never again
- [ ] 8.4 Unit-test expiry: a state present at day 6 is absent at day 8, with no write in between
- [ ] 8.5 Unit-test dormancy from two sessions, from the fallback last-played value, and the no-signal case that must return no state
- [ ] 8.6 Unit-test that a baseline poll over a large library stamps zero arrivals and stores last-played times
- [ ] 8.7 Unit-test that a second poll observing a new app id stamps exactly that one
- [ ] 8.8 Unit-test that a poll never overwrites an existing `firstSeenAt`
- [ ] 8.9 Unit-test that `rtime_last_played` of `0` reads as unknown, and that an absent field parses without failing
- [ ] 8.10 Unit-test that a restore inserting unknown games stamps no arrivals and produces no announcement
- [ ] 8.11 Unit-test a backup round trip preserving both fields, and an older backup importing with both absent
- [ ] 8.12 Unit-test the announcement lifecycle: shown, dismissed, superseded by a later acquisition, and expired by time alone
- [ ] 8.13 Migration test: an existing v16 database opens at v17 with both columns null and no data loss

## 9. Verification

- [ ] 9.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [ ] 9.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [ ] 9.3 On device: upgrade an existing install and confirm no badges appear, no banner appears, and last-played dates populate after one sync
- [ ] 9.4 On device: confirm a game with playtime but no Steam date reads "Unknown", and a zero-playtime game reads "Never played"
- [ ] 9.5 On device: check the badge at all three densities, including that it survives compact grid and coexists with selection mode
- [ ] 9.6 On device: restore a backup containing games absent from the current library and confirm no badges and no banner

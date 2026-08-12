## ADDED Requirements

### Requirement: A hidden game is absent from every surface
The system SHALL omit hidden games from every surface where a game would otherwise appear,
including library lists, search results, collection contents, derived collections, analytics,
history, and game detail. A hidden game SHALL NOT be reachable by navigation.

#### Scenario: Absent from the Library
- **WHEN** a game is hidden and the player views the Library
- **THEN** it does not appear in any list

#### Scenario: Absent from search
- **WHEN** the player searches for a hidden game by name
- **THEN** no result for it is returned

#### Scenario: Absent from collections
- **WHEN** a hidden game is a member of a custom collection
- **THEN** it does not appear among that collection's contents, and the collection's member count
  and summary reflect its absence

#### Scenario: Absent from derived collections
- **WHEN** a hidden game satisfies a derived collection's rule
- **THEN** it does not appear in that collection

#### Scenario: Absent from analytics and history
- **WHEN** analytics or history are viewed
- **THEN** no hidden game contributes to any figure and none of its sessions are listed

#### Scenario: Not reachable
- **WHEN** navigation to a hidden game's detail is attempted
- **THEN** it is not presented

### Requirement: A hidden game contributes to no derived value
The system SHALL exclude hidden games from XP and level computation. Hidden games SHALL NOT
contribute to daily progress from the point they are hidden onward.

#### Scenario: XP excludes a hidden game
- **WHEN** a game with recorded playtime is hidden
- **THEN** total XP and level are recomputed without its contribution

#### Scenario: Future daily progress excludes it
- **WHEN** a hidden game is played after being hidden
- **THEN** that playtime does not count toward the day's quest progress

#### Scenario: Unhiding restores the contribution
- **WHEN** a hidden game is unhidden
- **THEN** its playtime contributes to XP and level again, restoring the values that would have
  applied had it never been hidden

### Requirement: Historical days and streaks are not rewritten
The system SHALL NOT alter previously recorded daily quest results or streaks when a game is
hidden. A day already recorded as meeting its quest SHALL remain so.

#### Scenario: A past met day is preserved
- **WHEN** a game whose play satisfied a past day's quest is hidden
- **THEN** that day remains recorded as met and the streak that included it is unchanged

#### Scenario: The longest streak is untouched
- **WHEN** any game is hidden
- **THEN** the recorded longest streak is not lowered

#### Scenario: Current streak continuity
- **WHEN** a game is hidden
- **THEN** the current streak is not broken by the act of hiding

### Requirement: The effect of hiding is disclosed before it is applied
The system SHALL state the concrete consequences of hiding before the player confirms it,
including the resulting XP and level computed rather than estimated, and including the loss of any
goal designation.

#### Scenario: XP and level effect stated
- **WHEN** the player initiates hiding a game with recorded playtime
- **THEN** the current and resulting XP and level are stated

#### Scenario: Level change called out
- **WHEN** hiding would lower the player's level
- **THEN** that is stated explicitly before confirmation

#### Scenario: Goal loss stated
- **WHEN** the game being hidden is a goal game
- **THEN** the disclosure states that its goal designation will be cleared

#### Scenario: No effect without confirmation
- **WHEN** the player declines the confirmation
- **THEN** nothing is hidden, no derived value changes, and no goal is cleared

#### Scenario: A never-played game
- **WHEN** the game being hidden has no recorded playtime
- **THEN** the disclosure reflects that no XP or level change results

### Requirement: Hiding destroys nothing and is reversible
The system SHALL retain all data belonging to a hidden game — sessions, achievements, HowLongToBeat
data, and collection memberships. Unhiding SHALL restore the game to exactly the state it would
have been in had it never been hidden, except for its goal designation.

#### Scenario: Nothing deleted
- **WHEN** a game is hidden
- **THEN** its sessions, achievements, and stored metadata remain

#### Scenario: Collection membership retained
- **WHEN** a hidden game that belonged to a collection is unhidden
- **THEN** it appears in that collection again without the player re-adding it

#### Scenario: History restored
- **WHEN** a game is unhidden
- **THEN** its sessions appear in history again and contribute to analytics

#### Scenario: Goal not restored
- **WHEN** a game whose goal designation was cleared by hiding is unhidden
- **THEN** it is not a goal game, and the player may designate it again

### Requirement: A hidden game that is running is treated as not running
Where the game the player is currently in is hidden, the system SHALL resolve the in-game state to
not-in-game. No surface SHALL name, depict, or otherwise indicate the hidden game as running.

#### Scenario: Presence reports a hidden game
- **WHEN** Steam reports the player is in a game that is hidden
- **THEN** the in-game state resolves to not in a game

#### Scenario: No now-playing presentation
- **WHEN** a hidden game is running
- **THEN** no now-playing card, presence line, or live indicator names or depicts it

#### Scenario: No notification
- **WHEN** a hidden game is running
- **THEN** no ongoing now-playing notification is shown for it

#### Scenario: Sessions still recorded
- **WHEN** a hidden game is played
- **THEN** its sessions are still recorded, so that unhiding restores its full history

### Requirement: Hidden games consume no remote work
The system SHALL NOT issue achievement, achievement-schema, global-percentage, HowLongToBeat, or
store-metadata requests for hidden games.

#### Scenario: Excluded from achievement refresh
- **WHEN** achievement data is refreshed
- **THEN** no request is made for any hidden game

#### Scenario: Excluded from HowLongToBeat matching
- **WHEN** HowLongToBeat data is fetched, individually or in batch
- **THEN** hidden games are not included

#### Scenario: Excluded from store enrichment
- **WHEN** store metadata is enriched
- **THEN** hidden games are not enqueued

#### Scenario: Unhiding resumes normal treatment
- **WHEN** a game is unhidden
- **THEN** it is eligible for every enrichment path again

### Requirement: Non-game library items can be hidden in bulk
The system SHALL identify library items that Steam's store reports as something other than a game
and SHALL offer to hide them together. The system SHALL present exactly which items would be hidden
and SHALL hide nothing without confirmation. Items whose type is unknown SHALL NOT be offered.

#### Scenario: Non-games offered
- **WHEN** the library contains items the store reports as applications or tools
- **THEN** the player is offered the option to hide them, with each named

#### Scenario: Confirmation required
- **WHEN** the bulk offer is presented and not confirmed
- **THEN** nothing is hidden

#### Scenario: Nothing hidden automatically
- **WHEN** store metadata identifies a library item as a non-game
- **THEN** it is not hidden until the player confirms

#### Scenario: Unknown types excluded
- **WHEN** an item's type has not been retrieved
- **THEN** it is not offered for bulk hiding, and is not assumed to be a game or a non-game

#### Scenario: Bulk hiding is individually reversible
- **WHEN** items have been hidden in bulk
- **THEN** each can be unhidden individually

#### Scenario: Effect disclosed for the group
- **WHEN** the bulk offer is confirmed
- **THEN** the combined effect on XP and level is disclosed as for an individual hide

### Requirement: Hidden games remain listed and recoverable
The system SHALL provide a list of hidden games from which any can be unhidden, individually or
together. Hidden games SHALL appear in that list regardless of being hidden everywhere else.

#### Scenario: Reviewing what is hidden
- **WHEN** the player opens the hidden-games list
- **THEN** every hidden game is named there

#### Scenario: Unhiding one
- **WHEN** the player unhides a game from the list
- **THEN** it returns to every surface and to XP

#### Scenario: Unhiding everything
- **WHEN** the player unhides all
- **THEN** no game remains hidden

#### Scenario: Nothing hidden
- **WHEN** no game is hidden
- **THEN** the list reports that nothing is hidden

### Requirement: Hidden state survives backup and restore
The system SHALL include the set of hidden games in exported backups and SHALL apply it on restore.

#### Scenario: Export carries hidden state
- **WHEN** a backup is exported while games are hidden
- **THEN** the export records which games are hidden

#### Scenario: Restore reapplies hidden state
- **WHEN** a backup recorded with hidden games is restored
- **THEN** those games are hidden again, and their playtime does not re-enter XP

#### Scenario: Restore from a backup with none hidden
- **WHEN** a backup recorded before anything was hidden is restored
- **THEN** no game is hidden

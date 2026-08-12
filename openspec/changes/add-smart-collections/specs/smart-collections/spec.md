## ADDED Requirements

### Requirement: Derived collections are computed, never stored
The system SHALL determine each derived collection's membership from current library, session,
achievement, and HowLongToBeat state together with the current date, rather than from stored
membership. Membership SHALL reflect the passage of time without requiring a sync or any user
action.

#### Scenario: Membership follows the facts
- **WHEN** a game's playtime, sessions, or achievements change
- **THEN** its membership in every derived collection reflects the new state the next time they are
  shown

#### Scenario: Membership changes with the date alone
- **WHEN** a day boundary passes such that a game's last meaningful session is now older than the
  idle period
- **THEN** it appears among dropped games without a sync having run and without the player having
  done anything

#### Scenario: No stored membership
- **WHEN** derived collections are examined
- **THEN** no persisted membership exists for them, and none can become stale

### Requirement: A meaningful session is at least fifteen minutes
The system SHALL treat a play session shorter than fifteen minutes as not constituting play for the
purpose of derived collections. A game's last meaningful play and its meaningful session count
SHALL disregard shorter sessions.

#### Scenario: A brief launch does not count as playing
- **WHEN** a game's only session lasted under fifteen minutes
- **THEN** the game is treated as having no meaningful play

#### Scenario: A brief relaunch does not resume a dropped game
- **WHEN** a dropped game is launched for under fifteen minutes and closed
- **THEN** it remains among dropped games, because its last meaningful play is unchanged

#### Scenario: A qualifying session counts
- **WHEN** a session lasts fifteen minutes or longer
- **THEN** it counts as meaningful play and updates the game's last meaningful play

### Requirement: Completed games are determined by achievements first
The system SHALL treat a game as completed when every one of its achievements is unlocked. Where a
game has no achievements, the system SHALL treat it as completed when its playtime is at or beyond
its HowLongToBeat main-story length. Where a game has neither achievements nor a main-story length,
the system SHALL exclude it rather than classify it. Each member SHALL disclose which rule placed it
there.

#### Scenario: All achievements unlocked
- **WHEN** a game with achievements has all of them unlocked
- **THEN** it is a completed game, disclosed as determined by achievements

#### Scenario: Achievements outstanding
- **WHEN** a game with achievements has any locked
- **THEN** it is not a completed game, regardless of its playtime

#### Scenario: No achievements, playtime past main story
- **WHEN** a game has no achievements and its playtime is at or beyond its main-story length
- **THEN** it is a completed game, disclosed as determined by playtime

#### Scenario: No achievements, playtime short of main story
- **WHEN** a game has no achievements and its playtime is below its main-story length
- **THEN** it is not a completed game

#### Scenario: Neither signal available
- **WHEN** a game has no achievements and no main-story length
- **THEN** it appears in no completion determination, and is not presented as incomplete

#### Scenario: Achievement data not yet fetched
- **WHEN** a game's achievement data has never been retrieved
- **THEN** it is not treated as having no achievements, and the playtime fallback is not applied on
  that basis

### Requirement: Quick wins are unstarted short games
The system SHALL present as quick wins those games with no recorded playtime whose HowLongToBeat
main-story length is at or under six hours. A game with no main-story length SHALL NOT appear.

#### Scenario: A short unstarted game
- **WHEN** a game has never been played and its main story is four hours
- **THEN** it is a quick win

#### Scenario: A long unstarted game
- **WHEN** a game has never been played and its main story is forty hours
- **THEN** it is not a quick win

#### Scenario: A short game already started
- **WHEN** a game with a three-hour main story has recorded playtime
- **THEN** it is not a quick win

#### Scenario: No length known
- **WHEN** an unstarted game has no main-story length
- **THEN** it is not a quick win, and its absence reflects missing data rather than an assessment

### Requirement: Never-started games have no recorded playtime
The system SHALL present as never started those games with no recorded playtime from any source —
neither Steam-reported playtime, nor imported historical playtime, nor any meaningful session.

#### Scenario: Genuinely untouched
- **WHEN** a game has no playtime from any source
- **THEN** it is never started

#### Scenario: Imported historical playtime counts as played
- **WHEN** a game's only playtime came from an imported Steam history
- **THEN** it is not never started

#### Scenario: A brief launch does not start a game
- **WHEN** a game's only session was under fifteen minutes and it has no other recorded playtime
- **THEN** it remains never started

### Requirement: Almost-done games are near their main story length
The system SHALL present as almost done those games, not already completed, whose playtime is at or
beyond eighty percent of their HowLongToBeat main-story length. A game with no main-story length
SHALL NOT appear.

#### Scenario: Past the threshold
- **WHEN** a game's playtime is 85% of its main-story length and it is not completed
- **THEN** it is almost done

#### Scenario: Short of the threshold
- **WHEN** a game's playtime is 60% of its main-story length
- **THEN** it is not almost done

#### Scenario: Already completed
- **WHEN** a game qualifies on playtime but is a completed game
- **THEN** it is not almost done

#### Scenario: No length known
- **WHEN** a game has no main-story length
- **THEN** it is not almost done

### Requirement: Dropped games have real progress, no completion, and no recent meaningful play
The system SHALL present as dropped those games with more than two hours of playtime, which are not
completed, which have at least one meaningful session on record, and whose last meaningful session
was more than thirty days ago.

#### Scenario: Abandoned mid-game
- **WHEN** a game has five hours played, is not completed, and its last meaningful session was six
  weeks ago
- **THEN** it is dropped

#### Scenario: Played recently
- **WHEN** a game's last meaningful session was a week ago
- **THEN** it is not dropped

#### Scenario: Barely started
- **WHEN** a game has forty minutes of playtime and has not been touched for months
- **THEN** it is not dropped, because it never had enough progress to abandon

#### Scenario: Finished rather than abandoned
- **WHEN** a game is a completed game and has not been played for months
- **THEN** it is not dropped

#### Scenario: Playtime with no session history
- **WHEN** a game has substantial playtime but no meaningful session on record, such as playtime
  known only from an imported history
- **THEN** it is not dropped, because the app has no observation of when it was last played

#### Scenario: A brief relaunch does not rescue it
- **WHEN** a dropped game is launched for ten minutes
- **THEN** it remains dropped

### Requirement: Derived collections may overlap
The system SHALL allow a game to belong to more than one derived collection where it satisfies more
than one rule, except where a rule explicitly excludes another's members.

#### Scenario: Nearly finished and abandoned
- **WHEN** a game is at 85% of its main story and was last meaningfully played two months ago
- **THEN** it appears among both almost-done and dropped games

#### Scenario: Unstarted and short
- **WHEN** a game has never been played and has a three-hour main story
- **THEN** it appears among both never-started games and quick wins

#### Scenario: Completion excludes the others
- **WHEN** a game is completed
- **THEN** it appears in neither almost-done nor dropped games

### Requirement: Derived collections cannot be modified
The system SHALL NOT permit adding a game to, removing a game from, reordering, renaming, or
otherwise editing a derived collection. Derived collections SHALL carry no mode, target date, or
manual sequence.

#### Scenario: No membership editing
- **WHEN** the player views a derived collection
- **THEN** no operation is offered that would add, remove, or reorder its members

#### Scenario: No renaming or restyling
- **WHEN** the player views a derived collection
- **THEN** it cannot be renamed, described, or given an accent

#### Scenario: No modes
- **WHEN** a derived collection is presented
- **THEN** it presents no deadline countdown, no queue sequencing, and no collection-level goal

#### Scenario: Custom collections unaffected
- **WHEN** derived collections exist
- **THEN** every existing capability of custom collections — creation, membership, modes, ordering,
  accents, deletion — behaves exactly as before

### Requirement: Each derived collection states its rule
The system SHALL present, with each derived collection, the rule that determines its membership,
including the thresholds that rule uses.

#### Scenario: Rule visible
- **WHEN** the player views a derived collection
- **THEN** the criterion and its thresholds are readable

#### Scenario: A member's basis is explicable
- **WHEN** a completed game is presented
- **THEN** it discloses whether achievements or playtime placed it there

#### Scenario: Missing data is distinguishable
- **WHEN** a derived collection depends on HowLongToBeat lengths and some games lack them
- **THEN** their absence is attributable to missing data rather than read as an assessment

### Requirement: Derived collections can be hidden, and empty ones do not appear
Each derived collection SHALL be individually hideable, and the setting SHALL persist. A derived
collection with no members SHALL NOT be presented regardless of its setting.

#### Scenario: Hiding a list
- **WHEN** the player hides a derived collection
- **THEN** it no longer appears, and remains hidden after the app restarts

#### Scenario: Showing it again
- **WHEN** the player unhides it
- **THEN** it appears again if it has members

#### Scenario: Empty list absent
- **WHEN** a derived collection has no members
- **THEN** it is not presented even though it is not hidden

#### Scenario: A fresh library
- **WHEN** the player has just configured the app and no derived collection has members
- **THEN** no derived collection is presented at all

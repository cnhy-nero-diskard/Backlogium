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
- **WHEN** a day boundary passes such that a game's last play is now older than the idle period
- **THEN** it appears among dropped games without a sync having run and without the player having
  done anything

#### Scenario: No stored membership
- **WHEN** derived collections are examined
- **THEN** no persisted membership exists for them, and none can become stale

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
- **WHEN** a game with a three-hour main story has any recorded playtime
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

#### Scenario: A tracked session counts as played
- **WHEN** a game's only playtime came from a session Backlogium observed
- **THEN** it is not never started

### Requirement: Almost-done games are near their main story length and near their achievements
The system SHALL present as almost done those games, not already completed, whose playtime is at or
beyond eighty percent of their HowLongToBeat main-story length **and** which have unlocked at least
eighty percent of their achievements. The achievement condition is not negotiable: playtime alone
SHALL NOT place a game in this list. Where a game is known to have no achievements, playtime alone
SHALL decide. Where a game's achievements have never been fetched, it SHALL NOT appear, because an
unmet condition and an unknown one must not look the same. A game with no main-story length SHALL
NOT appear.

#### Scenario: Past both thresholds
- **WHEN** a game's playtime is 85% of its main-story length, 90% of its achievements are unlocked,
  and it is not completed
- **THEN** it is almost done

#### Scenario: Playtime past, achievements far short
- **WHEN** a game has 40 hours played against a 32-hour length but under half its achievements
  unlocked
- **THEN** it is not almost done, because achievements are the evidence of what was accomplished

#### Scenario: Short of the playtime threshold
- **WHEN** a game's playtime is 60% of its main-story length
- **THEN** it is not almost done

#### Scenario: A game with no achievements is judged on playtime
- **WHEN** a game confirmed to have no achievements is past 80% of its main-story length
- **THEN** it is almost done

#### Scenario: Achievements never fetched
- **WHEN** a game past 80% of its main-story length has never had its achievements retrieved
- **THEN** it is not almost done, and its absence reflects missing data rather than an assessment

#### Scenario: Already completed
- **WHEN** a game qualifies on both thresholds but is a completed game
- **THEN** it is not almost done

#### Scenario: No length known
- **WHEN** a game has no main-story length
- **THEN** it is not almost done

### Requirement: Dropped games have real progress, no completion, and no recent play
The system SHALL present as dropped those games with more than an hour and a half of playtime, which
are not completed, and whose last play was more than thirty days ago. Last play SHALL be taken from
whichever source knows it — Steam's own last-played time or a session Backlogium observed, whichever
is later — so a game the app has never watched is still recognised as abandoned. A game whose last
play is unknown from every source SHALL NOT appear.

#### Scenario: Abandoned mid-game
- **WHEN** a game has five hours played, is not completed, and was last played six weeks ago
- **THEN** it is dropped

#### Scenario: Never watched by the app
- **WHEN** a game has hours of Steam playtime, no session Backlogium ever observed, and Steam
  reports it was last played a year ago
- **THEN** it is dropped, on Steam's record of when it was last played

#### Scenario: Played recently
- **WHEN** a game was last played a week ago
- **THEN** it is not dropped

#### Scenario: Barely started
- **WHEN** a game has an hour of playtime and has not been touched for months
- **THEN** it is not dropped, because it never had enough progress to abandon

#### Scenario: Finished rather than abandoned
- **WHEN** a game is a completed game and has not been played for months
- **THEN** it is not dropped

#### Scenario: Last play unknown
- **WHEN** a game has substantial playtime but neither Steam nor any session says when it was last
  played
- **THEN** it is not dropped, because nothing establishes that it was abandoned

#### Scenario: A relaunch resumes it
- **WHEN** a dropped game is launched
- **THEN** it is no longer dropped, because it was played today

### Requirement: Derived collections may overlap
The system SHALL allow a game to belong to more than one derived collection where it satisfies more
than one rule, except where a rule explicitly excludes another's members.

#### Scenario: Nearly finished and abandoned
- **WHEN** a game is at 85% of its main story with 80% of its achievements unlocked, and was last
  played two months ago
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

### Requirement: Derived collections appear on Home beneath custom collections
The system SHALL present derived collections on the Home screen, always below the custom collections
and always last in that section, separated from them by a horizontal dashed rule. They SHALL appear
in their fixed order, and SHALL NOT be reorderable, editable, or removable from Home. Opening one
SHALL show the same read-only list the Collections screen opens. Hidden and empty lists SHALL be
absent from Home for the same reasons they are absent elsewhere.

#### Scenario: Always at the bottom
- **WHEN** Home shows both custom and derived collections
- **THEN** every custom collection appears above every derived one

#### Scenario: The two groups are separated
- **WHEN** derived collections are shown on Home
- **THEN** a horizontal dashed rule separates them from the custom collections above

#### Scenario: A derived list cannot be moved
- **WHEN** the player presses and holds a derived collection on Home
- **THEN** nothing is picked up, no order changes, and no reorder is persisted

#### Scenario: Custom reordering is unaffected
- **WHEN** the player reorders custom collections on Home
- **THEN** the reorder behaves exactly as before and the derived group stays below them in its own
  fixed order

#### Scenario: Opening one from Home
- **WHEN** the player taps a derived collection on Home
- **THEN** its read-only member list opens, with no management affordance

#### Scenario: Hidden and empty lists stay off Home
- **WHEN** a derived list is hidden, or has no members
- **THEN** it does not appear on Home

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

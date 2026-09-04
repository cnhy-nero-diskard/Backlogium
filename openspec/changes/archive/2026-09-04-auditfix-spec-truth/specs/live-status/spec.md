# live-status

## MODIFIED Requirements

### Requirement: Background presence tracking
The system SHALL continue observing the player's in-game presence while the app is not in the
foreground, for as long as the player remains in a game. Once the player is no longer in a
game, the system SHALL stop observing unless the player has enabled Live monitor, in which
case idle observation continues as required by "Opt-in idle presence monitoring".

#### Scenario: Presence tracked after leaving the app
- **WHEN** the player is in a game and the app is sent to the background
- **THEN** presence continues to be observed and reflected outside the app

#### Scenario: Observation stops when the game ends
- **WHEN** an observation reports that the player is no longer in a game and Live monitor is
  not enabled
- **THEN** background presence observation stops

#### Scenario: Not observing while idle
- **WHEN** the player is not in a game and Live monitor is not enabled
- **THEN** no background presence observation runs

#### Scenario: Idle observation continues under Live monitor
- **WHEN** the player is not in a game and Live monitor is enabled
- **THEN** idle presence observation continues, because the player asked for it, and the two
  requirements are not in conflict

### Requirement: Live session start time
The system SHALL record when the player was first observed in the current game and SHALL retain
that timestamp until the game ends, so elapsed session time can be presented and survives an app
restart.

#### Scenario: Start time recorded
- **WHEN** the player is first observed in a game
- **THEN** the start time for that game is recorded

#### Scenario: Elapsed time survives restart
- **WHEN** the app is restarted while the player is still in the same game
- **THEN** elapsed time continues from the recorded start time rather than restarting from zero

#### Scenario: Start time reset on game change
- **WHEN** the player is observed in a different game than the recorded one
- **THEN** the start time is replaced with the new game's first-observed time

#### Scenario: Start time cleared when the game ends
- **WHEN** the player is no longer in a game
- **THEN** the recorded start time is cleared

#### Scenario: Live session excluded from XP
- **WHEN** elapsed live session time is recorded
- **THEN** it is never used as a playtime input for XP, quests, or streaks, which are derived
  only from stored sessions

#### Scenario: Presence-derived stored sessions still count
- **WHEN** a stored session was synthesized from observed presence rather than from a Steam
  playtime delta, as required for family-shared games
- **THEN** it participates in XP, quests, streaks, history, and analytics identically to a
  playtime-derived session, because the exclusion above applies to the transient elapsed
  timer and not to the source of a stored session

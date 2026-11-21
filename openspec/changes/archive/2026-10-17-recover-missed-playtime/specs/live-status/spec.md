## MODIFIED Requirements

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
  only from durable credited activity, including stored sessions and Steam-authorized unknown-time allocations

#### Scenario: Presence-derived stored sessions still count
- **WHEN** a stored session was synthesized from observed presence rather than from a Steam
  playtime delta, as required for family-shared games
- **THEN** it participates in XP, quests, streaks, history, and analytics identically to a
  playtime-derived session, because the exclusion above applies to the transient elapsed
  timer and not to the source of a stored session

#### Scenario: Unknown-time recovery does not use the transient timer
- **WHEN** an owned-game Steam increase is recovered without timing evidence
- **THEN** its quantity comes only from the cumulative diff and the live elapsed timer does not create additional minutes

# steam-sync

## MODIFIED Requirements

### Requirement: Play-triggered targeted playtime fetch
When an observed play session ends, the system SHALL fetch the stopped game's playtime from Steam
without waiting for the next periodic poll. The fetch SHALL be scoped to that one game, so its cost
is independent of library size, and SHALL request no data other than playtime. Scoping is a
constraint on attribution and on request cost, not a claim about response shape: the request MAY
retrieve a bounded recent-game window and select the stopped game's observation from it.

#### Scenario: Session ends
- **WHEN** presence observation reports that a game which was running is no longer running
- **THEN** a targeted playtime fetch is scheduled for that game

#### Scenario: Fetch is scoped to one game
- **WHEN** a targeted playtime fetch runs
- **THEN** it requests a bounded recent-game window, selects only the stopped game's
  observation, and its request count does not grow with the size of the library

#### Scenario: Periodic cadence unchanged
- **WHEN** a targeted playtime fetch is scheduled, running, or exhausted
- **THEN** the periodic poll continues on its existing schedule, unchanged

#### Scenario: Session start is not a trigger
- **WHEN** presence observation reports that a game has started running
- **THEN** no targeted playtime fetch is scheduled

#### Scenario: Presence change that leaves the game running
- **WHEN** the player's presence state changes while the same game is still running
- **THEN** no targeted playtime fetch is scheduled

#### Scenario: Response for an unexpected game
- **WHEN** a targeted playtime fetch's response carries playtime for games other than the one
  that stopped, which a bounded recent-game window is expected to do
- **THEN** those observations are discarded and no playtime is attributed for them, so only
  the stopped game can be committed by this mechanism

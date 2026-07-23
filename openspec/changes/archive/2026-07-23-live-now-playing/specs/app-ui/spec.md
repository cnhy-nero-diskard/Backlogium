## MODIFIED Requirements

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress,
today's daily quest status, the current streak, and a "Now playing" indicator reflecting
the player's current in-game state.

#### Scenario: Viewing progress
- **WHEN** the Home screen is shown
- **THEN** it displays current level with progress toward the next level, whether today's quest is met, and the current streak count

#### Scenario: Sync now from Home
- **WHEN** the user triggers "Sync now" from Home
- **THEN** a manual poll is enqueued and the screen reflects updated state and any sync error when it completes

#### Scenario: Now playing shown while in-game
- **WHEN** the live status reports the player is in a game
- **THEN** the Home screen shows a "Now playing" indicator with the running game's name
  (and its icon when resolvable)

#### Scenario: Now playing hidden when not in-game
- **WHEN** the live status reports the player is not in a game
- **THEN** the Home screen does not show a "Now playing" indicator

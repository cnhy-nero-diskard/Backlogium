## ADDED Requirements

### Requirement: Ongoing now-playing notification
The system SHALL present an ongoing notification naming the currently played game and its elapsed
session time while the player is in a game, and SHALL remove it once the game ends.

#### Scenario: Notification shown while playing
- **WHEN** the player is in a game
- **THEN** an ongoing notification names the game and shows the elapsed session time

#### Scenario: Elapsed time kept current
- **WHEN** the session continues
- **THEN** the notification's elapsed time is updated without repeatedly alerting the user

#### Scenario: Notification removed when the game ends
- **WHEN** the player is no longer in a game
- **THEN** the notification is removed

#### Scenario: Notification permission not granted
- **WHEN** notification permission has never been granted
- **THEN** no notification is posted, no error is surfaced, and in-app now-playing presentation is
  unaffected

#### Scenario: Opening the app from the notification
- **WHEN** the user taps the notification
- **THEN** the app is opened

## MODIFIED Requirements

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress, today's daily
quest status, the current streak, and a "Now playing" indicator reflecting the player's current
in-game state. While the player is in a game, the "Now playing" indicator SHALL be the most
visually prominent element on Home, presenting enlarged game art, the game's name, and the elapsed
session time, in a color lane distinct from the accent reserved for milestone moments. When
credentials are configured, the Home screen SHALL also show a Steam account card exposing the
active SteamID and a masked API key with an action that reopens the onboarding flow. When
credentials are not configured, the Home screen SHALL present the onboarding flow as a full-screen
takeover rather than a static "Steam not configured" message.

#### Scenario: Viewing progress
- **WHEN** the Home screen is shown while configured
- **THEN** it displays current level with progress toward the next level, whether today's quest is
  met, and the current streak count

#### Scenario: Sync now from Home
- **WHEN** the user triggers "Sync now" from Home
- **THEN** a manual poll is enqueued and the screen reflects updated state and any sync error when
  it completes

#### Scenario: Prominent now-playing card
- **WHEN** the player is in a game and Home is shown
- **THEN** an enlarged card presents the game's art, its name, and the elapsed session time, and is
  visually distinct from the other cards on Home

#### Scenario: Elapsed time advances
- **WHEN** the now-playing card is displayed during a session
- **THEN** the elapsed time advances continuously without requiring a network response

#### Scenario: Milestone accent not diluted
- **WHEN** the now-playing card is displayed
- **THEN** it does not use the accent color reserved for level-up, streak-milestone, and
  completion moments

#### Scenario: Elapsed time not presented as exact
- **WHEN** elapsed session time is shown
- **THEN** it is presented as time since the session was detected, not as an exact game-launch time

#### Scenario: No game running
- **WHEN** the player is not in a game
- **THEN** no now-playing card is shown and Home's layout is unaffected

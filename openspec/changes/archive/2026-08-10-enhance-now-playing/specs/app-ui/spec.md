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

### Requirement: Live indicator on the running game in the Library
The Library SHALL mark the game the player is currently in with a live indicator, regardless of which
section that game appears in, and SHALL mark no game when the running game cannot be identified.

#### Scenario: Running game marked
- **WHEN** the player is in a game that is present in the stored library
- **THEN** that game's Library row displays a live indicator

#### Scenario: Marked in either section
- **WHEN** the running game belongs to the tracked set, or to the remaining games
- **THEN** it is marked in whichever section it appears in

#### Scenario: Ordering unaffected
- **WHEN** a game is marked as running
- **THEN** its position in the list is unchanged, so the user's chosen sort order is preserved

#### Scenario: Running game not identifiable
- **WHEN** the player is in a game whose identity cannot be resolved to a game in the stored library
- **THEN** no game is marked, rather than marking a game matched by name

#### Scenario: Not in a game
- **WHEN** the player is not in a game
- **THEN** no Library row displays a live indicator

#### Scenario: Indicator cleared when presence ends
- **WHEN** presence observation stops
- **THEN** no row continues to display a live indicator

## MODIFIED Requirements

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress, today's daily
quest status, the current streak, and a "Now playing" indicator reflecting the player's current
in-game state. While the player is in a game, the "Now playing" indicator SHALL be the most
visually prominent element on Home, presenting enlarged game art, the game's name, and the elapsed
session time, in a color lane distinct from the accent reserved for milestone moments, and SHALL convey
its active state through motion as well as color. When credentials are configured, the Home screen SHALL also show a Steam account card exposing the
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

#### Scenario: Card conveys an active state through motion
- **WHEN** the now-playing card is displayed
- **THEN** it presents continuous, ambient motion that distinguishes an active session from a static
  card, without competing with the app's celebratory milestone animations

#### Scenario: Reduced motion respected
- **WHEN** the system indicates that animations should be reduced or disabled
- **THEN** the card is presented without motion, and the active state remains legible from its elapsed
  time and its presence alone

#### Scenario: No motion when not in a game
- **WHEN** the player is not in a game
- **THEN** no now-playing animation runs

#### Scenario: Elapsed time not presented as exact
- **WHEN** elapsed session time is shown
- **THEN** it is presented as time since the session was detected, not as an exact game-launch time

#### Scenario: No game running
- **WHEN** the player is not in a game
- **THEN** no now-playing card is shown and Home's layout is unaffected

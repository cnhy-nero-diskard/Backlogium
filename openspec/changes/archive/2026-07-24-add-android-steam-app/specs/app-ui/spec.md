# app-ui

## ADDED Requirements

### Requirement: App shell and navigation
The system SHALL present a Compose UI with navigation between Home, Library, and
History screens, and all screens SHALL render from locally stored state so the app
is fully usable offline.

#### Scenario: Offline launch
- **WHEN** the app is opened without network
- **THEN** all screens display the last synced state and never block on a network call

#### Scenario: Navigating between screens
- **WHEN** the user selects a destination from the app's navigation
- **THEN** the corresponding screen (Home, Library, or History) is shown

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress,
today's daily quest status, and the current streak.

#### Scenario: Viewing progress
- **WHEN** the Home screen is shown
- **THEN** it displays current level with progress toward the next level, whether today's quest is met, and the current streak count

#### Scenario: Sync now from Home
- **WHEN** the user triggers "Sync now" from Home
- **THEN** a manual poll is enqueued and the screen reflects updated state and any sync error when it completes

### Requirement: Library screen
The system SHALL provide a Library screen separating tagged goal games (with progress
bars) from backlog games, and SHALL allow tagging a game as a goal with a target and
untagging it.

#### Scenario: Goal games with progress
- **WHEN** the Library is shown and goal games exist
- **THEN** each goal game displays its name, icon, and a progress bar against its target

#### Scenario: Managing a goal
- **WHEN** the user tags a backlog game as a goal and sets a target, or untags a goal game
- **THEN** the game moves between the goal and backlog sections and the change persists

### Requirement: History screen
The system SHALL provide a History screen listing recently synthesized sessions and
per-day play statistics.

#### Scenario: Recent sessions
- **WHEN** the History screen is shown
- **THEN** it lists recent sessions with game, date, and duration, most recent first

#### Scenario: Daily stats
- **WHEN** daily progress exists
- **THEN** the screen shows per-day totals and whether each day's quest was met

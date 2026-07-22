## MODIFIED Requirements

### Requirement: Display typography for numeral moments
The system SHALL render level number, streak count, and XP total text using a distinct
display font, and SHALL render all other text using a bundled brand body font. No text
SHALL fall back to the platform system default font.

#### Scenario: Level number uses display font
- **WHEN** the Home screen shows the player's level number
- **THEN** it is rendered in the display font, not the body font or the system default font

#### Scenario: Body text uses the brand body font
- **WHEN** any screen renders body, caption, title, or label text (e.g. section
  headers, list rows, dialog text, navigation labels)
- **THEN** it is rendered in the bundled brand body font, not the platform system default font

#### Scenario: Offline availability preserved
- **WHEN** the app runs with no network connectivity
- **THEN** both the display font and the brand body font render from bundled resources,
  with no downloadable-font dependency

### Requirement: Library screen
The system SHALL provide a Library screen separating tagged goal games from backlog
games, and SHALL allow marking a game as a goal and unmarking it. Goal games SHALL be
shown without a manual completion target; goal progress against a completion length is
deferred until a HowLongToBeat-sourced length is available.

#### Scenario: Goal games listed without a manual target
- **WHEN** the Library is shown and goal games exist
- **THEN** each goal game displays its name, icon, and playtime, and does not display a
  manual target value or a target-based progress percentage

#### Scenario: Marking a game as a goal
- **WHEN** the user marks a backlog game as a goal, or unmarks a goal game
- **THEN** the game moves between the goal and backlog sections and the change persists,
  without prompting for a typed target

## ADDED Requirements

### Requirement: Sync-in-progress feedback on Home
The system SHALL reflect an in-flight manual sync on the Home screen: while a "Sync now"
poll is running, the trigger control SHALL show a progress indicator and be disabled,
and SHALL return to its idle state when the poll completes.

#### Scenario: Sync in progress
- **WHEN** the user triggers "Sync now" and the poll has not yet completed
- **THEN** the sync control shows a progress indicator and is disabled so it cannot be
  triggered again

#### Scenario: Sync completes
- **WHEN** an in-flight sync completes (successfully or with an error)
- **THEN** the sync control returns to its enabled idle state and the screen reflects the
  updated last-sync time or any sync error

## REMOVED Requirements

### Requirement: Goal target and progress
**Reason**: The manual per-goal target (typed in minutes) was a temporary stand-in for a
completion length that should be sourced from HowLongToBeat. It produced misleading
progress (e.g. 156h played against a 4h target) and is removed until HowLongToBeat
ingestion supplies completionist-average lengths.
**Migration**: Existing goal games remain marked as goals. Their stored target values are
no longer surfaced or edited in the UI; progress bars/percentages return once
HowLongToBeat-sourced lengths are available. No user action is required.

#### Scenario: Manual target no longer offered
- **WHEN** the user marks a game as a goal
- **THEN** no typed target input is shown and no target-based progress is displayed

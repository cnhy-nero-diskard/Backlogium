## MODIFIED Requirements

### Requirement: Library screen
The system SHALL provide a Library screen separating tagged goal games from backlog
games, and SHALL allow marking a game as a goal and unmarking it. Goal games SHALL
display progress against a HowLongToBeat-sourced completion length when one is available,
and SHALL display no completion-based progress when it is not.

#### Scenario: Goal game with an HLTB length shows progress
- **WHEN** the Library is shown and a goal game has a HowLongToBeat-sourced completion length
- **THEN** the goal game displays its name, icon, and playtime, and a progress indicator measuring its playtime against that completion length

#### Scenario: Goal game without an HLTB length shows no progress
- **WHEN** the Library is shown and a goal game has no HowLongToBeat-sourced completion length yet
- **THEN** the goal game displays its name, icon, and playtime, and does not display completion-based progress

#### Scenario: Marking a game as a goal
- **WHEN** the user marks a backlog game as a goal, or unmarks a goal game
- **THEN** the game moves between the goal and backlog sections and the change persists,
  without prompting for a typed target

## ADDED Requirements

### Requirement: Refresh HowLongToBeat library trigger
The system SHALL provide a manual control to refresh HowLongToBeat data across the library, reflect that a refresh is running, and report its completion.

#### Scenario: Triggering a refresh
- **WHEN** the user triggers "Refresh HLTB library"
- **THEN** a batch refresh is enqueued and the user can leave the screen while it continues running

#### Scenario: Refresh completes
- **WHEN** a batch refresh completes
- **THEN** the user is informed of completion and any games needing review become available in the review surface

### Requirement: Per-game HowLongToBeat status and refresh
The system SHALL show each game's HowLongToBeat lookup state in the Library and SHALL let the user trigger a fresh single-game lookup, distinguishing a lookup in progress, a failed lookup, and a stored match result.

#### Scenario: Per-game status is visible
- **WHEN** the Library shows a game that has stored HowLongToBeat data (matched, needing review, or no match) or an in-progress lookup
- **THEN** the game displays its current HowLongToBeat state

#### Scenario: Refreshing a single game
- **WHEN** the user triggers a HowLongToBeat refresh for a single game
- **THEN** the system performs a fresh lookup for that game regardless of cached data and reflects the in-progress state while it runs

#### Scenario: Single-game lookup fails
- **WHEN** a single-game HowLongToBeat lookup fails
- **THEN** the failure is surfaced for that game and its cached HowLongToBeat data is not overwritten or cleared

### Requirement: HLTB match review
The system SHALL provide a surface listing games flagged as needing an HLTB match, and SHALL let the user open a flagged game and select the correct HowLongToBeat entry from its candidates.

#### Scenario: Reviewing flagged games
- **WHEN** the user opens the match-review surface and games are flagged as needing review
- **THEN** each flagged game is listed with its candidate HowLongToBeat entries available for selection

#### Scenario: Confirming a match
- **WHEN** the user selects the correct candidate for a flagged game
- **THEN** the game is marked resolved, its completion length becomes available to the goal and gamification features, and it is removed from the review list

#### Scenario: No games need review
- **WHEN** the user opens the match-review surface and no games are flagged
- **THEN** the surface indicates there is nothing to review

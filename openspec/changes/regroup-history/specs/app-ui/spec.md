## MODIFIED Requirements

### Requirement: History screen
The system SHALL provide a History screen presenting play history grouped by day, where each day
expands into the games played that day and each game expands into its individual sessions. Each day
SHALL show its total played time, its goal-game time, and whether that day's quest was met. Session
times SHALL be presented as approximate, and a session's tracked playtime SHALL be presented
distinctly from the clock range it spans.

#### Scenario: Day-grouped history
- **WHEN** the History screen is shown and play history exists
- **THEN** history is presented as a list of days, most recent first, each showing that day's total
  played time and whether its quest was met

#### Scenario: Expanding a day
- **WHEN** the user expands a day
- **THEN** the games played that day are listed, each with its art and its total played time for that
  day

#### Scenario: Expanding a game within a day
- **WHEN** the user expands a game within a day
- **THEN** that game's individual sessions for that day are listed, each with its approximate clock
  range and its tracked playtime

#### Scenario: Today expanded by default
- **WHEN** the History screen is opened
- **THEN** the current day is expanded and all earlier days are collapsed

#### Scenario: Bounded initial history
- **WHEN** the History screen is opened
- **THEN** at most 30 days are presented, and an action is offered to load earlier days

#### Scenario: Loading earlier days
- **WHEN** the user loads earlier days
- **THEN** further days are appended to the list, preserving the current expansion state

#### Scenario: Day total matches its contents
- **WHEN** a day is expanded
- **THEN** the total shown on that day's header equals the sum of the sessions listed beneath it

#### Scenario: Session spanning midnight
- **WHEN** a session began on one day and ended on the next
- **THEN** it is listed once, under the day it began, and is not divided between the two days

#### Scenario: Session times not presented as exact
- **WHEN** a session's clock range is shown
- **THEN** it is presented as approximate, reflecting that session boundaries are derived from
  periodic polling rather than observed directly

#### Scenario: Tracked playtime distinguished from elapsed range
- **WHEN** a session's tracked playtime differs from the time between its range endpoints
- **THEN** both are presented such that the tracked playtime is identifiable as playtime and the range
  is identifiable as an approximate span, so neither is read as the other

#### Scenario: Session still in progress
- **WHEN** a session is still open
- **THEN** its range is presented as open-ended and its playtime is included in its day's total

#### Scenario: Day with progress but no sessions
- **WHEN** a day has recorded progress but no individual sessions
- **THEN** its header is shown with its recorded state and offers nothing to expand

#### Scenario: Quest state remains authoritative
- **WHEN** a day's presented total differs from the stored per-day total that determined its quest
- **THEN** the quest state shown is the stored one, so the screen never contradicts whether a quest
  was met

#### Scenario: Game name unavailable
- **WHEN** a session's game is not present in the stored library
- **THEN** the session is still listed under a fallback label rather than being omitted

#### Scenario: No history yet
- **WHEN** no sessions and no daily progress exist
- **THEN** an empty state explains that history appears after playing and syncing

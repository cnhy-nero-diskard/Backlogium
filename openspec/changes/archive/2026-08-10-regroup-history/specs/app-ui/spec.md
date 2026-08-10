## MODIFIED Requirements

### Requirement: History screen
The system SHALL provide a History screen presenting play history grouped by day, where each day
expands into the games played that day and each game expands into its individual sessions. Each day
SHALL show its total played time, its goal-game time, and whether that day's quest was met. A
session's start time SHALL be presented as approximate, and its tracked playtime SHALL be presented
distinctly from that start time — never as a start–end range, since subtracting the two into a
duration can be misled by a difference that reflects how the tracked-minutes counter updates, not a
measurement error. Each day SHALL also show thumbnails for achievements
unlocked that day, capped at 5 with any excess collapsed into a count badge.

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
- **THEN** that game's individual sessions for that day are listed, each with its approximate start
  time and its tracked playtime

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
- **WHEN** a session's start time is shown
- **THEN** it is presented as approximate, reflecting that session boundaries are derived from
  periodic polling rather than observed directly

#### Scenario: Tracked playtime never paired with an end time
- **WHEN** a session's tracked playtime is shown
- **THEN** it is shown alongside only the session's approximate start, never a start–end range, so a
  reader cannot subtract two displayed clock times into a duration that may disagree with the tracked
  minutes

#### Scenario: Session still in progress
- **WHEN** a session is still open
- **THEN** it is marked as in progress and its playtime is included in its day's total

#### Scenario: Day with achievements unlocked
- **WHEN** a day has 5 or fewer achievements unlocked across the games played that day
- **THEN** its header shows a thumbnail for each unlocked achievement and no overflow badge

#### Scenario: Day with more than 5 achievements unlocked
- **WHEN** a day has more than 5 achievements unlocked
- **THEN** its header shows 5 thumbnails followed by a badge stating the remaining count

#### Scenario: Day with no achievements unlocked
- **WHEN** a day has no achievements unlocked
- **THEN** its header shows no achievement thumbnail row

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

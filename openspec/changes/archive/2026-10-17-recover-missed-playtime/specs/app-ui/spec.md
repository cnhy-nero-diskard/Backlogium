## MODIFIED Requirements

### Requirement: History screen
The system SHALL provide a History screen presenting play history grouped by day, where each day
expands into the games played that day and each game expands into its timestamped sessions and dated unknown-time allocations. Each day
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
- **THEN** timestamped sessions show approximate start and tracked minutes, and unknown-time allocations show minutes with exact session times unavailable
- **AND** allocations do not show fabricated clock times or in-progress session status

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
- **THEN** the total shown on that day's header equals the sum of the credited activity listed beneath it

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
- **WHEN** a day has recorded progress but no timestamped sessions
- **THEN** the header is shown with its recorded state
- **AND** it expands into known game allocations when those exist, otherwise offers no activity expansion

#### Scenario: Quest state remains authoritative
- **WHEN** a day's presented total differs from the stored per-day total that determined its quest
- **THEN** the quest state shown is the stored one, so the screen never contradicts whether a quest
  was met

#### Scenario: Game name unavailable
- **WHEN** a session's game is not present in the stored library
- **THEN** the session is still listed under a fallback label rather than being omitted

#### Scenario: No history yet
- **WHEN** no sessions, recovery activity or daily progress exist
- **THEN** an empty state explains that history appears after playing and syncing

### Requirement: Surfaces agree on a session's date
Every surface that presents play activity by date — history, daily progress, quest status,
streaks, and analytics — SHALL use the same attribution rule for timestamped sessions and explicit frozen dates for unknown-time allocations, so that the same session is
never shown under one date on one surface and counted toward another date elsewhere.

#### Scenario: Midnight-crossing session in history and progress
- **WHEN** a session that crossed local midnight is displayed
- **THEN** history groups it under the same date whose daily progress its minutes were
  credited to

#### Scenario: Quest and history agree
- **WHEN** a session's minutes satisfy a day's quest
- **THEN** that is the same day the session appears under in history

#### Scenario: Analytics agree
- **WHEN** per-day totals are presented in analytics
- **THEN** they reconcile with the dates history shows for the same sessions

#### Scenario: Unknown-time activity agrees across surfaces
- **WHEN** a recovery allocation contributes to a date's quest
- **THEN** History, Analytics, game breakdowns and daily totals show those same minutes on that date

## ADDED Requirements

### Requirement: Estimated activity is visible and accessible wherever dated totals are inspected
Every positive multi-day estimated allocation SHALL carry a visible ! indicator in History activity, day summaries, Analytics calendar/chart inspection and relevant game breakdowns. Aggregates mixing timed and estimated play SHALL indicate that they contain estimates. Activating the indicator SHALL explain: "Estimated playtime - X minutes recovered from a cumulative Steam total between [dates] and distributed evenly. Exact session times are unavailable." The explanation SHALL distinguish the window's recovered residual total from the selected day's share, state its allocation zone when relevant, and remain available after restart and backup import. The symbol SHALL have an accessible label and activation target, and copy SHALL be localized with locale-aware dates and amounts. Single-day unknown-time activity SHALL also disclose its derivation and absent clock boundaries without claiming multi-day distribution.

#### Scenario: Mixed day summary
- **WHEN** a day contains timestamped sessions and estimated allocations
- **THEN** its combined total and game breakdown reconcile and expose an accessible estimate explanation

#### Scenario: Inspecting a chart day
- **WHEN** a user selects an estimated date by touch or an accessible alternative
- **THEN** the date's minutes, estimated share and explanation are available without precise chart touch

#### Scenario: Single-day fallback
- **WHEN** 70 minutes were recovered onto one date
- **THEN** History describes unknown session times and does not show a fabricated start

### Requirement: Unknown-time activity does not invent session metrics
Analytics SHALL include dated recovery allocations in played-minute charts, comparative totals, day/game breakdowns and most-played games. It SHALL exclude unknown-time allocations from session counts, longest/average session duration, time-of-day buckets and related session-shape claims. Pending date-unknown quantity SHALL be disclosed separately from dated charts. If only unknown-time activity exists, session/time-of-day insights SHALL explain that session timing is unavailable rather than suggesting measured zeros.

#### Scenario: Allocations-only period
- **WHEN** a selected period contains 105 recovered minutes but no timestamped sessions
- **THEN** it shows 105 played minutes and matching games while session and time-of-day insights state that timing is unavailable

### Requirement: Recovery availability is described honestly
When Steam data is unavailable or an expected increase has not yet appeared, the interface SHALL preserve last-good figures and communicate that recovery depends on Steam publishing an updated readable cumulative total. It SHALL not claim real-time detection of Offline/Invisible or PC-offline gameplay. A successful unchanged read without independent evidence of pending play SHALL not be described as a known recovery debt. Timing anomalies with credited but undated quantity SHALL be distinguishable from unavailable remote data.

#### Scenario: Private library
- **WHEN** the owned-game read is inaccessible
- **THEN** the interface shows unavailable recovery status and retains the prior baseline and credited play

#### Scenario: Unchanged total without observed play
- **WHEN** a trustworthy total is unchanged and no independent observation indicates pending play
- **THEN** the interface reports the successful reading without inventing pending minutes

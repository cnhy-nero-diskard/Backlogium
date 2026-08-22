## ADDED Requirements

### Requirement: History presents a day's XP
Each History day SHALL present the XP attributed to it. A day that earned none SHALL present zero
rather than omitting the figure, so a day's worth is always answerable. A day whose XP comes only
from achievement unlocks SHALL present it the same way as any other day's.

#### Scenario: Day with XP
- **WHEN** a History day is shown and XP is attributed to it
- **THEN** that amount is presented on the day

#### Scenario: Day with no XP
- **WHEN** a History day has tracked play or unlocks but earned no XP
- **THEN** zero is presented rather than the figure being omitted

#### Scenario: XP from unlocks alone
- **WHEN** a day's only content is achievement unlocks
- **THEN** its attributed XP is presented in the same form as a tracked day's

#### Scenario: Day XP consistent with the total
- **WHEN** the presented days and the undated remainder are summed
- **THEN** the result equals the total XP presented on Home

### Requirement: History distinguishes a day before tracking
The History screen SHALL present a day produced from dated unlocks alone as distinct from a tracked
day: it SHALL present its played time as unknown rather than zero, SHALL present no quest state,
and SHALL offer nothing to expand for sessions it does not have. It SHALL still present that day's
achievement row and name the games those unlocks belong to.

#### Scenario: Pre-tracking day presentation
- **WHEN** a day produced from unlocks alone is shown
- **THEN** its played time reads as unknown, it shows no quest state, and it shows its unlocked
  achievements

#### Scenario: Games named without sessions
- **WHEN** a pre-tracking day's unlocks belong to one or more games
- **THEN** those games are identified on the day, without implying tracked sessions for them

#### Scenario: Nothing to expand
- **WHEN** the user attempts to expand a pre-tracking day for sessions
- **THEN** no session list is offered, and the day does not present an empty session list

#### Scenario: Not mistaken for an idle day
- **WHEN** a pre-tracking day and a tracked day with no play are both in view
- **THEN** the two are visually distinguishable

#### Scenario: Achievement row treatment unchanged
- **WHEN** a pre-tracking day has more than five unlocks
- **THEN** its achievement row follows the same capped-with-overflow treatment every other day uses

### Requirement: History pages back to the earliest dated evidence
The History screen SHALL allow loading earlier days until the earliest date carrying dated evidence
is reached, and SHALL stop offering to load earlier days once it is. Loading earlier days SHALL
preserve the current expansion state, as it does today.

#### Scenario: Loading past the first session
- **WHEN** dated unlocks exist earlier than the first tracked session and the user loads earlier days
- **THEN** the earlier dates are appended and the existing expansion state is preserved

#### Scenario: Floor reached
- **WHEN** the earliest dated evidence is in view
- **THEN** the load-earlier action is no longer offered

#### Scenario: Empty stretch between evidence
- **WHEN** a loaded range contains dates with neither sessions nor unlocks
- **THEN** those dates produce no day rows, and the days that do exist remain in date order

### Requirement: Home presents today's XP
The Home screen SHALL present the XP attributed to the current day, alongside the level and XP
progress it already carries. The figure SHALL be zero rather than absent on a day with no progress
yet, and SHALL update as the day's play and unlocks are recorded.

#### Scenario: XP earned today
- **WHEN** Home is shown and XP has been attributed to today
- **THEN** today's XP is presented alongside the level and progress

#### Scenario: Nothing earned yet today
- **WHEN** Home is shown early in a day with no recorded progress
- **THEN** today's XP is presented as zero rather than omitted

#### Scenario: Day rollover
- **WHEN** the local date advances
- **THEN** today's XP resets to describe the new day, without a sync being required

#### Scenario: Agreement with History
- **WHEN** today's XP is shown on Home and the same day is shown on History
- **THEN** the two present the same figure

#### Scenario: Home remains progress content only
- **WHEN** today's XP is added to Home
- **THEN** no account, sync, or data-management control is introduced to Home

### Requirement: The undated XP remainder is reachable from the daily accounting
Where daily XP is accounted for, the system SHALL make the undated remainder visible, naming
imported historical playtime and undated achievement unlocks as its sources, so the daily figures
and the player's total reconcile on screen.

#### Scenario: Remainder visible with the accounting
- **WHEN** the undated remainder is non-zero and daily XP is accounted for
- **THEN** the remainder is presented with its sources named

#### Scenario: Import identified as a source
- **WHEN** the player has imported Steam history
- **THEN** the remainder identifies that import as the reason part of their XP has no date

#### Scenario: Nothing shown when there is nothing to explain
- **WHEN** the undated remainder is zero
- **THEN** no remainder line is presented

## MODIFIED Requirements

### Requirement: History screen
The system SHALL provide a History screen presenting play history grouped by day, where each day
expands into the games played that day and each game expands into its individual sessions. Each day
SHALL show its total played time, its goal-game time, whether that day's quest was met, and the XP
attributed to it. A session's start time SHALL be presented as approximate, and its tracked playtime
SHALL be presented distinctly from that start time — never as a start–end range, since subtracting
the two into a duration can be misled by a difference that reflects how the tracked-minutes counter
updates, not a measurement error. Each day SHALL also show thumbnails for achievements unlocked that
day, capped at 5 with any excess collapsed into a count badge.

A day SHALL be produced for any local date carrying tracked sessions, a stored progress row, or one
or more dated achievement unlocks. A day carrying dated unlocks alone is a day before tracking began:
its played time is unknown rather than zero, it presents no quest state, and it offers no session
expansion.

#### Scenario: Day-grouped history
- **WHEN** the History screen is shown and play history exists
- **THEN** history is presented as a list of days, most recent first, each showing that day's total
  played time and whether its quest was met

#### Scenario: Day produced by unlocks alone
- **WHEN** a local date carries dated achievement unlocks and neither sessions nor a stored progress
  row
- **THEN** a day is produced for it

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
- **WHEN** a day with tracked sessions is expanded
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
- **WHEN** no sessions, no daily progress, and no dated unlocks exist
- **THEN** an empty state explains that history appears after playing and syncing

### Requirement: History day game thumbnails
A History day tile SHALL show thumbnails of the games played on that day, in a horizontal row, so a
day can be identified without expanding it. The row SHALL show a bounded number of thumbnails and
SHALL indicate how many further games the day holds, following the same capped-with-overflow
treatment the day tile's achievement row already uses. Thumbnails SHALL be ordered consistently with
the day's expanded game list.

On a day before tracking began, where no game was played but unlocks occurred, the row SHALL show
the games those unlocks belong to, so the day is still identifiable without expanding it.

#### Scenario: Day tile shows its games
- **WHEN** a History day tile represents a day with one or more games played
- **THEN** the tile shows a horizontal row of those games' thumbnails without the day being expanded

#### Scenario: Pre-tracking day tile shows its unlocked games
- **WHEN** a History day tile represents a day carrying unlocks and no tracked play
- **THEN** the row shows the games those unlocks belong to

#### Scenario: Thumbnail overflow
- **WHEN** a day holds more games than the row's cap
- **THEN** the row shows the capped number of thumbnails followed by a count of the remaining games

#### Scenario: Day with no games
- **WHEN** a History day tile represents a day with recorded progress but no games played and no
  unlocks
- **THEN** no thumbnail row is shown, rather than an empty row

#### Scenario: Thumbnail order matches the expanded list
- **WHEN** the user expands a day whose thumbnails are shown
- **THEN** the expanded game list begins with the games whose thumbnails were shown, in the same
  order

#### Scenario: Games and achievements distinguishable on one tile
- **WHEN** a day tile shows both game thumbnails and achievement icons
- **THEN** the two rows are visually distinct and separately identifiable

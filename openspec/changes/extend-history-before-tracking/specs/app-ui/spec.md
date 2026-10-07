## ADDED Requirements

### Requirement: History and Home present current-rule XP attribution
History SHALL present each evidence day's attributed XP with current-rule wording and a reachable undated remainder explanation. Home SHALL use the same projection for today's attributed XP. Unknown minutes SHALL remain unknown even when achievement XP is nonzero. Provenance/confidence qualifiers SHALL not imply historical award dates.

#### Scenario: Daily surfaces agree
- **WHEN** Home and History show today's attribution over the same inputs
- **THEN** their attributed XP values agree

#### Scenario: Unknown playtime with achievement credit
- **WHEN** an evidence-only date contributes achievement XP
- **THEN** the UI shows that XP beside unknown playtime without implying a played duration

#### Scenario: Undated explanation
- **WHEN** the player opens the accounting detail
- **THEN** imported/manual/undated unlock contributions are identified instead of folded into a date

### Requirement: History older-content actions preserve current interaction state
History SHALL page bounded calendar windows to its complete evidence floor while preserving expansion state, the current-day transition, timeline/list selection, and valid reveal-to-session actions. It SHALL not offer session expansion for an evidence-only day.

#### Scenario: Earlier data loaded
- **WHEN** the player loads another bounded window
- **THEN** existing expanded rows and valid cloud-session reveals remain stable

#### Scenario: Midnight transition
- **WHEN** today advances while History remains open
- **THEN** the current-day behavior is preserved and no new synthetic quest row appears

## MODIFIED Requirements

### Requirement: History screen
The system SHALL provide a History screen presenting play history grouped by day, where each day
expands into the games played that day and each game expands into its individual sessions. Recorded session days
SHALL show total played time and goal-game time; recorded progress-only days SHALL show their
recorded totals. Quest state SHALL be shown only when recorded daily progress supplies it.
Unlock-only dates SHALL also appear as evidence-only days with unknown playtime and unavailable
quest state. A
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
- **THEN** its header is shown with its recorded totals and quest state, offers no session expansion,
  and still permits inspecting any achievement evidence

#### Scenario: Quest state remains authoritative
- **WHEN** a day's presented total differs from the stored per-day total that determined its quest
- **THEN** the quest state shown is the stored one, so the screen never contradicts whether a quest
  was met

#### Scenario: Game name unavailable
- **WHEN** a session's game is not present in the stored library
- **THEN** the session is still listed under a fallback label rather than being omitted

#### Scenario: No history yet
- **WHEN** no sessions, daily progress, or valid dated unlocks exist
- **THEN** an empty state explains that history appears after playing and syncing


#### Scenario: Unlock-only date
- **WHEN** a dated unlock has no matching session or recorded daily progress
- **THEN** its evidence-only day appears with unknown played/Focus minutes and unavailable quest state

#### Scenario: Session day without recorded quest state
- **WHEN** sessions exist for a date but no daily-progress row records its quest outcome
- **THEN** its session totals are shown and the quest state is unavailable rather than fabricated

#### Scenario: History preserves proven cloud contributions
- **WHEN** a visible session has recorded cloud contribution evidence
- **THEN** its existing contribution badge, focused explanation, and reveal actions remain reachable

# app-ui

## ADDED Requirements

### Requirement: Surfaces agree on a session's date
Every surface that presents play activity by date — history, daily progress, quest status,
streaks, and analytics — SHALL use the same attribution rule, so that the same session is
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

### Requirement: "Today" follows the calendar, not the last data emission
A surface that labels play activity as "today" SHALL resolve the current local date whenever
that date changes, not only when the data behind it changes. A day boundary crossed with no
intervening sync SHALL NOT leave the previous day's totals or quest status presented as the
current day's.

#### Scenario: Midnight passes with no sync
- **WHEN** local midnight passes while a surface showing "today" is on screen, and no sync,
  settings change, or other data update occurs
- **THEN** the surface re-resolves the current date and presents the new day's totals,
  showing no recorded progress where the new day has none

#### Scenario: Quest status follows the same boundary
- **WHEN** the previous day's quest was met and the new day's has not yet been
- **THEN** the quest indicator reflects the new day once midnight passes, rather than
  continuing to show the previous day's satisfied state

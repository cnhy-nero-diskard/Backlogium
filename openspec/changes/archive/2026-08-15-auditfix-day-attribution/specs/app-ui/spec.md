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

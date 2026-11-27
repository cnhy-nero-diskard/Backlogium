# play-insights

## Purpose

Defines offline, evidence-aware comparisons and distributions for the player's selected Backlogium activity period.

## Requirements

### Requirement: Period comparisons use comparable recorded activity
Play insights SHALL compare the selected period with the established comparable previous period when both have adequate recorded history and a meaningful baseline. Comparisons SHALL expose both date ranges and use the same metric definition in each period.

#### Scenario: Partial first window or mismatched elapsed periods
- **WHEN** either period predates tracking or the comparable date bounds cannot be resolved
- **THEN** the insight suppresses the comparison and still shows available selected-period facts

#### Scenario: No positive baseline
- **WHEN** the prior value is zero for the selected metric
- **THEN** the insight can show absolute counts and does not show percentage growth from zero

### Requirement: Play mix and variety describe the selected period
Play insights SHALL report distinct played games, each leading game's share of visible dated minutes, and the remaining share. It SHALL identify games first recorded during the period and returns after at least 30 full local dates with no recorded play between positive dated records.

#### Scenario: A first recorded game could have prior Steam play
- **WHEN** the game's earliest supported Backlogium activity falls inside the selected period
- **THEN** the insight says first recorded and does not claim first-ever play

#### Scenario: Multiple playtime evidence types
- **WHEN** the period includes dated allocations without sessions
- **THEN** the play mix accounts for visible dated minutes and session counts are available only for recorded sessions

### Requirement: Session distribution excludes activity without a session
Session insights SHALL derive median duration and the distribution below 30, 30–59, 60–119, and at least 120 minutes from eligible finalized positive-duration sessions.

#### Scenario: Only dated allocations exist
- **WHEN** the selected period has credited dated play and no eligible sessions
- **THEN** session distribution and median are unavailable, while dated play remains in applicable metrics

### Requirement: Focus and quest consistency disclose their denominators
Focus share SHALL use current Focus membership and visible dated minutes. Quest consistency SHALL use the authoritative daily outcomes with a disclosed denominator and SHALL identify dates where an outcome is unavailable.

#### Scenario: Historical range and current Focus membership differ
- **WHEN** an older period is viewed after a Focus membership edit
- **THEN** the displayed Focus measure identifies today's tags as its membership basis

#### Scenario: Missing quest outcome
- **WHEN** a tracked date has no stored quest outcome
- **THEN** the insight reports the missing coverage and does not count that date as a failed goal

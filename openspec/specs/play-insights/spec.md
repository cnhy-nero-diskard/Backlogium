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

### Requirement: Genre minutes are allocated without double counting
Play insights SHALL allocate each game's visible dated minutes evenly across its distinct known genres and retain an Unknown category for games without genres. Genre drift SHALL compare share in percentage points only for comparable periods with adequate known genre evidence.

#### Scenario: One title has multiple genres
- **WHEN** a game with 60 minutes has two distinct known genres
- **THEN** 30 minutes contribute to each and the total remains 60 minutes

#### Scenario: Sparse genre coverage
- **WHEN** too few games or minutes have known genres in either period
- **THEN** the insight shows the available composition and withholds a drift claim

### Requirement: Weekday and clock patterns use session start evidence
Play insights SHALL group recorded session minutes by weekday and local session-start time bucket. Data with unknown session timing SHALL remain outside this matrix and the app SHALL disclose the attribution basis.

#### Scenario: Session spans midnight
- **WHEN** a completed session begins just before midnight
- **THEN** its minutes stay in the local weekday and clock bucket of its recorded start

### Requirement: Achievement trends use supported unlock dates
Play insights SHALL report windowed count and rarity for eligible unlocks with supported unlock dates. A completed game SHALL count in the period only if stored evidence dates a transition to a fully unlocked confirmed set.

#### Scenario: Missing date or confirmation
- **WHEN** the app has an unlock without a supported date or a present-day complete set with no dated completion evidence
- **THEN** it does not assign that unlock or completion to the window and discloses incomplete coverage

#### Scenario: Rarity observation evolves
- **WHEN** a dated unlock has a retained first-unlock rarity snapshot
- **THEN** the historical insight uses the retained snapshot for tier and rarest-unlock figures

#### Scenario: Still-unlocked refresh omits its date
- **WHEN** a later achievement response still confirms an unlock but has no positive unlock time
- **THEN** its prior supported unlock date remains available; a reported relock clears that date, and a later undated re-unlock does not restore it

#### Scenario: Achievement comparison is supported
- **WHEN** both selected and previous periods have comparable bounds and enough dated unlock evidence
- **THEN** Analytics shows the dated unlock counts for both periods and labels their dates

### Requirement: Personal records cite dated local evidence
Play insights SHALL expose records for the represented period using documented eligibility and stable tie handling. Returned-game and longest-gap records SHALL be based on positive recorded dates on both sides of the gap.

#### Scenario: A record depends on evidence outside the selected window
- **WHEN** a game returns in the window after a qualifying gap
- **THEN** the record reports the supported dates and identifies the earlier record as context

#### Scenario: Too little evidence
- **WHEN** a period has insufficient data for a genre, clock, comeback or record claim
- **THEN** the view shows the available facts and omits the comparative claim

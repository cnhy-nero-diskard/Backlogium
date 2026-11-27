# Play Insights Delta

## ADDED Requirements

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

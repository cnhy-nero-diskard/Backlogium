## ADDED Requirements

### Requirement: Analytics and History use figure-specific provenance
Analytics SHALL classify period/per-game recorded totals as Tracked, calculated ranking/session/timing/quest/tier summaries as Inferred, and unmodified remote values as Observed. History SHALL distinguish approximate starts, recorded amounts, and remote unlock dates using the shared explanation. Existing cloud contribution presentation SHALL remain available.

#### Scenario: Analytics time pattern
- **WHEN** the time-of-day figure appears
- **THEN** its inline qualifier and reachable explanation describe estimated start-based placement

#### Scenario: History mixed evidence
- **WHEN** History shows a shared or timing-assisted session
- **THEN** the mechanism is conservative/evidence-aware and existing contribution detail remains reachable

#### Scenario: All-time versus selected period
- **WHEN** a library-wide summary sits near period figures
- **THEN** each figure states its actual scope

### Requirement: Collection pacing explanation names its complete mechanism
Collection Personal Pace presentation SHALL integrate provenance with current confidence states and explain tracked pace/frequency, remaining HLTB work, and applicable deadline/capacity. It SHALL preserve the existing conditional deadline action and absence of Personal Pace on basic lists.

#### Scenario: Deadline explanation opened
- **WHEN** the player requests the derivation of a reliable deadline forecast
- **THEN** all applicable inputs are named instead of only a generic session disclaimer

#### Scenario: Missing or learning state
- **WHEN** the overview cannot make a definitive forecast
- **THEN** its existing confidence state remains the leading message

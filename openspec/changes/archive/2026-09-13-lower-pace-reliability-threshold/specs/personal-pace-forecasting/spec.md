## MODIFIED Requirements

### Requirement: Confidence-aware profile
The system SHALL classify Personal Pace as reliable only when the local history covers at least 14
completed dates and includes at least six active dates. A profile that does not meet both
thresholds SHALL remain in a learning state and SHALL NOT support definitive feasibility or
infeasibility claims.

The two thresholds answer different questions and both SHALL be required. The covered-date
threshold asks whether enough time has been observed to describe a pattern; the active-date
threshold asks whether enough of that time was actually spent playing. Six active dates within a
14-date span is a substantially denser requirement than the same six within 28, so shortening the
observed span SHALL NOT be accompanied by relaxing the active-date floor.

#### Scenario: Sufficient history
- **WHEN** at least 14 completed dates are covered and at least six are active
- **THEN** the Personal Pace profile is classified as reliable

#### Scenario: Too few covered dates
- **WHEN** fewer than 14 completed dates are covered
- **THEN** the profile remains learning regardless of the minutes recorded

#### Scenario: Too few active dates
- **WHEN** fewer than six covered dates contain tracked play
- **THEN** the profile remains learning regardless of the total minutes recorded

#### Scenario: A qualifying profile is reclassified without stored state
- **WHEN** a profile that was learning under the previous threshold already covers at least 14
  completed dates with at least six active
- **THEN** it is classified as reliable on its next derivation from stored sessions, with no
  migration, recompute, or stored confidence value involved

#### Scenario: An already reliable profile is unaffected
- **WHEN** a profile satisfied the previous, longer covered-date threshold
- **THEN** it remains reliable and its expected active days and expected gaming minutes are
  unchanged, because the threshold gates classification only and is not an input to the forecast

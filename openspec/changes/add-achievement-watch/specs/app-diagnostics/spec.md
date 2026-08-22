## ADDED Requirements

### Requirement: Watch observations are recorded and attributable
The system SHALL record achievement watch observations in the diagnostics record, distinguishable by
trigger from periodic, manual, post-play, and reconciliation work, and SHALL fold their requests into
the rolling request counters as it does every other request.

#### Scenario: Watch runs are distinguishable
- **WHEN** an achievement watch observation is recorded
- **THEN** its trigger identifies it as a watch observation rather than a periodic, manual, or
  reconciliation run

#### Scenario: Requests counted
- **WHEN** a watch observation issues requests
- **THEN** they are folded into the rolling request counters, keyed by route and status, as every
  other request is

#### Scenario: A discarded observation is still recorded
- **WHEN** an observation is discarded because global unlock percentages were unavailable
- **THEN** the attempt and its outcome are recorded, so a repeatedly discarded observation is
  diagnosable

#### Scenario: Recording never affects the watch
- **WHEN** recording a watch observation fails for any reason
- **THEN** the watch is unaffected and continues on its cadence

#### Scenario: No credentials recorded
- **WHEN** a watch observation's requests are recorded
- **THEN** no credential value appears in the record, since routes carry no query parameters

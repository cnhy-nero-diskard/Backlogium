# app-diagnostics

## ADDED Requirements

### Requirement: Monitoring start outcomes are recorded as presence decisions
When live monitoring is requested, the system SHALL record the outcome as a presence decision —
including a start refused by the platform, a start that failed, and monitoring ended because a
platform runtime budget was reached — so that monitoring silently ceasing to work is observable
after the fact.

#### Scenario: Start refused by the platform
- **WHEN** a monitoring start is refused by the platform
- **THEN** a presence decision records that outcome and the condition that produced it

#### Scenario: Monitoring ended on a runtime budget
- **WHEN** the platform ends monitoring because its cumulative runtime budget was reached
- **THEN** a presence decision records that cause, distinguishable from a start refusal

#### Scenario: Start not attempted
- **WHEN** monitoring is not requested because the requesting context could not legally do so
- **THEN** a presence decision records that it was not attempted, rather than leaving no record

#### Scenario: Records carry no credentials
- **WHEN** any of these presence decisions is stored
- **THEN** it contains no credential value, consistent with the existing redaction requirement

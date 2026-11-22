# First Run Setup Delta

## ADDED Requirements

### Requirement: Setup shows operation-specific Steam progress
Setup SHALL show Steam sync progress and available outcomes within its existing library stage, using that stage's current operation identity. Inline achievement work SHALL appear as a named phase of the library stage when it runs.

#### Scenario: Setup is reopened
- **WHEN** the library operation is still queued, waiting, retrying, or running
- **THEN** setup displays its actual current state without starting a second operation

#### Scenario: No inline achievement work is eligible
- **WHEN** the setup library poll has no eligible inline achievement work
- **THEN** setup continues with its existing stages without starting a new reconciliation pass

#### Scenario: Earlier stage is pending
- **WHEN** an independent later stage becomes available
- **THEN** it can proceed under the existing setup recovery rules and displays its own progress

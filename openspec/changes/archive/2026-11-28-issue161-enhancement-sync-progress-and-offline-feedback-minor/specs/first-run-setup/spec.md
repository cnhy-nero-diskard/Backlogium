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

### Requirement: Setup preserves a committed partial library result
When the library poll commits but inline achievement work is incomplete, Setup SHALL report the stage as partially complete rather than fully succeeded or failed. The partial outcome SHALL survive process recovery and remain explicitly retryable.

#### Scenario: Library commit has incomplete achievement work
- **WHEN** the library snapshot commits and its operation reports partial achievement results
- **THEN** Setup records and presents a partial outcome with available operation counts

#### Scenario: Partial result is recovered
- **WHEN** Setup reattaches to a completed library operation after process recreation
- **THEN** it restores the partial outcome and offers an explicit retry without relabelling the committed library as failed

## MODIFIED Requirements

### Requirement: Sync failure surfacing
The system SHALL surface sync failures while retaining the last good data. It SHALL distinguish an explicitly confirmed empty owned library from a privacy error or an unconfirmed empty response.

#### Scenario: Private profile or empty response
- **WHEN** a poll returns an unconfirmed empty response or an authorization/privacy error
- **THEN** the app retains the last synced data and exposes a recoverable error state indicating the profile may be private

#### Scenario: Confirmed empty response
- **WHEN** an explicitly confirmed zero-game response is accepted and committed
- **THEN** the poll reports a successful empty baseline

## ADDED Requirements

### Requirement: Library polls expose attributable operation results
Each library poll SHALL expose whether it committed, did not perform the operation, or failed with a recoverable reason. The result SHALL belong to the exact operation and admitted account. Scheduler success alone SHALL NOT establish a library commit.

#### Scenario: No library commit
- **WHEN** a poll is refused at account admission, has no credentials, or receives an unreadable library response
- **THEN** its result explains the unsuccessful operation without borrowing another poll's result

#### Scenario: Worker stops after committing
- **WHEN** an accepted library transaction commits before the worker is interrupted while publishing its result
- **THEN** recovery can attribute that commit to the exact operation and account from durable evidence

#### Scenario: Parallel library polls
- **WHEN** overlapping manual and periodic polls observe the same increase
- **THEN** each result describes its own operation and the increase is credited once

### Requirement: Confirmed library baseline readiness is durable and account-scoped
The system SHALL record evidence of a confirmed owned-library baseline for the admitted account in the transaction that accepts the response, including a confirmed zero-game response. Account reset SHALL invalidate the prior account's evidence. A failed refresh SHALL preserve the last valid confirmation.

#### Scenario: Library transaction rolls back
- **WHEN** an initial library transaction is interrupted before commit
- **THEN** it leaves no independent confirmation record

#### Scenario: Legacy or restored library lacks provenance
- **WHEN** local rows or a sync timestamp exist without trusted confirmation for the active account
- **THEN** readiness remains unconfirmed until a valid owned-library poll commits

#### Scenario: Later refresh fails
- **WHEN** a refresh fails after the active account has a confirmed baseline
- **THEN** the prior confirmation remains usable

#### Scenario: Account replacement
- **WHEN** account replacement is pending or has completed
- **THEN** the previous account's confirmation cannot grant readiness to the replacement account

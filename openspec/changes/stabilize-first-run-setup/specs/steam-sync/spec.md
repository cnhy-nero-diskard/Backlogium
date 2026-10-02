## MODIFIED Requirements

### Requirement: Sync failure surfacing
The system SHALL detect and surface sync failures without discarding the last good data. It SHALL distinguish an unreadable or unconfirmed empty owned-library response from an explicitly confirmed empty owned library. A job completing without an accepted and committed library response SHALL NOT be reported as a successful library import to setup or other consumers.

#### Scenario: Private profile or empty response
- **WHEN** a poll returns an unconfirmed empty response or an authorization/privacy error
- **THEN** the app retains the last synced data and exposes a recoverable error state indicating the profile may be private, without claiming library-sync success

#### Scenario: Confirmed empty library
- **WHEN** Steam explicitly confirms an owned-game count of zero and that response is accepted and committed
- **THEN** the poll is reported as a successful empty-library baseline rather than a privacy failure

## ADDED Requirements

### Requirement: Library polls expose attributable operation results
A library poll SHALL expose whether its requested domain operation committed, was not performed with an attributable reason, or needs recovery after failure. Results SHALL be associated with the admitted operation and account rather than inferred from an unrelated poll's latest error or scheduler completion alone. Transient retry scheduling SHALL remain distinguishable from terminal failure. Existing periodic, manual, presence, and post-play scheduling and exactly-once ledger behavior SHALL remain unchanged.

#### Scenario: Worker exits without credentials
- **WHEN** a requested library poll exits because credentials are unavailable
- **THEN** consumers receive a not-performed reason rather than a successful library-sync result

#### Scenario: Account admission refuses a poll
- **WHEN** a poll cannot run against the active account or its persistence is refused at the account boundary
- **THEN** that operation cannot report a committed library or update another account's setup attempt

#### Scenario: Two polls overlap
- **WHEN** a setup-requested manual poll and a periodic poll overlap
- **THEN** each attributable result describes its own operation, overlap alone is not failure, and the same playtime increase is credited only once

### Requirement: Confirmed library baseline readiness is durable and account-scoped
The system SHALL expose durable evidence that an accepted owned-library response has established a baseline for the active account. That evidence SHALL be committed with the accepted library data, SHALL cover confirmed empty libraries, and SHALL NOT be manufactured from credentials, nonempty local rows, or scheduler success alone. A later failed refresh SHALL retain an existing same-account baseline. Account reset SHALL invalidate the previous account's readiness. Restored or legacy data without sufficient evidence SHALL remain unconfirmed until a valid poll establishes it.

#### Scenario: Initial library commit
- **WHEN** an accepted first library response is committed
- **THEN** its same-account baseline readiness becomes durable with that commit, without creating historical sessions

#### Scenario: Interrupted initial commit
- **WHEN** initial library persistence is interrupted before committing
- **THEN** baseline readiness is not recorded separately from the library data

#### Scenario: Confirmed zero-game baseline
- **WHEN** an accepted zero-game response is committed for the active account
- **THEN** readiness is true even though no game rows exist

#### Scenario: Private response before baseline
- **WHEN** the only initial poll is an unreadable or unconfirmed empty response
- **THEN** history-import readiness remains unconfirmed

#### Scenario: Later refresh fails
- **WHEN** a confirmed account's later refresh fails
- **THEN** its previously committed baseline remains usable and last-good data is retained

#### Scenario: Account or uncertain restored data
- **WHEN** the account changes or restored data lacks sufficient baseline provenance
- **THEN** readiness is not inferred from the previous account or the existence of local game rows

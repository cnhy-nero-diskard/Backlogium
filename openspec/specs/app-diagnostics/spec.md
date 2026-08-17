# app-diagnostics

## Purpose

Defines diagnostic recording for sync runs and presence determinations: what is recorded, how
credentials are kept out of records and logs, retention bounds, and availability in release
builds. The Settings-facing diagnostics view that presents these records is defined in
`app-settings`.

## Requirements

### Requirement: Credentials never reach a log sink
The system SHALL remove credential values from any request identifier before it is written to a log
sink or stored as a diagnostic record. Redaction SHALL be applied where request data is formatted,
so that no call site can emit an unredacted value by omission.

#### Scenario: Request identifier carrying credentials
- **WHEN** a request whose identifier includes the Steam API key or SteamID is recorded
- **THEN** those values are replaced with a redacted placeholder before the record is emitted or
  stored

#### Scenario: Endpoint remains identifiable
- **WHEN** a request is recorded with credentials redacted
- **THEN** the endpoint and any non-credential parameters remain legible, so the request is still
  identifiable

#### Scenario: Redaction is not caller-controlled
- **WHEN** any component records request data
- **THEN** redaction has already been applied by the formatting layer and cannot be bypassed by that
  component

#### Scenario: Stored records contain no credentials
- **WHEN** stored diagnostic records are read back or displayed
- **THEN** no credential value appears in them

### Requirement: Per-request timing
The system SHALL record, for each outbound Steam request, the endpoint, the outcome, and the elapsed
duration, so the cost of a sync can be attributed to specific calls rather than estimated.

#### Scenario: Successful request
- **WHEN** a Steam request completes successfully
- **THEN** its endpoint, status, and elapsed duration are recorded

#### Scenario: Failed request
- **WHEN** a Steam request fails or times out
- **THEN** the failure and the elapsed duration before it are recorded

#### Scenario: Attributable to a run
- **WHEN** requests are issued during a sync run
- **THEN** they are attributable to that run, so the run's total request count and time spent in
  requests can be determined

### Requirement: Persisted sync run records
The system SHALL persist one record per sync run describing what that run did: what triggered it,
when it started, how long it took, how many requests it issued, how much work it performed, and how
it ended. Records SHALL survive app restart and SHALL be readable without a network connection.

#### Scenario: Successful run recorded
- **WHEN** a sync run completes successfully
- **THEN** a record is stored with its trigger, start time, duration, request count, work performed,
  and a successful outcome

#### Scenario: Failed run recorded
- **WHEN** a sync run fails
- **THEN** a record is stored with the same fields and an outcome identifying the failure

#### Scenario: Run interrupted
- **WHEN** a sync run is stopped by the system before completing
- **THEN** a record is stored identifying it as incomplete rather than being absent or appearing
  successful

#### Scenario: Records survive restart
- **WHEN** the app is restarted
- **THEN** previously recorded runs remain readable

#### Scenario: Early-returning run still recorded
- **WHEN** a run ends early — for example because credentials are absent or the owned-games list is
  empty
- **THEN** a record is stored identifying that reason, so a run that did nothing is distinguishable
  from a run that never happened

### Requirement: Bounded diagnostic retention
Diagnostic records SHALL be bounded in number, with the oldest pruned as new ones are recorded, so
storage does not grow without limit.

#### Scenario: Retention limit reached
- **WHEN** a new record is stored and the retention limit is already reached
- **THEN** the oldest record is removed

#### Scenario: Pruning does not fail the run
- **WHEN** pruning fails for any reason
- **THEN** the sync run it accompanied is unaffected

### Requirement: Presence decision records
The system SHALL record the outcome of each presence determination, including which condition
produced it, so an absent now-playing indicator can be attributed to a specific cause rather than
inferred.

#### Scenario: In-game determination
- **WHEN** presence resolves to in-game
- **THEN** a record identifies the determination and the game

#### Scenario: Not-in-game determination
- **WHEN** presence resolves to not in-game
- **THEN** a record identifies which condition produced that outcome — no credentials, no player
  returned, or a player with no running game — since these are indistinguishable in the resulting
  state

#### Scenario: Failed determination
- **WHEN** a presence determination fails with a network or API error
- **THEN** a record identifies the failure and that prior state was retained

#### Scenario: Trigger identified
- **WHEN** a presence determination is recorded
- **THEN** what triggered it is identified, so a missing determination is distinguishable from one
  that ran and found nothing

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


### Requirement: Diagnostics available in release builds
Persisted diagnostic records SHALL be recorded and readable in release builds, not only in debug
builds, since the behaviour they describe occurs on installed builds on untethered devices.
Freeform narrative logging to the platform log MAY be restricted to debug builds.

#### Scenario: Release build records diagnostics
- **WHEN** a sync run or presence determination occurs on a release build
- **THEN** its record is stored and readable in the app

#### Scenario: Narrative logging restricted
- **WHEN** the app runs as a release build
- **THEN** freeform narrative logging to the platform log is disabled, while persisted records are
  unaffected

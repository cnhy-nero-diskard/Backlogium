# live-status

## ADDED Requirements

### Requirement: A refused monitoring start never fails a sync
Starting live monitoring SHALL be best-effort with respect to the Steam poll that requests it.
If the platform refuses or fails the start, the poll SHALL continue and report its own outcome,
and the refusal SHALL be recorded rather than discarded.

#### Scenario: Platform refuses the start during a background poll
- **WHEN** a scheduled poll requests monitoring while the app is backgrounded and the platform
  refuses to start it
- **THEN** the poll continues, fetches its data, and reports success if that data was retrieved

#### Scenario: Refusal is recorded
- **WHEN** a monitoring start is refused or fails
- **THEN** a record identifying the condition that caused it is stored, so the failure is
  observable afterwards

#### Scenario: Successful start is unaffected
- **WHEN** the platform permits the start
- **THEN** monitoring begins as before and the poll proceeds

### Requirement: Monitoring start uses a mechanism the platform permits
The system SHALL request live monitoring only through a mechanism permitted from the context
making the request, rather than issuing a request the platform is expected to reject.

#### Scenario: Request from a background scheduled poll
- **WHEN** monitoring is requested from a scheduled background execution
- **THEN** the request is made through a mechanism valid from that context, or is not made at
  all and is recorded as not attempted

#### Scenario: Request from the foreground
- **WHEN** monitoring is requested while the app is in the foreground
- **THEN** monitoring begins

### Requirement: Behaviour after a platform runtime budget is reached is stated and honest
When the platform ends monitoring because a cumulative runtime budget was reached, the system
SHALL stop cleanly, record that the budget was reached, and behave as the specification states
for resumption. The system SHALL NOT document or rely on a resumption path the platform will
refuse.

#### Scenario: Budget reached
- **WHEN** the platform signals that the monitoring runtime budget is exhausted
- **THEN** monitoring stops cleanly and the reason is recorded

#### Scenario: Resumption is described accurately
- **WHEN** the conditions for monitoring to resume are documented
- **THEN** they describe what the platform permits, including the case where monitoring cannot
  resume until the user brings the app to the foreground

#### Scenario: User is informed when unattended monitoring is unavailable
- **WHEN** monitoring has stopped and cannot resume without the user opening the app
- **THEN** the live-status surface distinguishes that state from active monitoring, rather than
  appearing to monitor

#### Scenario: Tracked playtime is unaffected
- **WHEN** monitoring is unavailable for any period
- **THEN** playtime continues to be tracked by the periodic poll, and only the finer-grained
  live resolution is lost

# app-diagnostics

## MODIFIED Requirements

### Requirement: Persisted sync run records
The system SHALL persist one record per sync run describing what that run did: what triggered it,
when it started, how long it took, how many requests it issued, how much work it performed, and how
it ended. Records SHALL survive app restart and SHALL be readable without a network connection.

A run triggered by the end of an observed play session SHALL be distinguishable by its trigger from
a periodic or manual run, and SHALL identify the game it was scoped to.

A periodic run and a manual, player-initiated run SHALL additionally be distinguishable from
**each other**. The initiating trigger SHALL be determined by whatever enqueued the work and
SHALL travel with it, rather than being inferred inside the run from state that cannot tell
the two apart.

Retry state SHALL be recorded as an attribute of a run alongside its trigger, and SHALL NOT
replace it. A retried run SHALL still identify what originally initiated it, because a record
that says only "retry" cannot be attributed to a player action or to the schedule — which is
the distinction these records exist to preserve.

#### Scenario: Successful run recorded
- **WHEN** a sync run completes successfully
- **THEN** a record is stored with its trigger, start time, duration, request count, work performed,
  and a successful outcome

#### Scenario: Manual run is distinguishable from a periodic one
- **WHEN** the player activates a manual sync action
- **THEN** the stored record's trigger identifies it as player-initiated, and not as periodic

#### Scenario: Periodic run is recorded as periodic
- **WHEN** the periodic schedule starts a sync
- **THEN** the stored record's trigger identifies it as periodic

#### Scenario: Retry preserves the originating trigger
- **WHEN** a run is retried after a failure
- **THEN** the stored record identifies both that it was a retry and what originally initiated
  the run, whether that was the schedule or the player

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

#### Scenario: Post-play attempt recorded
- **WHEN** an attempt of a play-triggered targeted fetch completes, whether or not it observed an
  increase
- **THEN** a record is stored whose trigger identifies it as play-triggered and which names the game
  it was scoped to

#### Scenario: An exhausted schedule is legible
- **WHEN** a play-triggered schedule ends without observing an increase
- **THEN** its attempts are individually recorded, so the absence of a recorded session is
  attributable rather than silent

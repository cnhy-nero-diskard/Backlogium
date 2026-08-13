## MODIFIED Requirements

### Requirement: Only earned progress produces events
Progress events SHALL be produced only for writes declaring earned provenance. A write declaring
any non-earned provenance SHALL produce no events and SHALL instead set the delivery baseline to
the values it wrote, including where those values are lower than the baseline it replaces. This
guarantee SHALL hold even if the process terminates after the derived values are written but
before the delivery baseline is updated to match.

#### Scenario: Play produces an event
- **WHEN** a sync raises the player's level from 4 to 5
- **THEN** a level-up event is produced

#### Scenario: A configuration change produces no event
- **WHEN** the player raises the XP rate and the resulting recompute raises the level from 4 to 9
- **THEN** no level-up event is produced

#### Scenario: An import produces no event and does not defer one
- **WHEN** a playtime backfill raises the level from 4 to 24
- **THEN** no level-up event is produced, and no level-up event covering that rise is produced by
  any later sync

#### Scenario: A non-earned drop lowers the baseline
- **WHEN** clearing a backfill lowers the level from 24 to 4
- **THEN** no event is produced, and a subsequent sync that raises the level to 5 produces a
  level-up event from 4 to 5

#### Scenario: A restore resets the baseline to the restored values
- **WHEN** a restore replaces the player's derived values with those from a snapshot
- **THEN** no events are produced, and the baseline matches the restored values so that the next
  earned change is measured against them

#### Scenario: A crash after a non-earned write still produces no event
- **WHEN** a rule change, backfill, or restore writes its derived values, the process terminates
  before the delivery baseline is updated to match, and the app is later reopened
- **THEN** no event is produced for that write, and the delivery baseline is brought in line with
  the values that were actually written before any pending event is evaluated

### Requirement: An event is delivered once
Each progress event SHALL be delivered to a consumer at most once. Delivery SHALL survive process
death: an event acknowledged before the app was closed SHALL NOT be presented again on relaunch,
and an event produced while the app was closed SHALL remain available until presented. This
guarantee SHALL hold for every event kind, including one whose detection depends on comparing the
values immediately before and after the transition rather than comparing a monotonic value to its
baseline — the system SHALL retain enough information from before such a transition to survive a
process death occurring after the transition's derived values are written but before its delivery
baseline is updated.

A quest-met event SHALL remain available for delivery for as many calendar days as it stays
unacknowledged, and its identity (the date the quest was met) SHALL NOT change or disappear simply
because the current date has since advanced. Where more than one calendar day's quest is
unacknowledged at once, each SHALL remain individually deliverable rather than one obscuring
another; the system MAY deliver them one at a time, oldest first.

#### Scenario: An acknowledged event does not reappear
- **WHEN** a streak-milestone event has been presented and acknowledged, and the player returns to
  the same screen
- **THEN** the event is not presented again

#### Scenario: An acknowledged event does not survive a restart
- **WHEN** an event has been presented and acknowledged and the app process is killed and relaunched
- **THEN** the event is not presented again

#### Scenario: An event produced while the app was closed is still delivered
- **WHEN** a background sync produces a level-up event and the player opens the app some hours later
- **THEN** the event is available to be presented

#### Scenario: An unacknowledged event survives a crash
- **WHEN** an event is produced and the process terminates before any consumer acknowledges it
- **THEN** the event is presented on the next launch rather than being lost

#### Scenario: A streak-broken event survives a crash immediately after the transition
- **WHEN** a sync's earned recompute drops the current streak to zero from a positive value, the
  process terminates before the streak-broken event's delivery baseline is recorded, and the app is
  later reopened
- **THEN** the streak-broken event, carrying the length that was lost, is presented rather than
  being permanently lost

#### Scenario: A quest earned yesterday is still delivered today
- **WHEN** a background sync marks yesterday's quest met and is not acknowledged before the
  calendar date advances
- **THEN** opening the app today still presents a quest-met event carrying yesterday's date

#### Scenario: Two unacknowledged quest days both remain deliverable
- **WHEN** quests on two different past days are both met and unacknowledged
- **THEN** neither is silently dropped; acknowledging the earlier one leaves the later one still
  available to be presented

## ADDED Requirements

### Requirement: Acknowledgement is not lost or resurrected by a concurrent recompute
Acknowledging an event and a recompute producing or reseeding the delivery baseline SHALL NOT be
able to race into a lost update. Whichever of the two completes second SHALL observe the effect of
the one that completed first, rather than overwriting it with a result computed from a baseline
snapshot taken before the first one applied.

#### Scenario: Acknowledgement survives a concurrent recompute
- **WHEN** a streak-broken event is acknowledged at the same time a recompute is writing an
  unrelated delivery-baseline update
- **THEN** the acknowledgement's effect is present afterward, and the streak-broken event is not
  presented again

#### Scenario: A recompute cannot resurrect an already-acknowledged event
- **WHEN** a streak-broken event has been acknowledged and a subsequent recompute observes the same
  underlying transition it was derived from
- **THEN** the recompute does not reintroduce that event as pending

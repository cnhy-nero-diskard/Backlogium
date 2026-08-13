## ADDED Requirements

### Requirement: The persist/recovery protocol is serialized within a process
Persisting derived values and resolving abandoned transition-recovery state SHALL be serialized
against each other within the process. The serialized section SHALL span the whole protocol —
resolving any prior pending transition, capturing the previous state, recording the pending
transition, writing the derived values, finalizing the delivery baseline, and clearing the pending
transition — not merely the individual writes it is composed of. A second persistence SHALL NOT
begin the protocol until the first has finalized.

Any operation that decides a recorded pending transition has been abandoned SHALL first take the
same serialization boundary. A pending transition observed inside that boundary therefore always
belongs to a persistence that is no longer running, so a persistence that is merely between its
recorded transition and its derived-value write SHALL NOT have that record resolved, cleared, or its
transition consumed on its behalf.

#### Scenario: Recovery cannot claim a live persistence's recovery record
- **WHEN** a persistence has recorded its pending transition but has not yet written its derived
  values, and a consumer begins resolving abandoned recovery state
- **THEN** the pending transition is left intact until the persistence finalizes it, and the
  transition it describes is delivered by that persistence rather than consumed by recovery

#### Scenario: Recovery cannot consume a transition mid-write
- **WHEN** a persistence has written its derived values but has not yet finalized its delivery
  baseline, and a consumer begins resolving abandoned recovery state
- **THEN** no event is produced from the partially applied state, and the persistence's own
  finalization determines what is delivered

#### Scenario: Two persistences with different provenance cannot corrupt each other
- **WHEN** a non-earned persistence and an earned persistence overlap in time
- **THEN** neither one's recovery record is overwritten or cleared by the other, each captures a
  previous state that the stored values actually held, and the resulting delivery baseline reflects
  both writes applied in order

### Requirement: An in-flight transition marks the stored state as non-derivable
While a pending transition is recorded, the stored derived values and the delivery baseline SHALL be
treated as describing different logical versions of state, and SHALL NOT be compared to reconstruct
events. Reconstruction SHALL resume once that transition has been finalized or resolved.

An event whose identity is recorded explicitly rather than reconstructed — a quest-met date or a
streak-broken length already earned — SHALL remain available while a transition is in flight, since
it does not depend on that comparison.

#### Scenario: An in-flight write produces no phantom event
- **WHEN** a non-earned persistence has written derived values that exceed the delivery baseline and
  has not yet finalized, and a consumer is already observing pending events
- **THEN** no event is reconstructed from that pair, and none appears after finalization either

#### Scenario: Derivation resumes after the transition resolves
- **WHEN** the pending transition is finalized or resolved
- **THEN** events reconstructed from the stored values and the delivery baseline are available again

## MODIFIED Requirements

### Requirement: Only earned progress produces events
Progress events SHALL be produced only for writes declaring earned provenance. A write declaring
any non-earned provenance SHALL produce no events and SHALL instead set the delivery baseline to
the values it wrote, including where those values are lower than the baseline it replaces. This
guarantee SHALL hold even if the process terminates after the derived values are written but
before the delivery baseline is updated to match.

Where an event's availability is recorded explicitly rather than reconstructed, a non-earned write
SHALL NOT create such a record from values it recomputed, and SHALL NOT cancel one that an earned
write already created. A non-earned write SHALL NOT move any acknowledgement baseline backwards:
resetting a baseline to a lower or absent value where that would make already-acknowledged history
deliverable again is prohibited.

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

#### Scenario: A rule change that re-evaluates past days as met produces no quest events
- **WHEN** a rule change lowers the daily goal so that several past days that were not met are
  recomputed as met
- **THEN** no quest-met event is produced for any of those days, now or later

#### Scenario: An acknowledged quest is not revived by a later non-earned recompute
- **WHEN** a quest-met event has been presented and acknowledged, and a later rule change, backfill,
  or restore recomputes on a day whose own quest is not met
- **THEN** the acknowledged quest does not become deliverable again

#### Scenario: A non-earned recompute does not cancel an owed quest delivery
- **WHEN** a quest earned by a sync is still unacknowledged and a rule change is applied
- **THEN** that quest remains available to be presented

### Requirement: An event is delivered once
Each progress event SHALL be delivered to a consumer at most once. Delivery SHALL survive process
death: an event acknowledged before the app was closed SHALL NOT be presented again on relaunch,
and an event produced while the app was closed SHALL remain available until presented. This
guarantee SHALL hold for every event kind, including one whose detection depends on comparing the
values immediately before and after the transition rather than comparing a monotonic value to its
baseline — the system SHALL retain enough information from before such a transition to survive a
process death occurring after the transition's derived values are written but before its delivery
baseline is updated.

A quest-met event SHALL be available only where an earned recompute recorded that a quest was earned
on that date and no consumer has acknowledged it. Earnedness SHALL NOT be inferred from the stored
per-day quest outcome, which states only whether that day satisfies the current rule and therefore
cannot distinguish a quest the player earned while the feature was watching from one a recompute
produced, from one that predates the feature, or from one already celebrated. The record SHALL be
durable, SHALL be keyed by the date the quest was met, and SHALL NOT change or disappear because the
current date has since advanced.

Where more than one calendar day's quest is unacknowledged at once, each SHALL remain individually
deliverable rather than one obscuring another; the system MAY deliver them one at a time, oldest
first. Acknowledging one such date SHALL affect only that date, SHALL be idempotent, and SHALL NOT
acknowledge, remove, or reorder any other pending date, whichever order they are acknowledged in.

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

#### Scenario: A quest is earned only by the recompute that first meets it
- **WHEN** a sync meets today's quest and a later sync on the same day recomputes it as still met
- **THEN** one quest-met event is available for that date, not two

#### Scenario: Two unacknowledged quest days both remain deliverable
- **WHEN** quests on two different past days are both met and unacknowledged
- **THEN** neither is silently dropped; acknowledging the earlier one leaves the later one still
  available to be presented

#### Scenario: Acknowledging one quest date leaves the others untouched
- **WHEN** two quest dates are pending and the more recent one is acknowledged first
- **THEN** the older one is still available to be presented, and acknowledging the more recent one
  again changes nothing

### Requirement: A player's first recorded progress is not celebrated
Where no derived values have previously been persisted, the system SHALL establish the delivery
baseline from the computed values and produce no events, regardless of provenance. Establishing that
baseline SHALL NOT create pending event records from history that accumulated before the baseline
existed — in particular, days whose quests were met before progress-event delivery was ever
initialized SHALL NOT become deliverable quest-met events.

#### Scenario: First sync on a fresh install
- **WHEN** a newly configured install syncs a mature library and lands at a level well above zero
- **THEN** no level-up event is produced, and the baseline is set to the computed level

#### Scenario: First initialization on an account with met quest history
- **WHEN** delivery baselines are established for the first time on an account whose stored days
  already include several met quests
- **THEN** no quest-met event is produced for any of those days

#### Scenario: Earned progress after the first sync still produces events
- **WHEN** a second sync raises the level above the level established by the first
- **THEN** a level-up event is produced from the established baseline

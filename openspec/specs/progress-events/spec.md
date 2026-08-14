# progress-events

## Purpose

Defines the durable progress-event pipeline: how recompute provenance is declared at the point
derived values are persisted, which transitions produce player-facing events, how threshold
crossings collapse, and how each event is delivered at most once with a stable presentation
priority. The Home-facing presentation of the streak-broken acknowledgement is defined in
`app-ui`.

## Requirements

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

### Requirement: Recompute provenance is declared where derived values are written
Every write of derived gamification values SHALL declare the provenance of the recompute that
produced them. Provenance SHALL be supplied at the point the values are persisted rather than at
any wrapper above it, and SHALL have no default, so a caller that persists derived values without
stating what caused them cannot be expressed.

#### Scenario: A sync declares earned provenance
- **WHEN** a scheduled or manual sync recomputes and persists derived values
- **THEN** the write declares a provenance meaning the changes were earned through play

#### Scenario: A rule change declares non-earned provenance
- **WHEN** a confirmed rule change recomputes and persists derived values
- **THEN** the write declares a provenance meaning the changes resulted from configuration, not play

#### Scenario: A backfill declares non-earned provenance
- **WHEN** a playtime backfill is applied or cleared and derived values are persisted
- **THEN** the write declares a provenance meaning the changes resulted from an import, not play

#### Scenario: A restore declares non-earned provenance
- **WHEN** a backup restore persists derived values directly, without going through a recompute
- **THEN** the write still declares provenance, because provenance is required by the persisting
  operation itself

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

### Requirement: Speculative computation produces no observable effect
Computing derived values without persisting them SHALL produce no progress events and SHALL not
alter the delivery baseline.

#### Scenario: Previewing a rule change
- **WHEN** the settings confirmation dialog computes the effect of a candidate configuration in
  order to state its before/after
- **THEN** no progress event is produced and no baseline is advanced

#### Scenario: Abandoning a previewed rule change
- **WHEN** the player opens the rule-change confirmation dialog and cancels it
- **THEN** the player's subsequent earned progress produces the same events it would have produced
  had the dialog never been opened

### Requirement: Progress event vocabulary
The system SHALL represent player-facing progress transitions as a closed set of events, each
carrying enough detail to be presented without re-reading state: a level-up carrying the level
departed from and the level reached; a quest-met event carrying the date; a streak-milestone event
carrying the streak length reached; and a streak-broken event carrying the length of the streak
that ended.

#### Scenario: Level-up carries both ends of the transition
- **WHEN** a level-up event is produced
- **THEN** it carries both the level the player left and the level the player reached

#### Scenario: Streak milestone honours the configured interval
- **WHEN** an earned recompute raises the current streak to a positive multiple of the milestone
  interval that has not previously been delivered
- **THEN** a streak-milestone event carrying that streak length is produced

#### Scenario: Streak break carries the lost length
- **WHEN** an earned recompute lowers the current streak to zero from a positive value
- **THEN** a streak-broken event carrying the previous length is produced

#### Scenario: A streak that does not reach a milestone produces no milestone event
- **WHEN** an earned recompute raises the current streak to a value that is not a multiple of the
  milestone interval
- **THEN** no streak-milestone event is produced

### Requirement: Threshold crossings collapse into a single event
Where an earned recompute crosses several thresholds of the same kind at once, the system SHALL
produce one event describing the whole transition rather than one event per threshold.

#### Scenario: Multiple levels gained in one sync
- **WHEN** a single sync raises the player's level from 4 to 7
- **THEN** exactly one level-up event is produced, carrying 4 and 7

#### Scenario: Multiple streak milestones passed at once
- **WHEN** a single earned recompute raises the current streak from 6 to 15, passing both the 7-day
  and 14-day milestones
- **THEN** exactly one streak-milestone event is produced, carrying the highest milestone reached

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

### Requirement: Simultaneous events carry a presentation priority
Where one earned recompute produces several events, the system SHALL make all of them available
and SHALL define a stable priority order — level-up, then streak milestone, then quest met, then
streak broken — so that a surface able to present only one presents the most significant.

#### Scenario: A sync produces several events
- **WHEN** one sync levels the player up, satisfies today's quest, and reaches a streak milestone
- **THEN** all three events are available, ordered level-up first

#### Scenario: A surface that presents one event
- **WHEN** a surface can present only a single event and several are pending
- **THEN** it presents the highest-priority pending event, and the others remain unacknowledged

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


# progress-events

## Purpose

Defines the durable progress-event pipeline: how recompute provenance is declared at the point
derived values are persisted, which transitions produce player-facing events, how threshold
crossings collapse, and how each event is delivered at most once with a stable presentation
priority. The Home-facing presentation of the streak-broken acknowledgement is defined in
`app-ui`.

## Requirements

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
the values it wrote, including where those values are lower than the baseline it replaces.

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
and an event produced while the app was closed SHALL remain available until presented.

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

### Requirement: A player's first recorded progress is not celebrated
Where no derived values have previously been persisted, the system SHALL establish the delivery
baseline from the computed values and produce no events, regardless of provenance.

#### Scenario: First sync on a fresh install
- **WHEN** a newly configured install syncs a mature library and lands at a level well above zero
- **THEN** no level-up event is produced, and the baseline is set to the computed level

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


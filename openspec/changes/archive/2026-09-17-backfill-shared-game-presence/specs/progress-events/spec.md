## ADDED Requirements

### Requirement: Play observed retroactively declares its own provenance

Where derived values are recomputed because play that already happened has been observed after the
fact, the recompute SHALL declare a provenance distinct from both earned-through-play and
administrative bookkeeping. That provenance SHALL produce no progress events and SHALL set the
delivery baseline to the values it wrote, in either direction.

This play was genuinely earned — it is not a rule change, a restore, or a settings action. It is
recorded under a non-earned provenance anyway, deliberately, because the event vocabulary exists to
mark progress as it is made. A level-up announced on Monday for a Saturday already lived is not the
moment the vocabulary was built for, and a stretch of recovered play would otherwise deliver a
cascade of them at once.

The provenance SHALL be distinct from the administrative ones rather than reusing them, so a reader
tracing a baseline reseed can tell recovered play from a removal, a hide, or a restore.

#### Scenario: Recovered play produces no event

- **WHEN** play observed after the fact raises the player's level
- **THEN** no level-up event is produced, and no later sync produces one covering that rise

#### Scenario: The baseline follows the values written

- **WHEN** a recompute for retroactively observed play completes
- **THEN** the delivery baseline matches the values it wrote, so the next earned change is measured
  against them

#### Scenario: A past day becoming met produces no quest event

- **WHEN** recovered play raises a past date's qualifying playtime above the daily threshold
- **THEN** that day's quest status is corrected and no quest-met event is produced for it

#### Scenario: A streak corrected without celebration

- **WHEN** recovered play fills a day that had broken a streak, and the streak is recomputed as
  unbroken
- **THEN** the corrected streak is reflected and no streak event is produced for it

#### Scenario: Distinct from administrative provenance

- **WHEN** a baseline reseed is traced to its cause
- **THEN** recovered play is distinguishable from a removal, a visibility change, a rule change and
  a restore

#### Scenario: An owed delivery is not cancelled

- **WHEN** an event earned by a sync is still unacknowledged and recovered play is recomputed
- **THEN** that event remains available to be presented

#### Scenario: Later earned play still produces events

- **WHEN** a sync raises the level again after recovered play reseeded the baseline
- **THEN** a level-up event is produced, measured from the reseeded baseline

## MODIFIED Requirements

### Requirement: Recompute provenance is declared where derived values are written
Every write of derived gamification values SHALL declare the provenance of the recompute that
produced them. Provenance SHALL be supplied at the point the values are persisted rather than at
any wrapper above it, and SHALL have no default, so a caller that persists derived values without
stating what caused them cannot be expressed.

#### Scenario: A sync declares earned provenance
- **WHEN** a scheduled or manual sync persists fresh earned progress
- **THEN** its earned transition declares play provenance
- **AND** recovered historical credit or placement correction declares corrective provenance even when triggered by that same sync

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

#### Scenario: Recovered play declares corrective provenance
- **WHEN** newly recovered multi-day play or a correction of existing placement is persisted
- **THEN** the transition declares corrective retroactive-play provenance and reseeds delivery baselines without generating earned events

## ADDED Requirements

### Requirement: Recovery corrections do not duplicate or consume earned events
Placement changes and newly recovered multi-day historical credit SHALL update progression under corrective provenance, preserving explicitly owed events and acknowledgement high-water marks. A new single-day credit on the current date SHALL use ordinary earned provenance when it is fresh quantity rather than replacement of already credited play. Past-day single-date recovery and pending date-unknown quantity SHALL use corrective provenance. A sync containing both correction and fresh earned inputs, including current play and achievement credit under existing policies, SHALL preserve those inputs' earned-event eligibility while applying correction silently. Recovery after interruption SHALL retain the same separation and produce no duplicate events.

#### Scenario: Late placement change
- **WHEN** already credited minutes move from estimates into validated timing
- **THEN** no new level, quest or streak celebration is generated from that correction

#### Scenario: Current single-day recovery
- **WHEN** 70 newly authorized minutes are credited to the current date without clock boundaries
- **THEN** that fresh credit can produce ordinary earned progress exactly once

#### Scenario: Mixed sync
- **WHEN** a sync both corrects old placement and credits fresh current play
- **THEN** correction produces no event and the fresh credit retains its normal event eligibility

#### Scenario: Owed event survives
- **WHEN** an earlier sync left an unacknowledged earned event and recovery recomputes progression
- **THEN** that earlier event remains deliverable without being duplicated

#### Scenario: New achievement in a corrective sync
- **WHEN** a sync corrects historical placement and also records an achievement whose credit would normally cross a level boundary
- **THEN** correction does not consume the achievement's ordinary earned-event eligibility

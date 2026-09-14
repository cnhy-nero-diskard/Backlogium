## MODIFIED Requirements

### Requirement: A cloud read writes nothing derived

The system SHALL NOT write a session, daily progress record, derived gamification value, or
progress event as a result of a cloud read, except through the presence session mechanism acting on
games whose sessions that mechanism already owns.

A read is an acquisition of recorded fact. Deriving from it is a separate decision with its own
provenance and its own author, and must be introduced deliberately rather than arriving as a side
effect of fetching. The one permitted path is not an exception to that rule but an application of
it: the cloud supplies observations to a mechanism that already exists, rather than becoming an
author of its own.

#### Scenario: A read of owned-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from playtime diffing
- **THEN** no session, daily progress record, derived value, or progress event is written for them

#### Scenario: A read of shared-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from observed presence
- **THEN** sessions may be written for them through that mechanism and its writer
- **AND** no other derived value is authored by the read itself

#### Scenario: The reconstruction remains inert

- **WHEN** intervals are reconstructed
- **THEN** the reconstruction itself writes nothing, and any write is performed by the ingest that
  consumes it

## ADDED Requirements

### Requirement: Cloud intervals are ingested only for presence-derived games

The system SHALL admit a cloud interval to session derivation only where the named game's stored
source is one whose sessions are derived from observed presence. An interval naming a game whose
sessions come from playtime diffing SHALL be discarded, exactly as an unrecognised game is.

Two independent detectors produce session boundaries that disagree and cannot be deduplicated.
This is the same partition the on-device observer already honours, enforced the same way — by what
the ingest is handed, not by what it chooses.

#### Scenario: A family-shared game

- **WHEN** a cloud interval names a game whose stored source is family-shared
- **THEN** it is admitted to derivation

#### Scenario: An owned game

- **WHEN** a cloud interval names a game whose stored source is Steam-owned
- **THEN** it is discarded, and no session is opened, extended or closed for it

#### Scenario: An unknown game

- **WHEN** a cloud interval names a game not present in the library
- **THEN** it is discarded rather than admitted speculatively

#### Scenario: Mixed intervals in one read

- **WHEN** a read returns intervals for both owned and family-shared games
- **THEN** only the family-shared ones reach derivation, and the presence of the others changes
  nothing about how they are handled

### Requirement: Ingested play is not counted twice

The system SHALL record how far cloud observations have been ingested and SHALL NOT derive a second
session from an interval already accounted for. Where the on-device observer has already recorded
play for a stretch the cloud also observed, the result SHALL be one session's worth of credited
time, not two.

#### Scenario: Re-running an ingest

- **WHEN** the same cloud window is ingested twice
- **THEN** the second ingest credits no additional minutes and creates no additional session

#### Scenario: Play observed by both the device and the cloud

- **WHEN** the on-device observer recorded a session and the cloud observed the same stretch
- **THEN** the credited time reflects that play once

#### Scenario: Ingest position survives the process

- **WHEN** the app is killed partway through an ingest
- **THEN** the next ingest resumes without re-crediting what was already written

#### Scenario: Position discarded on an account change

- **WHEN** the configured Steam account changes
- **THEN** the ingest position is discarded along with the read position

### Requirement: Unconfirmed time is not credited to a shared game

Where a cloud interval's coverage is partial or unknown, the system SHALL credit only the span
that was confirmed by observation, and SHALL NOT credit the unobserved remainder. Where an
interval carries the raw interior-gap pair phase 2 preserves (`prevCoverageLapseFrom` /
`prevCoverageLapseRecoveredAt`), the system SHALL treat the span between that pair as unconfirmed
and exclude it from derivation exactly as it excludes an unconfirmed tail, applying its own
tolerance to the raw pair rather than a verdict computed upstream.

A shared game has no Steam-reported total, so nothing external bounds an over-credit — an interval
mistakenly credited in full becomes that game's tracked time with no correction available from any
source. An owned game's equivalent error is caught by its lifetime total; this one is not.

#### Scenario: Interval observed continuously

- **WHEN** an interval is recorded as observed throughout
- **THEN** its full span is available for derivation

#### Scenario: Interval observed only until a stated time

- **WHEN** an interval was confirmed only until a time before the transition that closed it
- **THEN** derivation uses the confirmed portion and the remainder is not credited

#### Scenario: Interval with an interior gap excludes that span

- **WHEN** an interval carries an interior-gap pair alongside a fresh tail (for example a v3
  transition carrying `prevLastObservedAt=t10` with `prevCoverageLapseFrom=t1` /
  `prevCoverageLapseRecoveredAt=t10`)
- **THEN** derivation uses the confirmed portions and the `t1..t10` span is not credited
- **AND** the interval is not treated as continuous on the strength of its fresh tail

#### Scenario: Interval of unknown coverage

- **WHEN** an interval carries no coverage record
- **THEN** it is treated conservatively rather than as observed throughout

#### Scenario: Play entirely inside a gap is not invented

- **WHEN** a game was played entirely during a period the poller did not observe
- **THEN** no session is derived for it, and no span is inferred from surrounding intervals

### Requirement: A recovered session is an ordinary session

A session derived from cloud presence SHALL be indistinguishable downstream from one derived by the
on-device observer: the same stored shape, the same writer, and the same participation in XP,
quests, streaks, history and analytics.

#### Scenario: Downstream treatment

- **WHEN** a session recovered from cloud presence is examined by any consumer
- **THEN** it participates identically to a session derived on-device

#### Scenario: Written through the shared writer

- **WHEN** recovered sessions are persisted
- **THEN** they are written through the same writer other session mechanisms use, not a parallel
  path

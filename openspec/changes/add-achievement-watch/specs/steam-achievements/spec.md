## ADDED Requirements

### Requirement: The watch is a recognised refresh path
The achievement watch SHALL be treated as a refresh of a single game's per-player unlock state,
subject to the same serialization, storage, and rarity-snapshot rules as every other refresh path,
and SHALL NOT bypass them.

#### Scenario: Watch writes through the same path
- **WHEN** a watch observation stores achievement state
- **THEN** it is written through the same merge and persistence path a sync's refresh uses

#### Scenario: Watch respects per-game serialization
- **WHEN** a watch observation and another refresh target the same game
- **THEN** they are serialized against each other, as two refreshes of the same game already are

#### Scenario: Watch does not disturb the tiered schedule
- **WHEN** the watch refreshes a game's unlock state
- **THEN** the tiered refresh's own selection of games and the reconciliation pass's coverage are
  unaffected

#### Scenario: Retired achievements unaffected
- **WHEN** the watch observes a game whose stored achievements include retired rows
- **THEN** the watch does not retire or un-retire any row; retirement remains reconciliation's
  responsibility

### Requirement: A watch observation fetches globals only when it has an unlock to record
The watch SHALL request per-player unlock state on each observation, and SHALL request that game's
global unlock percentages only on an observation that finds an achievement newly unlocked. That
request SHALL complete before the newly unlocked state is written.

#### Scenario: No change, one request
- **WHEN** a watch observation finds no newly unlocked achievement
- **THEN** only the per-player unlock state was requested

#### Scenario: New unlock, globals requested first
- **WHEN** a watch observation finds a newly unlocked achievement
- **THEN** the game's global unlock percentages are requested and received before the unlock is
  written

#### Scenario: Globals request fails
- **WHEN** the global unlock percentages cannot be obtained
- **THEN** the observation is discarded without writing, so no unlocked row is stored without a
  rarity snapshot

#### Scenario: Schema is not refetched per observation
- **WHEN** the watch observes a game whose stored achievement schema is within its long freshness
  window
- **THEN** the schema is served from storage and no schema request is made

## MODIFIED Requirements

### Requirement: Persist achievements with a first-unlock rarity snapshot
The system SHALL persist each achievement keyed by its game and achievement id, storing its
unlock state, unlock time when available, the current global unlock percentage, and a rarity
percentage snapshotted at the first sync that observes the achievement as unlocked with a
known global percentage. The snapshot SHALL NOT change on later syncs.

No path SHALL store an achievement as newly unlocked without a known global unlock percentage in
hand, since the snapshot is taken only at first observation and cannot be repaired afterwards. A path
that cannot obtain the percentage SHALL defer the write rather than complete it.

#### Scenario: Snapshot taken at first observed unlock
- **WHEN** a sync first observes an achievement as unlocked and a global unlock percentage is available
- **THEN** the system stores that percentage as the achievement's rarity snapshot

#### Scenario: Snapshot is stable against later drift
- **WHEN** a later sync reports a different global unlock percentage for an already-snapshotted achievement
- **THEN** the stored rarity snapshot is unchanged, while the current global percentage is updated for display

#### Scenario: Still-locked achievement has no snapshot
- **WHEN** an achievement has never been observed unlocked
- **THEN** it has no rarity snapshot

#### Scenario: A first unlock is never written unsnapshotted
- **WHEN** any path observes an achievement as newly unlocked and cannot obtain a global unlock
  percentage for it
- **THEN** the unlocked state is not written, and the observation is left for a later path to record
  with a snapshot

#### Scenario: A deferred write is recoverable
- **WHEN** a write is deferred because no global percentage was available
- **THEN** a later refresh of that game stores the unlock with its snapshot, and no achievement is
  permanently left unsnapshotted as a result

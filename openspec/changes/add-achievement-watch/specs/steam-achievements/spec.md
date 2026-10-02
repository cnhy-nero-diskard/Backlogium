## ADDED Requirements

### Requirement: Achievement watch shares coordinated refresh and merge semantics
The watch SHALL use the same per-account/game request and write coordination as other achievement refreshes. It SHALL preserve non-null rarity snapshots, freshness/timestamp guards, metadata, and retirement rules; it SHALL NOT perform reconciliation retirement on a normal watch tick.

#### Scenario: Concurrent requests
- **WHEN** a watch tick and ordinary refresh target the same account/game
- **THEN** their requests/writes coordinate and stale data cannot overwrite newer committed data

#### Scenario: Retired row remains retired
- **WHEN** a normal watch observation sees incomplete game schema
- **THEN** it does not mass-retire or delete unrelated stored achievements

### Requirement: A watch commit requiring a snapshot defers incomplete rarity data
For an unlocked row lacking an existing non-null snapshot, a watch commit SHALL require a usable global percentage for that row. Missing/invalid required rarity data SHALL defer the whole observation without persistence, event insertion, or baseline advancement. Existing non-null snapshots SHALL remain immutable, and a previously null snapshot MAY be repaired when usable data arrives. This stronger gate SHALL apply to watch commits only.

#### Scenario: Usable zero percentage
- **WHEN** a newly observed unlocked row has a valid global percentage of zero
- **THEN** zero is stored as a valid snapshot

#### Scenario: Already snapshotted unlock
- **WHEN** the player response has no newly unsnapshotted unlock
- **THEN** globals are not required solely to replay a known snapshot

#### Scenario: Missing data later recovers
- **WHEN** a previously null snapshot obtains a usable percentage on a later watch read
- **THEN** it is filled without treating the repair alone as a new unlock alert

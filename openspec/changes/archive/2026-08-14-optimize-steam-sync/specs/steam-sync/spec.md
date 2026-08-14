## ADDED Requirements

### Requirement: Bounded inline sync work
A periodic or manual sync SHALL perform only a bounded amount of network work inline, proportional
to recent play activity rather than to library size, so that sync duration does not grow with the
number of games owned and cannot approach the platform's background execution limit.

#### Scenario: Sync duration independent of library size
- **WHEN** a sync runs for a player with a large library and little recent play activity
- **THEN** the number of requests it issues is proportional to recently played games, not to the
  library

#### Scenario: Library-scale work is deferred
- **WHEN** work covering the whole library is due
- **THEN** it is performed by a separate deferred pass rather than inline in the sync

#### Scenario: Manual sync stays responsive
- **WHEN** the player triggers "Sync now"
- **THEN** it completes without waiting for library-scale work

#### Scenario: A library with no stored derived data does not force a sweep
- **WHEN** a sync runs against a library for which no per-game achievement data has been stored yet,
  as on a first install or after a restore from backup
- **THEN** it still issues only a bounded number of requests, and the uncovered games are left to
  subsequent syncs and to the deferred pass rather than fetched in one inline sweep

### Requirement: Play deltas available to dependent work
The sync SHALL make the per-game playtime deltas it computes available to work that depends on
knowing which games were played, so that information is derived once per run rather than
rediscovered by refetching.

#### Scenario: Deltas passed to achievement refresh
- **WHEN** a sync computes which games' playtime increased
- **THEN** that set is used to select which games' achievements to refresh, without additional
  requests to determine it

#### Scenario: Baseline sync yields no deltas
- **WHEN** the sync is the first one and establishes a baseline
- **THEN** no playtime deltas are reported, and no achievement refresh is triggered by play evidence
  in that run

### Requirement: Prior session state read in bulk
The sync SHALL read the prior open-session state it needs for playtime diffing in a bounded number
of database queries rather than one query per owned game.

#### Scenario: Reconstructing diff state
- **WHEN** a sync reconstructs prior session state before writing new playtime
- **THEN** the open sessions are retrieved in bulk, and the number of queries does not grow with the
  size of the library

#### Scenario: Diff results unchanged
- **WHEN** prior session state is read in bulk instead of per game
- **THEN** the synthesized sessions are identical to those produced by the per-game reads

# backup-restore

## MODIFIED Requirements

### Requirement: Achievement rarity snapshot is protected during import
An achievement's frozen rarity snapshot (`snapshotPercent`) SHALL NOT be refreshed to a
current rarity value once set, because its value derives entirely from having been captured at
first unlock. When both the local database and the imported file hold a snapshot for the same
achievement, the snapshot associated with the earlier unlock timestamp SHALL be retained,
including when that is the imported one — the earlier unlock is by definition nearer the true
first unlock, and this rule makes the merge independent of import order.

#### Scenario: Imported snapshot has an earlier unlock
- **WHEN** an imported file includes a snapshot for an achievement whose imported unlock
  timestamp is earlier than the locally stored one
- **THEN** the imported snapshot and its unlock timestamp replace the local values

#### Scenario: Imported snapshot has a later unlock
- **WHEN** an imported file includes a snapshot for an achievement whose imported unlock
  timestamp is later than the locally stored one
- **THEN** the locally stored snapshot and its unlock timestamp are retained, and the imported
  value is discarded for that achievement

#### Scenario: No local snapshot exists yet
- **WHEN** an imported file includes a snapshot for an achievement with no locally stored
  snapshot
- **THEN** the imported snapshot and its unlock timestamp are stored

#### Scenario: Merge is order-independent
- **WHEN** two backups holding different snapshots for one achievement are imported in either
  order
- **THEN** the retained snapshot is the same in both cases

#### Scenario: Snapshot is never refreshed to a current value
- **WHEN** current global rarity for an already-snapshotted achievement is observed from any
  source
- **THEN** the stored snapshot is left unchanged, because refreshing it would discard the
  first-unlock observation it exists to preserve

## ADDED Requirements

### Requirement: Import is validated in full before any write
The system SHALL validate a parsed backup completely — structurally and semantically — before
performing any database write, and SHALL reject an invalid file with a message identifying what
failed. No part of an invalid file SHALL be applied.

#### Scenario: Semantically invalid record
- **WHEN** an imported file contains an unparseable date, an implausible timestamp, a session
  whose end precedes its start, an out-of-range rarity percentage, a member referencing an
  absent collection, or a duplicate natural key
- **THEN** the import is rejected before any write occurs, and the stored data is unchanged

#### Scenario: Rejection identifies the problem
- **WHEN** an import is rejected by validation
- **THEN** the message identifies the kind of problem and where it occurred, rather than
  reporting only that the import failed

#### Scenario: Valid file proceeds
- **WHEN** an imported file passes validation
- **THEN** the merge proceeds without re-deriving the same checks during writing

### Requirement: Import is all-or-nothing
Merging an imported backup SHALL apply as a single unit covering every affected data type and
the recomputation of derived aggregates. If any part fails or the process ends partway, the
stored data SHALL be exactly as it was before the import began.

#### Scenario: Failure partway through the merge
- **WHEN** a merge fails after writing some data types but not others
- **THEN** none of the merge's writes remain, and the stored data matches its pre-import state

#### Scenario: Process death during a merge
- **WHEN** the process ends while a merge is in progress
- **THEN** the next start observes the pre-import state rather than a mixture

#### Scenario: Aggregate recomputation is included
- **WHEN** a merge commits
- **THEN** the recomputation of derived aggregates over the merged data commits with it, so raw
  data and aggregates cannot describe different states

#### Scenario: Cancellation during a merge
- **WHEN** an in-progress import is cancelled
- **THEN** no partial result remains

### Requirement: Import enforces a size limit
The system SHALL reject a selected backup file exceeding a documented maximum size before
reading its full contents into memory, so an oversized or hostile file produces a clear refusal
rather than an out-of-memory failure.

#### Scenario: Oversized file selected
- **WHEN** the user selects a file larger than the documented maximum
- **THEN** the import is refused before the payload is materialized, and the message states both
  the limit and the file's size

#### Scenario: Reported size cannot be trusted
- **WHEN** a file's reported size is absent or understates its actual size
- **THEN** reading is still bounded, so the limit holds regardless of reported metadata

#### Scenario: Normal file unaffected
- **WHEN** the user selects a backup of ordinary size
- **THEN** the import proceeds as before

### Requirement: Export reflects a single point in time
A backup export SHALL read all exported data from one consistent view of the database, so that
a concurrent sync or user edit cannot produce a file combining data from before an operation
with data from after it.

#### Scenario: Sync concurrent with export
- **WHEN** a Steam sync commits while an export is being produced
- **THEN** the exported file reflects either the state before that sync or the state after it,
  and never a mixture

#### Scenario: Exported file is internally consistent
- **WHEN** an exported file is inspected
- **THEN** its games, sessions, daily progress, achievements, and aggregates all correspond to
  the same instant

#### Scenario: Export does not block on a running sync
- **WHEN** an export is requested while a sync is in progress
- **THEN** the export proceeds against a consistent view rather than waiting for the sync to
  finish

# backup-restore

## Purpose

Provides manual and automatic backup/restore of the player's app-derived data (sessions,
progress, XP/level/streak, achievements, HLTB) via a versioned JSON format, with merge
semantics that never double-count and always recompute aggregates. The exported rule
configuration travels in the file as export-only reproducibility metadata: it documents the
rules the file's rollup was produced under but is never applied to the receiving device on
import.

## Requirements

### Requirement: Backup export format
The system SHALL be able to serialize the player's app-derived data into a single versioned JSON
file containing: a `formatVersion`, the export timestamp, the player's SteamID64 (for
verification only), the active gamification rule configuration, a minimal game/achievement
identity skeleton (app ID, name/display name), session history, daily progress, HLTB match data,
library sort preferences, the player's XP/level/streak state, and an export-time computed
rollup (XP per game, XP over time). The Steam Web API key SHALL NOT be included in the file
under any circumstance. Timestamps in the file SHALL be ISO-8601 strings, not epoch values.

#### Scenario: Exporting produces a complete file
- **WHEN** an export is generated
- **THEN** the resulting file contains the player's session history, daily progress, XP/level/
  streak state, goal tags, backfill offsets, frozen achievement-rarity snapshots, HLTB data,
  library sort preferences, and the active rule configuration

#### Scenario: Credentials are never exported
- **WHEN** an export is generated
- **THEN** the file contains the player's SteamID64 but does not contain the Steam Web API key
  in any form

#### Scenario: Computed rollup reflects current rules
- **WHEN** an export is generated
- **THEN** the file's computed XP-per-game and XP-timeline values are produced by evaluating the
  raw data under the rule configuration included in the same file

### Requirement: Manual export via file picker
The system SHALL let the user export a backup file to a location of their choosing using the
platform's storage access mechanism, independent of whether automatic snapshots are enabled.

#### Scenario: User exports to a chosen location
- **WHEN** the user activates the "Export Backup" action and selects a destination
- **THEN** a backup file conforming to the export format is written to that location

#### Scenario: Export available regardless of auto-snapshot setting
- **WHEN** automatic snapshots are disabled
- **THEN** the manual export action remains available and unaffected

### Requirement: Manual import via file picker
The system SHALL let the user import a previously exported backup file selected via the
platform's storage access mechanism, merging its contents into the local database.

#### Scenario: User imports a selected file
- **WHEN** the user activates the "Import Backup" action and selects a valid backup file
- **THEN** the file's contents are merged into the local database following the merge semantics
  below, and the app's displayed XP, level, streaks, and history reflect the merged result

#### Scenario: Invalid or unrecognized file rejected
- **WHEN** the user selects a file that is not a valid backup of a supported `formatVersion`
- **THEN** the import is rejected and no data is modified

### Requirement: Import merge does not double-count or blindly overwrite
Merging an imported backup into existing local data SHALL use natural-key upsert per data type
(replacing a matching key's values and adding keys present only in the import), and SHALL NOT
sum, duplicate, or otherwise double-count any value. Aggregate values (total XP, level, current
streak) SHALL always be recomputed from the merged raw data after import; they SHALL NOT be
taken directly from the imported file. The recompute SHALL use the receiving installation's
active rule configuration, not the configuration recorded in the file.

#### Scenario: Overlapping session imported
- **WHEN** an imported session matches an existing session's game, start time, and end time
- **THEN** the existing session's stored values are replaced by the imported values, and no
  duplicate session is created

#### Scenario: Non-overlapping session imported
- **WHEN** an imported session does not match any existing session's natural key
- **THEN** the session is inserted as a new row

#### Scenario: Aggregates are recomputed, not imported
- **WHEN** an import completes
- **THEN** total XP, level, and current streak are derived by running the gamification engine
  over the merged raw data under the receiving device's active rule configuration, and any
  aggregate values in the imported file are ignored

#### Scenario: Longest streak never decreases
- **WHEN** an import completes and the recomputed longest streak differs from the stored value
- **THEN** the stored longest streak becomes the greater of the previous stored value and the
  recomputed value

### Requirement: Rule configuration is export-only metadata
The exported rule configuration SHALL be recorded in the backup file for legibility and for
reproducing the file's own computed rollup, and SHALL NOT be applied to the receiving
installation on import. Import SHALL merge raw data and then recompute aggregates under
whichever rule configuration the receiving device already has.

The same applies to the other reproducibility fields the file carries for the reader's
benefit rather than for restoration: library sort preferences and the export-time computed
rollup.

#### Scenario: Imported rules are not applied
- **WHEN** a backup file whose recorded rule configuration differs from the receiving
  device's configuration is imported
- **THEN** the device's own rule configuration is left unchanged, and the merged data is
  recomputed under it

#### Scenario: Recorded rules still explain the file's rollup
- **WHEN** a reader inspects a backup file's computed XP-per-game and XP-timeline values
- **THEN** the rule configuration recorded in the same file is the configuration those
  values were produced under, so the rollup remains reproducible from the file alone

#### Scenario: Restoring onto a differently configured device
- **WHEN** the player imports their own backup onto a device configured with different rules
- **THEN** the import succeeds, the raw history is restored, and the displayed XP, level, and
  streaks reflect the receiving device's rules rather than the exporting device's

### Requirement: Achievement rarity snapshot is protected during import
An achievement's frozen rarity snapshot (`snapshotPercent`) SHALL NOT be refreshed to a
current rarity value once set, because its value derives entirely from having been captured at
first unlock. When both the local database and the imported file hold a snapshot for the same
achievement, the snapshot associated with the earlier unlock timestamp SHALL be retained,
including when that is the imported one — the earlier unlock is by definition nearer the true
first unlock, and this rule makes the merge independent of import order. When the two unlock
timestamps are equal, the lower `snapshotPercent` SHALL be retained. This is a canonical
tie-break rather than a claim about which value was observed first — rarity is a ratio and can
fall as the player population grows, and the timestamps are equal by definition — but the rule
must be total and deterministic for order-independence to hold at all.

Merging SHALL preserve locally stored fields the backup format does not carry — including an
achievement's retired state — rather than resetting them to defaults.

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

#### Scenario: Equal unlock timestamps
- **WHEN** the local and imported snapshots for one achievement carry the same unlock timestamp
  but different rarity percentages
- **THEN** the lower percentage is retained, whichever side it came from

#### Scenario: Locally stored fields the backup cannot carry
- **WHEN** an imported snapshot replaces the local one for an achievement that is retired locally
- **THEN** the achievement remains retired, and other fields absent from the backup format are
  left as stored rather than reset to defaults

#### Scenario: Snapshot is never refreshed to a current value
- **WHEN** current global rarity for an already-snapshotted achievement is observed from any
  source
- **THEN** the stored snapshot is left unchanged, because refreshing it would discard the
  first-unlock observation it exists to preserve

### Requirement: Import is validated in full before any write
The system SHALL validate a parsed backup completely — structurally and semantically — before
performing any database write, and SHALL reject an invalid file with a message identifying what
failed. No part of an invalid file SHALL be applied.

#### Scenario: Semantically invalid record
- **WHEN** an imported file contains an unparseable date, an implausible timestamp, a session
  whose end precedes its start, an out-of-range rarity percentage, a member referencing an
  absent collection, or a duplicate natural key
- **THEN** the import is rejected before any write occurs, and the stored data is unchanged

#### Scenario: Parseable but implausible date
- **WHEN** an imported file contains a date that parses correctly but falls outside the supported
  range the app can have recorded
- **THEN** the import is rejected, because derived-value recomputation spans the calendar from the
  earliest stored day and such a date would make that work effectively unbounded on every
  subsequent attempt

#### Scenario: Rejection identifies the problem
- **WHEN** an import is rejected by validation
- **THEN** the message identifies the kind of problem and where it occurred, rather than
  reporting only that the import failed

#### Scenario: Valid file proceeds
- **WHEN** an imported file passes validation
- **THEN** the merge proceeds without re-deriving the same checks during writing

### Requirement: Import is all-or-nothing
Merging an imported backup SHALL apply as a single unit covering every affected raw data type.
If any part fails or the process ends partway, the stored data SHALL be exactly as it was
before the import began.

Derived aggregates are recomputed immediately after that unit commits, through the existing
recoverable protocol spanning the database and settings storage. They are outside the unit
because that protocol cannot execute inside a database transaction; an interruption between the
two steps SHALL be detected and resolved rather than left standing.

#### Scenario: Failure partway through the merge
- **WHEN** a merge fails after writing some data types but not others
- **THEN** none of the merge's writes remain, and the stored data matches its pre-import state

#### Scenario: Process death during a merge
- **WHEN** the process ends while a merge is in progress
- **THEN** the next start observes the pre-import state rather than a mixture

#### Scenario: Aggregates are recomputed after the merge commits
- **WHEN** a merge commits
- **THEN** derived aggregates are recomputed over the merged raw data immediately afterwards

#### Scenario: Interruption between merge and recomputation
- **WHEN** the process ends after the merge commits but before aggregates are recomputed
- **THEN** the merged raw data remains, the incomplete recomputation is detected on the next
  attempt, and aggregates are regenerated from the merged data rather than left describing the
  pre-import state

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

### Requirement: Cross-account import is allowed with a warning
When the SteamID64 recorded in an imported backup differs from the currently signed-in account's
SteamID64, the system SHALL present a clear warning identifying the mismatch and stating that the
imported data belongs to a different account and will be merged with the current account's data,
but SHALL NOT block the import. This differs deliberately from changing the configured account:
an import is a considered act on identified data, whereas an account change is a credentials edit
whose data consequences the user has no reason to anticipate.

#### Scenario: Mismatched account detected
- **WHEN** the user attempts to import a file whose recorded SteamID64 differs from the
  currently signed-in account
- **THEN** a warning identifying the mismatch is shown before the import proceeds, stating that
  the data belongs to a different account and will be merged with the current account's

#### Scenario: User proceeds past the warning
- **WHEN** the user confirms the import despite the mismatch warning
- **THEN** the import proceeds using the same merge semantics as a matching-account import

#### Scenario: Import does not change the configured account
- **WHEN** a cross-account import completes
- **THEN** the configured SteamID is unchanged, and no account-change consequence is triggered

#### Scenario: Relationship to changing the configured account
- **WHEN** the user instead changes the configured SteamID to the one recorded in a backup
- **THEN** that follows the account-change requirements rather than these import requirements,
  so the two paths are distinguishable rather than contradictory

### Requirement: Automatic rolling snapshots
The system SHALL be able to automatically write a backup snapshot to app-private storage after a
successful Steam sync, subject to a configurable minimum interval between snapshots, and SHALL
retain only a configurable maximum number of the most recent snapshots, discarding older ones
beyond that count.

#### Scenario: Snapshot written after sync when due
- **WHEN** a Steam sync completes successfully and the time since the most recent snapshot
  exceeds the configured interval
- **THEN** a new snapshot is written to app-private storage

#### Scenario: Snapshot skipped when not due
- **WHEN** a Steam sync completes successfully but the time since the most recent snapshot is
  less than the configured interval
- **THEN** no new snapshot is written

#### Scenario: Oldest snapshot discarded beyond retention count
- **WHEN** writing a new snapshot would exceed the configured retention count
- **THEN** the oldest existing snapshot is discarded so the total stored count does not exceed
  the configured retention count

#### Scenario: Auto-snapshot disabled
- **WHEN** automatic snapshots are turned off in settings
- **THEN** a successful Steam sync does not write a snapshot

### Requirement: Restoring from an automatic snapshot
The system SHALL let the user view the currently retained automatic snapshots with their
timestamps and restore any one of them, applying the same merge semantics as a manually
imported file.

#### Scenario: Snapshot list shown
- **WHEN** the Data & Backup settings section is shown
- **THEN** the currently retained snapshots are listed with their timestamps, most recent first

#### Scenario: Restoring a listed snapshot
- **WHEN** the user selects "Restore" on a listed snapshot
- **THEN** that snapshot's contents are merged into the local database following the same merge
  semantics as a manually imported file
### Requirement: Recency fields round-trip without manufacturing events
A backup SHALL carry each game's recorded arrival time, last-played time, and recorded return from
dormancy, and an import SHALL restore them as recorded. An import SHALL NOT write any of those values
for a game other than what the backup carried, so that restoring a library is never mistaken for
acquiring one or for playing one.

#### Scenario: Fields exported
- **WHEN** a backup is exported
- **THEN** each game's arrival time, last-played time, and recorded return are included, with an
  explicit absence where any is unknown

#### Scenario: Fields imported
- **WHEN** a backup carrying those times is imported
- **THEN** they are restored, unchanged, for the games they belong to

#### Scenario: Inserted games are not stamped as arrivals
- **WHEN** an import inserts games that are not present in the current library
- **THEN** no arrival time is written for them other than one carried by the backup

#### Scenario: Import records no returns
- **WHEN** an import inserts or updates games
- **THEN** no return from dormancy is written other than one carried by the backup

#### Scenario: Import produces no announcement
- **WHEN** an import inserts previously unknown games
- **THEN** no acquisition announcement is presented

#### Scenario: Older backup lacking the fields
- **WHEN** a backup written before these fields existed is imported
- **THEN** it imports successfully, the affected games have no arrival time and no recorded return,
  and their last-played times are filled in by the next sync

#### Scenario: Restored data is interpreted on its own timeline
- **WHEN** an import completes
- **THEN** any recency state a game carries follows from the times the backup recorded, so states
  recorded long ago have already expired and states recorded recently are still current

## ADDED Requirements

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
taken directly from the imported file.

#### Scenario: Overlapping session imported
- **WHEN** an imported session matches an existing session's game, start time, and end time
- **THEN** the existing session's stored values are replaced by the imported values, and no
  duplicate session is created

#### Scenario: Non-overlapping session imported
- **WHEN** an imported session's game, start time, and end time do not match any existing
  session
- **THEN** the imported session is added without displacing any existing session

#### Scenario: Aggregates are recomputed, not trusted
- **WHEN** an import completes
- **THEN** total XP, level, and current streak reflect a fresh computation over the merged raw
  data rather than the values recorded in the imported file

### Requirement: Longest streak is protected during import
Importing a backup SHALL NOT lower the player's stored longest streak. The value after import
SHALL be the maximum of the streak stored before the import, the streak recorded in the imported
file, and the streak produced by recomputing over the merged raw data.

#### Scenario: Import carries a lower longest streak
- **WHEN** the imported file's longest streak is lower than the value currently stored
- **THEN** the stored longest streak remains unchanged after import

#### Scenario: Import carries a higher longest streak
- **WHEN** the imported file's longest streak is higher than the value currently stored, and no
  higher value is produced by recomputation
- **THEN** the stored longest streak is raised to the imported value

### Requirement: Achievement rarity snapshot is protected during import
An achievement's frozen rarity snapshot (`snapshotPercent`) already present locally SHALL NOT be
overwritten by an imported value for the same achievement once it has been set. When both the
local database and the imported file have a snapshot for the same achievement, the snapshot
associated with the earlier unlock timestamp SHALL be retained.

#### Scenario: Local snapshot already exists
- **WHEN** an imported file includes a snapshot for an achievement that already has a locally
  stored snapshot
- **THEN** the locally stored snapshot and its unlock timestamp are retained, and the imported
  value is discarded for that achievement

#### Scenario: No local snapshot exists yet
- **WHEN** an imported file includes a snapshot for an achievement with no locally stored
  snapshot
- **THEN** the imported snapshot and its unlock timestamp are stored

### Requirement: Cross-account import is allowed with a warning
When the SteamID64 recorded in an imported backup differs from the currently signed-in account's
SteamID64, the system SHALL present a clear warning identifying the mismatch before proceeding,
but SHALL NOT block the import.

#### Scenario: Mismatched account detected
- **WHEN** the user attempts to import a file whose recorded SteamID64 differs from the
  currently signed-in account
- **THEN** a warning identifying the mismatch is shown before the import proceeds

#### Scenario: User proceeds past the warning
- **WHEN** the user confirms the import despite the mismatch warning
- **THEN** the import proceeds using the same merge semantics as a matching-account import

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

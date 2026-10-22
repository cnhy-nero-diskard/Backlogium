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
identity skeleton (app ID, name/display name), session history, recovery snapshots and quantity baselines, funded contribution identities and replacements, unknown-time allocations with date/zone/window/method provenance, daily progress, HLTB match data,
library sort preferences, the player's XP/level/streak state, and an export-time computed
rollup (XP per game, XP over time). The Steam Web API key SHALL NOT be included in the file
under any circumstance. Timestamps in the file SHALL be ISO-8601 strings, not epoch values.

#### Scenario: Exporting produces a complete file
- **WHEN** an export is generated
- **THEN** the resulting file contains the player's session history, recovery snapshots and quantity baselines, funded contribution identities and replacements, unknown-time allocations with date/zone/window/method provenance, daily progress, XP/level/
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

#### Scenario: Recovery state is a coherent snapshot
- **WHEN** a backup containing recovery activity is exported
- **THEN** the raw quantity/contribution graph, placements, observation baseline and computed totals reflect one point in time
- **AND** observation timestamps are not exported as gameplay session boundaries

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
active rule configuration, not the configuration recorded in the file. A session's two independent
cloud-contribution facts SHALL be replaced by explicit values in a version 2 backup,
but a version 1 backup that carries no provenance field SHALL leave existing local session
provenance intact. The importer SHALL support exactly backup format versions 1, 2 and 3; all new
exports SHALL use version 3. A missing root `formatVersion` SHALL be interpreted as version 1
only for a legacy payload with no `cloudContribution` fields, because the version 1 serializer
could omit its default version value. A version 1 payload that contains `cloudContribution` SHALL
be rejected as mixed-version input. Version 2 SHALL include an explicit root version and a
required, non-null `cloudContribution` object on every session, with required states for
`recoveredSharedPlay` and `timingInformedSteamPlay`. Each state SHALL be `UNKNOWN`, `NONE`, `FULL`,
or `PARTIAL`; JSON null or an absent object/member is invalid.

#### Scenario: Overlapping session imported
- **WHEN** an imported session matches an existing session's game, start time, and end time
- **THEN** the existing session's stored values are replaced by the imported values, and no
  duplicate session is created
- **AND** a missing provenance field in an older backup does not erase locally known provenance

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

#### Scenario: Version 3 merges contribution identity once
- **WHEN** a version 3 backup contains a contribution already present locally with different placement
- **THEN** the quantity is not summed twice and the merge preserves one consistent current placement revision
- **AND** a timed upgrade is not downgraded by an older estimate or resurrected legacy session

#### Scenario: Legacy input does not erase recovery state
- **WHEN** a valid version 1 or 2 backup is merged into an installation with recovery records
- **THEN** its absent recovery fields do not erase observations, contributions or uncertainty
- **AND** ambiguous legacy activity is not assumed to be an additional funded contribution

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

### Requirement: Session cloud-contribution provenance survives backup and merge

A backup containing session history SHALL also carry both independent session cloud-contribution facts, and an import SHALL restore them with their matching session without altering tracked minutes or deriving provenance from timestamps. A session MAY carry recovered-play and timing-informed facts at the same time, each with its own full/partial state. Older supported backup files that lack provenance SHALL remain importable; absence SHALL mean unknown for newly imported sessions, while an import missing the field SHALL NOT erase already-known contribution facts on a matching local session. The backup SHALL NOT include the cloud reader URL, bearer credential, read position, or pending interval evidence.

#### Scenario: Export and restore a contributed session
- **WHEN** a backup containing a session with cloud contribution evidence is restored on the same account
- **THEN** that session retains both independent fact states, including both facts when present, and its recorded minutes are unchanged

#### Scenario: New export writes explicit version and provenance markers
- **WHEN** a backup is exported
- **THEN** it declares `formatVersion: 3` and every session carries a non-null `cloudContribution` object with both required fact states, including `{"recoveredSharedPlay":"UNKNOWN","timingInformedSteamPlay":"UNKNOWN"}` when provenance is unknown

#### Scenario: Import an older backup
- **WHEN** a version 1 backup (with an explicit `formatVersion: 1` or the root version omitted as in legacy exports) has a session with no provenance field
- **THEN** the backup remains importable and a newly inserted session's provenance is unknown

#### Scenario: Older backup overlaps a known session
- **WHEN** a legacy backup session matches a local session that already records cloud contribution evidence
- **THEN** the existing evidence is retained rather than replaced with an inferred or unknown value

#### Scenario: Version 2 explicitly records unknown provenance
- **WHEN** a version 2 backup session has both contribution facts set to `UNKNOWN` and matches a local session with known contribution evidence
- **THEN** both matching facts are replaced with unknown while its natural key and recorded minutes remain unchanged

#### Scenario: Version 2 preserves independent fact states
- **WHEN** a version 2 session records one fact as `UNKNOWN` and the other as `FULL` or `PARTIAL`
- **THEN** import restores those states independently without inferring or erasing the known fact

#### Scenario: Version 2 replaces independent known provenance
- **WHEN** a version 2 backup session has known recovered-play and/or timing-informed fact states and matches a local session with different provenance
- **THEN** each imported fact state replaces its corresponding local state, including when both facts coexist, while the session's natural key and recorded minutes remain unchanged

#### Scenario: Version 2 provenance marker is missing or null
- **WHEN** a version 2 backup omits a session's `cloudContribution` object or either required fact, sets one to JSON null, or uses an invalid state
- **THEN** the backup is rejected before any data is modified

#### Scenario: V2 provenance appears without a root version
- **WHEN** a backup omits `formatVersion` but contains a `cloudContribution` field
- **THEN** the backup is rejected as an ambiguous mixed-version file before any data is modified

#### Scenario: Version 1 contains a version 2 provenance field
- **WHEN** a backup declares `formatVersion: 1` but contains a `cloudContribution` field
- **THEN** the backup is rejected as mixed-version input before any data is modified

#### Scenario: Unsupported backup format version
- **WHEN** a backup declares a format version other than 1, 2, or 3
- **THEN** the backup is rejected before any data is modified

#### Scenario: Reader credentials stay on the device
- **WHEN** a backup is exported
- **THEN** neither the cloud reader credential nor its URL, read position, or pending interval evidence is included in the file

### Requirement: Recovery backups preserve uncertainty and account-safe baselines
Version 3 SHALL require explicit recovery sections containing account/game-scoped observations, authoritative contribution identities and quantity, placement revision/replacement links, and unknown-time allocation date/zone/window/method provenance. An empty section SHALL be explicit; absence, malformed values, inconsistent funding sums, duplicate conflicting identities and dangling replacement links SHALL fail full-file preflight before any write. Version 3 SHALL retain the version 2 required independent session cloud-contribution facts. Versions 1 and 2 SHALL not contain version 3 recovery sections and SHALL retain their prior compatibility behavior. Dates SHALL be ISO calendar dates, zones valid zone identifiers and observation instants ISO-8601 timestamps.

Same-account imports SHALL preserve the newer usable baseline and never roll it backwards; their credited contribution coverage SHALL be coherent with that baseline. A restore lacking coherent matching-account recovery state SHALL establish a fresh trustworthy baseline before detecting new play, without re-crediting imported activity. Cross-account import SHALL retain the existing warning and history-merge policy, but foreign observation baselines SHALL never be used to diff the configured account. Repeated import SHALL not duplicate quantities, warnings or replacements.

#### Scenario: Recovery round trip
- **WHEN** four estimated day allocations are exported as version 3 and restored
- **THEN** their exact total, individual dates, allocation zone, contribution identity and estimate explanation survive

#### Scenario: Same-account older backup
- **WHEN** an older recovery snapshot is imported over a newer live baseline
- **THEN** the newer usable baseline remains and no previously credited increase becomes available again

#### Scenario: Foreign baseline
- **WHEN** the user confirms importing another account's backup
- **THEN** its history follows the existing merge policy but its recovery baseline remains ineligible for the configured account's Steam reads

#### Scenario: Legacy restore
- **WHEN** a legacy backup has credited history but no coherent recovery baseline
- **THEN** the first trustworthy read establishes a baseline and does not convert lifetime totals or imported records into another play increase

#### Scenario: Invalid quantity graph
- **WHEN** version 3 placements sum to a quantity different from their funded contribution
- **THEN** import is rejected before modifying the database

#### Scenario: Interrupted import
- **WHEN** a version 3 import fails partway through persistence
- **THEN** observations, contribution revisions, session replacements and allocations all roll back together

### Requirement: Recovery epoch validity is checked before import mutation
Version 3 import preflight SHALL reject every recovery observation or funded contribution whose baseline epoch is zero or negative, before any database or derived-state mutation. A file reported valid SHALL satisfy the recovery writer's structural epoch constraints rather than defer those checks until persistence or a subsequent Steam observation. Rejection SHALL identify the invalid record and leave existing baselines, history, raw daily credit and progression unchanged. This requirement SHALL preserve supported positive-epoch v3 round trips and legacy v1/v2 import behavior; missing recovery epochs in legacy formats SHALL NOT be interpreted as an explicit zero epoch or as new lifetime credit.

#### Scenario: Zero epoch on an observation without funded history
- **WHEN** a version 3 backup contains a recovery observation with epoch zero, including a file with no funded contributions
- **THEN** preflight rejects that observation before it can become an active baseline and no receiving state changes

#### Scenario: Zero or negative contribution epoch
- **WHEN** a version 3 contribution uses epoch zero or a negative epoch even though its placement quantities otherwise conserve
- **THEN** preflight rejects the contribution before persistence begins and reports the epoch problem

#### Scenario: Negative observation epoch
- **WHEN** a version 3 observation has a negative baseline epoch
- **THEN** the file is rejected without changing stored or derived activity

#### Scenario: A supported positive epoch is restored then read
- **WHEN** a coherent same-account version 3 backup with a positive epoch is restored and the next trustworthy Steam reading reports the same cumulative total
- **THEN** the restored epoch and quantity remain coherent and no already-funded minutes become a new increase

#### Scenario: First account poll exceeds a restored per-game high-water
- **WHEN** a fresh install restores a trustworthy same-account per-game observation at 605 minutes and its very first Steam poll reports 630 while the account-wide last-sync timestamp is zero
- **THEN** exactly 25 new minutes are funded using the saved per-game observation window, without an epoch-zero session boundary
- **AND** unobserved games in that poll establish baselines without lifetime credit, and repeated polls and imports preserve the funded quantity exactly once

#### Scenario: Legacy import keeps existing compatibility
- **WHEN** a supported version 1 or 2 backup without recovery epoch fields is imported
- **THEN** its existing merge and fresh-baseline rules apply without rejecting it for missing modern fields or re-crediting its history

#### Scenario: Rejected recovery data leaves unrelated history intact
- **WHEN** a backup contains otherwise importable games, sessions and daily records together with an invalid recovery epoch
- **THEN** none of those records or the receiving progression state is modified

### Requirement: V3 recovery graphs are representable and linked to their source baseline
Version 3 recovery observations and contribution ranges SHALL stay within the nonnegative `Int` playtime domain of the live Steam observation writer; preflight SHALL reject values outside `0..Int.MAX_VALUE` rather than allow restore to clamp the detector pair. Conserved historical contribution graphs MAY outlive or lack an observation for the same source account and app and SHALL remain exportable and importable without fabricating a detector baseline or observation timestamp. Recovery epochs may differ across independently merged backup lineages: when a matching observation is present and a contribution epoch is equal to or newer than that observation epoch, its endpoint SHALL be covered by that observation's high-water; an older-epoch contribution SHALL remain valid historical quantity after a deliberate baseline re-establishment, even when its cumulative endpoint exceeds the newer epoch's lower high-water. Recovery observations and contributions SHALL use the SteamID64 recorded by the backup identity; this source-identity check SHALL NOT block importing a foreign backup whose recovery records consistently identify that foreign source account. Timed placements, allocations, recipes and replacements SHALL remain linked to their contribution's account/app identity, and portable timed-session links SHALL resolve unambiguously.

#### Scenario: Unrepresentable Steam detector values
- **WHEN** a version 3 observation high-water or contribution endpoint exceeds `Int.MAX_VALUE`
- **THEN** full-file preflight rejects the specific recovery record before any mutation instead of importing a value the live detector cannot represent

#### Scenario: Funded range is not covered by its observation
- **WHEN** a contribution has a same-account/app observation and its endpoint exceeds that observation's high-water while it belongs to the same or a newer epoch
- **THEN** preflight rejects that contribution as incoherent before persistence

#### Scenario: Historical graph has no observation
- **WHEN** a conserved positive-epoch contribution graph without a matching observation is exported and restored
- **THEN** validation permits the file and restore preserves its quantity, dates, zones, provenance and contribution identity without inventing an observation
- **AND** re-export and repeated restore preserve that history without duplicating it
- **AND** if the receiving game has no usable local detector baseline, its first trustworthy Steam read establishes one without re-crediting imported history, even if other games have already synced
- **AND** the fresh baseline uses an epoch newer than the retained historical contribution epochs so a lower first cumulative reading does not invalidate those historical graphs

#### Scenario: Older epoch history survives baseline re-establishment
- **WHEN** a contribution from an older positive epoch is present beside a newer observation epoch with a lower high-water
- **THEN** the historical contribution remains valid and conserved without being clamped to the newer baseline

#### Scenario: Cross-account import preserves source attribution
- **WHEN** the user confirms importing a foreign backup whose recovery accounts match its recorded identity
- **THEN** validation permits the file, the existing warning/history merge policy remains, and the foreign observations remain inactive for the receiving account
- **WHEN** recovery records inside one file identify a different account from that file's identity
- **THEN** preflight rejects the inconsistent source-account link before mutation

#### Scenario: Ambiguous portable timed-session reference
- **WHEN** a timed placement's natural key matches more than one exported session
- **THEN** preflight rejects the ambiguous recovery reference before persistence

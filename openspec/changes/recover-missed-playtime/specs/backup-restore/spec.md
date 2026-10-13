## MODIFIED Requirements

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
- **WHEN** a backup declares a format version other than 1 or 2
- **THEN** the backup is rejected before any data is modified

#### Scenario: Reader credentials stay on the device
- **WHEN** a backup is exported
- **THEN** neither the cloud reader credential nor its URL, read position, or pending interval evidence is included in the file

## ADDED Requirements

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

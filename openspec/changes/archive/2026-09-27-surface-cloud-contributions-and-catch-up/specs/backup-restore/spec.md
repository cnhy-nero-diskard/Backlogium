## MODIFIED Requirements

### Requirement: Import merge does not double-count or blindly overwrite
Merging an imported backup into existing local data SHALL use natural-key upsert per data type
(replacing a matching key's values and adding keys present only in the import), and SHALL NOT
sum, duplicate, or otherwise double-count any value. Aggregate values (total XP, level, current
streak) SHALL always be recomputed from the merged raw data after import; they SHALL NOT be
taken directly from the imported file. The recompute SHALL use the receiving installation's
active rule configuration, not the configuration recorded in the file. A session's two independent
cloud-contribution facts SHALL be replaced by explicit values in a version 2 backup,
but a version 1 backup that carries no provenance field SHALL leave existing local session
provenance intact. The importer SHALL support exactly backup format versions 1 and 2; all new
exports SHALL use version 2. A missing root `formatVersion` SHALL be interpreted as version 1
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

## ADDED Requirements

### Requirement: Session cloud-contribution provenance survives backup and merge

A backup containing session history SHALL also carry both independent session cloud-contribution facts, and an import SHALL restore them with their matching session without altering tracked minutes or deriving provenance from timestamps. A session MAY carry recovered-play and timing-informed facts at the same time, each with its own full/partial state. Older supported backup files that lack provenance SHALL remain importable; absence SHALL mean unknown for newly imported sessions, while an import missing the field SHALL NOT erase already-known contribution facts on a matching local session. The backup SHALL NOT include the cloud reader URL, bearer credential, read position, or pending interval evidence.

#### Scenario: Export and restore a contributed session
- **WHEN** a backup containing a session with cloud contribution evidence is restored on the same account
- **THEN** that session retains both independent fact states, including both facts when present, and its recorded minutes are unchanged

#### Scenario: New export writes explicit version and provenance markers
- **WHEN** a backup is exported
- **THEN** it declares `formatVersion: 2` and every session carries a non-null `cloudContribution` object with both required fact states, including `{"recoveredSharedPlay":"UNKNOWN","timingInformedSteamPlay":"UNKNOWN"}` when provenance is unknown

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

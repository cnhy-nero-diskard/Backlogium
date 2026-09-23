## MODIFIED Requirements

### Requirement: Import merge does not double-count or blindly overwrite
Merging an imported backup into existing local data SHALL use natural-key upsert per data type
(replacing a matching key's values and adding keys present only in the import), and SHALL NOT
sum, duplicate, or otherwise double-count any value. Aggregate values (total XP, level, current
streak) SHALL always be recomputed from the merged raw data after import; they SHALL NOT be
taken directly from the imported file. The recompute SHALL use the receiving installation's
active rule configuration, not the configuration recorded in the file. A session's known
cloud-contribution provenance SHALL be replaced by an explicit value in a newer backup, but
an older backup that carries no provenance field SHALL leave existing local session
provenance intact.

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

A backup containing session history SHALL also carry any recorded session cloud-contribution provenance, and an import SHALL restore that evidence with its matching session without altering tracked minutes or deriving provenance from timestamps. Older supported backup files that lack provenance SHALL remain importable; absence SHALL mean unknown for newly imported sessions, while an import missing the field SHALL NOT erase an already-known contribution on a matching local session. The backup SHALL NOT include the cloud reader URL, bearer credential, or read position.

#### Scenario: Export and restore a contributed session
- **WHEN** a backup containing a session with cloud contribution evidence is restored on the same account
- **THEN** that session retains the kind and partial status of its contribution and its recorded minutes are unchanged

#### Scenario: Import an older backup
- **WHEN** a supported earlier-format backup has a session with no provenance field
- **THEN** the backup remains importable and a newly inserted session's provenance is unknown

#### Scenario: Older backup overlaps a known session
- **WHEN** a legacy backup session matches a local session that already records cloud contribution evidence
- **THEN** the existing evidence is retained rather than replaced with an inferred or unknown value

#### Scenario: Reader credentials stay on the device
- **WHEN** a backup is exported
- **THEN** neither the cloud reader credential nor its URL or read position is included in the file

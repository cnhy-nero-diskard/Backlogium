# backup-restore

## ADDED Requirements

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

## MODIFIED Requirements

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

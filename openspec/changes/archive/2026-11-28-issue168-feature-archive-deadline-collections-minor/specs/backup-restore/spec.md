# Backup Restore Delta

## ADDED Requirements

### Requirement: Backups retain immutable deadline archives
Backups and snapshots SHALL preserve archived collection records and frozen evidence through validated, idempotent restore. Validation SHALL preserve the distinction between exact play quantities and Steam bounds, reject unsupported combinations of Steam and family-shared evidence, require family-shared session evidence to be a positive interval with a count no greater than its non-null recorded-session count, and recompute the frozen outcome from that evidence. It SHALL reject contradictory evidence before any write rather than normalize immutable records. Older exports without archive data SHALL remain importable.

#### Scenario: Repeated restore
- **WHEN** a compatible backup containing an archive is imported twice
- **THEN** one archive remains with the same stable identity and outcome

#### Scenario: Older export or active copy is imported
- **WHEN** the target already contains a finalized archive for the same identity
- **THEN** merge does not reactivate it or replace its frozen outcome

#### Scenario: Conflicting or invalid archive record
- **WHEN** an import attempts to supply conflicting immutable content for the same identity or an invalid archival reference
- **THEN** the conflict is surfaced before writes and the stored archive is preserved

#### Scenario: Frozen cutoff membership and outcome round-trip
- **WHEN** an archived deadline includes a member hidden or removed after cutoff and is exported and restored where current membership or visibility differs
- **THEN** its persisted cutoff membership, cutoff visibility provenance, evaluated membership, per-member evidence, and outcome are restored unchanged

#### Scenario: Invalid member evidence is rejected before restore
- **WHEN** a version 4 archive combines exact Steam play with family-shared session lower-bound evidence, pairs session evidence with a missing or smaller recorded-session count, or records a zero-length session interval
- **THEN** decoding and validation reject the backup before any imported data is written, and the existing archive remains unchanged

#### Scenario: Frozen Steam bounds round-trip
- **WHEN** a version 4 archive contains a supported Steam lower bound and monotonic upper bound
- **THEN** restore preserves those bounds and their observation timestamps as bounds, without converting them to exact play or remaining minutes

# Backup Restore Delta

## ADDED Requirements

### Requirement: Backups retain immutable deadline archives
Backups and snapshots SHALL preserve archived collection records and frozen evidence through validated, idempotent restore. Older exports without archive data SHALL remain importable.

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

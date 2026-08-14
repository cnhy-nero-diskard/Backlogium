# schema-migration

## Purpose

Defines the guarantees and mechanical verification for upgrading a previously released Room
database: stored data survives migration, current schemas are recorded, historical limits are
explicit, and migration tests run automatically.

## Requirements

### Requirement: Upgrading an existing installation preserves its data
A database created by a previously released version of the app SHALL be upgradable to the
current version with all stored records intact. No migration SHALL discard, truncate, or
reset a stored value as a side effect of changing the schema, because the tracked history
cannot be re-derived from any external source once lost.

#### Scenario: Upgrade across a single version
- **WHEN** a database at the immediately previous version is opened by the current version
- **THEN** the upgrade completes and every stored record remains readable with the values it
  had before the upgrade

#### Scenario: Upgrade across several versions at once
- **WHEN** a database several versions behind is opened by the current version
- **THEN** the full chain of migrations runs in order and every stored record remains
  readable with its prior values

#### Scenario: App-owned columns survive
- **WHEN** an upgrade runs against a database containing app-derived values that Steam does
  not supply — backfilled minutes, focus flags, accumulated experience, and the
  longest-streak high-water mark
- **THEN** each of those values is unchanged after the upgrade

#### Scenario: Achievement rarity snapshots survive
- **WHEN** an upgrade runs against a database containing achievement rarity snapshots
- **THEN** each snapshot is unchanged, preserving the first-unlock value it recorded

#### Scenario: A migration that loses data is a defect
- **WHEN** a migration produces the expected schema but does not carry stored rows forward
- **THEN** that migration is treated as incorrect, because matching the schema is not
  sufficient evidence that an upgrade succeeded

### Requirement: Migration correctness is mechanically verified
The upgrade guarantee SHALL be verified by automated tests that run the real migrations
against a database created at an earlier version and seeded with representative records,
rather than being asserted by review. The current schema SHALL be recorded in the
repository so that each subsequent version's migration can be verified against a known
prior shape.

#### Scenario: Schema is recorded
- **WHEN** the database version is incremented
- **THEN** the schema for the new version is exported and committed, so the shape it
  migrated from is recoverable by a later test

#### Scenario: Automated verification of a new migration
- **WHEN** a new migration is added
- **THEN** a test exists that creates a database at the preceding version, seeds records,
  runs the migration, and asserts both the resulting schema and the survival of every
  seeded value

#### Scenario: Verification runs on every change
- **WHEN** a change is proposed to the repository
- **THEN** the migration tests execute automatically, so a data-losing migration cannot
  merge on the strength of unit tests that only ever see a freshly created database

#### Scenario: Versions predating schema recording
- **WHEN** no recorded schema exists for a version because it was released before schemas
  were exported
- **THEN** the limits of what can be verified are written down rather than left implicit,
  and any version still plausibly present on a device is covered by an explicit fixture

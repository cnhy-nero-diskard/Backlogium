# schema-migration

## MODIFIED Requirements

### Requirement: Upgrading an existing installation preserves its data
A database created by a previously released version of the app SHALL be upgradable to the
current version with all stored records intact. No migration SHALL discard, truncate, or
reset a stored value as an incidental side effect of changing the schema, because the tracked
history cannot be re-derived from any external source once lost.

A migration MAY translate a stored value into a new representation, or repair a value already
known to be wrong, when that transformation is the explicit purpose of the migration and is
covered by a test asserting the intended result. Such a transformation SHALL preserve the
meaning of the record even where it changes the stored bytes, and SHALL NOT be used to
justify convenience deletion. The distinction is intent plus mechanical verification:
incidental loss is a defect, designed repair is the point.

#### Scenario: Upgrade across a single version
- **WHEN** a database at the immediately previous version is opened by the current version
- **THEN** the upgrade completes and every stored record remains readable with the values it
  had before the upgrade, except where a migration's declared purpose is to transform them

#### Scenario: Upgrade across several versions at once
- **WHEN** a database several versions behind is opened by the current version
- **THEN** the full chain of migrations runs in order and every stored record remains
  readable, carrying either its prior value or the value a declared transformation produced

#### Scenario: App-owned columns survive
- **WHEN** an upgrade runs against a database containing app-derived values that Steam does
  not supply — backfilled minutes, focus flags, accumulated experience, and the
  longest-streak high-water mark
- **THEN** each of those values is unchanged after the upgrade

#### Scenario: Achievement rarity snapshots survive
- **WHEN** an upgrade runs against a database containing achievement rarity snapshots
- **THEN** each snapshot is unchanged, preserving the first-unlock value it recorded

#### Scenario: A migration that loses data is a defect
- **WHEN** a migration produces the expected schema but does not carry stored rows forward,
  and carrying them forward was not something the migration set out to change
- **THEN** that migration is treated as incorrect, because matching the schema is not
  sufficient evidence that an upgrade succeeded

#### Scenario: Representation change that retires an obsolete row
- **WHEN** a migration's declared purpose is to move a fact out of a sentinel row and into a
  dedicated column, and it deletes the sentinel row once the fact has been carried across
- **THEN** the migration is correct, provided a test asserts the fact survives in its new
  representation

#### Scenario: Repair of a known-wrong stored value
- **WHEN** a migration's declared purpose is to correct values written under an identified
  earlier defect, such as timestamps stored at the wrong scale
- **THEN** the migration is correct, provided a test asserts the corrected value, because
  preserving a value already known to be wrong would preserve the defect rather than the data

### Requirement: Migration correctness is mechanically verified
The upgrade guarantee SHALL be verified by automated tests that run the real migrations
against a database created at an earlier version and seeded with representative records,
rather than being asserted by review. The current schema SHALL be recorded in the
repository so that each subsequent version's migration can be verified against a known
prior shape.

Verification SHALL cover both shapes of upgrade a device can actually perform: each
individual migration in isolation, and the composed chain from the oldest fixture version
through to the current database version. A per-hop suite alone is insufficient evidence,
because two individually correct migrations can interact badly when Room runs them in
sequence — a later migration may assume a shape, default, or data state that an earlier one
produced differently. The chain test SHALL be written so that incrementing the database
version without extending it is visible as a failure or an explicit assertion against the
current version, rather than silently continuing to validate a stale target.

#### Scenario: Schema is recorded
- **WHEN** the database version is incremented
- **THEN** the schema for the new version is exported and committed, so the shape it
  migrated from is recoverable by a later test

#### Scenario: Automated verification of a new migration
- **WHEN** a new migration is added
- **THEN** a test exists that creates a database at the preceding version, seeds records,
  runs the migration, and asserts both the resulting schema and the survival of every
  seeded value

#### Scenario: Composed chain to the current version
- **WHEN** a database is created at the oldest version with a populated fixture and opened by
  the current version
- **THEN** every registered migration between those versions runs in order and the
  representative seeded rows and values are asserted to survive at the current version

#### Scenario: Chain target follows the database version
- **WHEN** the database version is incremented and the deep-history chain test is not
  extended to the new version
- **THEN** that omission is detectable, because the test asserts against the current version
  rather than a hard-coded older one

#### Scenario: Verification runs on every change
- **WHEN** a change is proposed to the repository
- **THEN** the migration tests execute automatically, so a data-losing migration cannot
  merge on the strength of unit tests that only ever see a freshly created database

#### Scenario: Versions predating schema recording
- **WHEN** no recorded schema exists for a version because it was released before schemas
  were exported
- **THEN** the limits of what can be verified are written down rather than left implicit,
  and any version still plausibly present on a device is covered by an explicit fixture

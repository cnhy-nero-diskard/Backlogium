# Schema Migration Delta

## MODIFIED Requirements

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

#### Scenario: Verification runs before a migration-relevant change merges
- **WHEN** a pull request changes the schema, migration behavior or registration, a
  migration test input, the app or shared Gradle configuration, or the CI workflow or
  routing that controls migration verification
- **THEN** CI runs the migration suite automatically and the required CI result depends on
  its success

#### Scenario: An unrelated pull request skips the migration emulator
- **WHEN** a pull request changes only inputs outside those migration and verification
  paths
- **THEN** CI skips the Room migration emulator job

#### Scenario: Instrumentation sources retain compile coverage
- **WHEN** a pull request changes an unrelated source under `app/src/androidTest/`
- **THEN** CI selects the migration emulator job so Gradle compiles the complete
  instrumentation test APK, while the runner continues to execute only `MigrationTest`

#### Scenario: Verification runs on every change
- **WHEN** a commit is pushed to `master`
- **THEN** CI runs the migration suite automatically regardless of which paths changed

#### Scenario: Versions predating schema recording
- **WHEN** no recorded schema exists for a version because it was released before schemas
  were exported
- **THEN** the limits of what can be verified are written down rather than left implicit,
  and any version still plausibly present on a device is covered by an explicit fixture

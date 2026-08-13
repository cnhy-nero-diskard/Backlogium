# backup-restore

## ADDED Requirements

### Requirement: The app's explicit backup model is the only backup channel for library data
The app SHALL declare an explicit platform-backup policy rather than inheriting a default one, and
that policy SHALL exclude the tracked library, settings, and automatic snapshots from any
platform-managed backup or device-transfer channel. Library data SHALL leave the device only through
the app's own export, so there is exactly one restore path whose retention and semantics the user
can see.

#### Scenario: Platform backup policy is explicit
- **WHEN** the app's backup configuration is inspected
- **THEN** it enumerates what is included and excluded rather than leaving the decision to a
  template or a platform default

#### Scenario: Library data excluded from platform backup
- **WHEN** the platform performs a cloud backup or a device-to-device transfer
- **THEN** the tracked library, settings, and automatic snapshots are not among the transferred data

#### Scenario: Explicit export is unaffected
- **WHEN** the user exports a backup through the app
- **THEN** the export contains the same data as before, because the app's own backup model is
  unchanged by the platform policy

### Requirement: Automatic snapshots are stored outside the platform-backup path
Automatic snapshots SHALL be written to app-private storage that the platform excludes from backup
and device transfer, so that a snapshot cannot be copied into a second backup lifecycle with
retention and ownership the app does not control.

#### Scenario: New snapshot location
- **WHEN** an automatic snapshot is written
- **THEN** it is stored in app-private storage that is excluded from platform backup and device
  transfer

#### Scenario: Snapshots written before this requirement took effect
- **WHEN** the app starts and finds snapshots in the previously used location
- **THEN** those snapshots are moved to the excluded location and remain listed and restorable, so
  no retained snapshot is lost to the relocation

#### Scenario: Relocation interrupted
- **WHEN** a relocation does not complete because the process ends partway through
- **THEN** no snapshot has been deleted without having been successfully copied first, and a
  subsequent start completes the relocation

#### Scenario: Retention during relocation
- **WHEN** retention pruning runs while a relocation is incomplete
- **THEN** pruning does not discard the only remaining copy of a snapshot

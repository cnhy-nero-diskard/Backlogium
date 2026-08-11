## ADDED Requirements

### Requirement: Data & Backup section
The Settings screen SHALL present a "Data & Backup" section, separate from the existing history
import data controls, containing: an automatic-snapshot on/off toggle, an adjustable snapshot
retention count, an adjustable snapshot interval, a list of currently retained automatic
snapshots with a restore action per entry, and manual "Export Backup" and "Import Backup"
actions.

#### Scenario: Data & Backup section shown
- **WHEN** the Settings screen is shown
- **THEN** the Data & Backup section is presented with the auto-snapshot toggle, retention
  count, snapshot interval, the current snapshot list, and the manual export/import actions

#### Scenario: Manual actions independent of the toggle
- **WHEN** the auto-snapshot toggle is off
- **THEN** the manual "Export Backup" and "Import Backup" actions remain visible and usable

#### Scenario: Adjusting retention count
- **WHEN** the user changes the snapshot retention count and confirms
- **THEN** the new count is persisted and used the next time a snapshot would be retained or
  discarded

#### Scenario: Adjusting snapshot interval
- **WHEN** the user changes the snapshot interval and confirms
- **THEN** the new interval is persisted and used the next time a successful sync evaluates
  whether a snapshot is due

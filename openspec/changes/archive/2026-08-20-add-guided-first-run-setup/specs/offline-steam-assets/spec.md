## MODIFIED Requirements

### Requirement: Manually triggered independent asset download
The system SHALL provide a repeatable one-time Steam asset download job that is triggered only by
an explicit user action and remains independent from manual and periodic Steam data synchronization.
Only one asset download job SHALL be enqueued or running at a time.

Selecting the asset stage in first-run setup and starting setup is such an explicit user action. The
distinction this requirement draws is between a download the user chose and one the app started on
its own — not between one surface and another. No asset download SHALL be enqueued as a consequence
of a sync completing, of setup completing, or of any schedule.

#### Scenario: User starts an asset download
- **WHEN** the user confirms an asset download mode
- **THEN** the system enqueues the dedicated one-time asset download job
- **AND** the Steam data-sync job is not triggered

#### Scenario: User selects the asset stage during setup
- **WHEN** the user selects the asset stage in first-run setup and starts setup
- **THEN** the dedicated asset download job is enqueued when that stage runs, exactly as it would be
  from its own control

#### Scenario: User does not select the asset stage
- **WHEN** the user starts setup without selecting the asset stage
- **THEN** no asset download is enqueued

#### Scenario: Steam sync completes
- **WHEN** a manual or periodic Steam data sync completes
- **THEN** no bulk asset download is automatically enqueued

#### Scenario: Setup completes
- **WHEN** first-run setup completes
- **THEN** no asset download is enqueued beyond the one its selected stage started

#### Scenario: No schedule enqueues a download
- **WHEN** any periodic or scheduled work runs
- **THEN** no bulk asset download is enqueued on its account

#### Scenario: Duplicate trigger while downloading
- **WHEN** an asset download is already enqueued or running and another trigger is attempted
- **THEN** the system keeps the existing job and does not stack a duplicate download

#### Scenario: User leaves Settings
- **WHEN** the user navigates away or the app process is recreated while a download is active
- **THEN** the download continues under WorkManager and its current state remains observable

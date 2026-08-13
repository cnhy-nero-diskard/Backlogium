## ADDED Requirements

### Requirement: Offline Steam assets settings section
The Settings screen SHALL provide an "Offline Steam assets" section separate from the Steam sync
controls. It SHALL describe the manual offline-storage behavior, show the currently stored asset
count and bytes, and provide an action to start a download when the local library has assets to
inventory.

#### Scenario: Offline assets section is shown
- **WHEN** the user opens Settings
- **THEN** a dedicated Offline Steam assets section is shown separately from `Sync now` and full achievement refresh

#### Scenario: No local asset inventory exists
- **WHEN** no locally synced profile, game, artwork, or achievement image can be inventoried
- **THEN** the download action is unavailable
- **AND** the section explains that the user must sync a Steam library first

#### Scenario: Stored assets exist
- **WHEN** one or more valid durable Steam assets are stored
- **THEN** the section shows their item count and total storage size

### Requirement: Asset download mode choice
Activating the offline asset action SHALL present a choice between downloading missing assets and
refreshing all assets, with concise copy explaining that refresh-all re-downloads existing files.

#### Scenario: User opens the download choice
- **WHEN** the user activates the asset download action
- **THEN** the UI offers `Download missing assets` and `Refresh all assets` before enqueueing work

#### Scenario: User chooses missing assets
- **WHEN** the user confirms `Download missing assets`
- **THEN** the dedicated worker is enqueued in `DOWNLOAD_MISSING` mode

#### Scenario: User chooses refresh all
- **WHEN** the user confirms `Refresh all assets`
- **THEN** the dedicated worker is enqueued in `REFRESH_ALL` mode

#### Scenario: User dismisses the choice
- **WHEN** the user dismisses the mode choice without confirming
- **THEN** no asset work is enqueued

### Requirement: Dedicated asset progress presentation
While the asset job is active, the Offline Steam assets section SHALL show its own state and
progress bar without replacing, disabling, or visually merging with Steam sync state. The active
presentation SHALL also offer a stop action.

#### Scenario: Asset job is queued
- **WHEN** the asset job is waiting for constraints or preparing its inventory
- **THEN** the section shows a queued or preparing state independently of Steam sync

#### Scenario: Asset job reports determinate progress
- **WHEN** the worker reports a positive total
- **THEN** the section shows a dedicated progress bar and processed-versus-total counts

#### Scenario: Asset download and Steam sync overlap
- **WHEN** Steam sync and asset download are active at the same time
- **THEN** each operation shows its own state and remains independently controlled

#### Scenario: User stops the asset download
- **WHEN** the user activates the stop control while asset work is enqueued or running
- **THEN** only the asset download is cancelled
- **AND** Steam sync state is unaffected

#### Scenario: Asset download reaches a terminal state
- **WHEN** the job completes, is cancelled, or fails before processing its inventory
- **THEN** the progress presentation resolves and the download action becomes available again
- **AND** any available completion or failure summary remains visible

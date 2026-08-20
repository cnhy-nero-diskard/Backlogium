## ADDED Requirements

### Requirement: Manually triggered independent asset download
The system SHALL provide a repeatable one-time Steam asset download job that is triggered only by
an explicit user action and remains independent from manual and periodic Steam data synchronization.
Only one asset download job SHALL be enqueued or running at a time.

#### Scenario: User starts an asset download
- **WHEN** the user confirms an asset download mode
- **THEN** the system enqueues the dedicated one-time asset download job
- **AND** the Steam data-sync job is not triggered

#### Scenario: Steam sync completes
- **WHEN** a manual or periodic Steam data sync completes
- **THEN** no bulk asset download is automatically enqueued

#### Scenario: Duplicate trigger while downloading
- **WHEN** an asset download is already enqueued or running and another trigger is attempted
- **THEN** the system keeps the existing job and does not stack a duplicate download

#### Scenario: User leaves Settings
- **WHEN** the user navigates away or the app process is recreated while a download is active
- **THEN** the download continues under WorkManager and its current state remains observable

### Requirement: Complete known Steam image inventory
The system SHALL construct the download inventory from the locally known Steam images Backlogium
currently renders. The inventory SHALL include the known player avatar, owned-game icons, all
supported derived game-art variants for each owned app id, and known achievement icons, with
duplicate URLs processed once per run.

#### Scenario: Owned game is inventoried
- **WHEN** an owned game exists in local storage
- **THEN** its non-blank game icon URL is included
- **AND** its `header.jpg`, `hero_capsule.jpg`, `library_hero.jpg`, `library_600x900.jpg`, and `capsule_616x353.jpg` URLs are included

#### Scenario: Profile and achievement images are inventoried
- **WHEN** the local profile or achievement rows contain non-blank Steam image URLs
- **THEN** those avatar and achievement icon URLs are included in the inventory

#### Scenario: Asset metadata is not locally known
- **WHEN** an image URL has not been discovered by existing Steam synchronization
- **THEN** the asset job does not call Steam metadata endpoints to discover it
- **AND** a later run includes it after normal synchronization makes it known

#### Scenario: Non-Steam or unused media is present
- **WHEN** HLTB covers, Steam screenshots, videos, or other media not rendered by Backlogium are considered
- **THEN** they are excluded from the inventory

### Requirement: User-selectable download behavior
Before a job is enqueued, the system SHALL let the user choose either `DOWNLOAD_MISSING` or
`REFRESH_ALL`. Missing-only SHALL avoid re-downloading valid stored assets and recently confirmed
unavailable assets. Refresh-all SHALL re-request every inventoried asset while preserving valid
existing files until replacements succeed.

#### Scenario: Download only missing assets
- **WHEN** the user selects `DOWNLOAD_MISSING`
- **THEN** valid stored files are skipped
- **AND** missing, corrupt, or previously transiently failed assets are requested

#### Scenario: Recently unavailable asset in missing-only mode
- **WHEN** an asset was confirmed unavailable with HTTP 404 or 410 within the previous 30 days
- **AND** the user selects `DOWNLOAD_MISSING`
- **THEN** the request is skipped and counted as unavailable

#### Scenario: Expired unavailable marker in missing-only mode
- **WHEN** an asset's unavailable marker is at least 30 days old
- **AND** the user selects `DOWNLOAD_MISSING`
- **THEN** the system requests the asset again

#### Scenario: Refresh every asset
- **WHEN** the user selects `REFRESH_ALL`
- **THEN** every current inventory URL is requested regardless of its stored or unavailable state

#### Scenario: Refresh request fails
- **WHEN** a refresh attempt for an asset fails validation or encounters a transient error
- **THEN** any previously valid stored file remains available and unchanged

### Requirement: Durable validated asset storage
The system SHALL store successful downloads as validated files in app-private persistent storage,
indexed by a manifest that records their source and integrity metadata. The downloaded files SHALL
not depend on Coil's evictable disk cache and SHALL not be included in Backlogium backup exports.

#### Scenario: Valid image is downloaded
- **WHEN** Steam returns a non-empty supported image that passes validation
- **THEN** the system commits it atomically to persistent app-private asset storage
- **AND** records its URL, kind, path, byte count, checksum, and timestamps in the manifest

#### Scenario: Response is not a valid image
- **WHEN** a successful HTTP response is empty, has an unsupported content type, or cannot be decoded as an image
- **THEN** the system does not mark or expose that response as a stored asset

#### Scenario: Stored file no longer matches its manifest
- **WHEN** a manifest entry points to a missing or integrity-mismatched file
- **THEN** the system treats the asset as missing and eligible for download

#### Scenario: Backup is exported
- **WHEN** the user exports a Backlogium backup
- **THEN** the downloaded Steam image files and their derived manifest are not included

### Requirement: Isolated partial-failure and resume behavior
The system SHALL process each inventory item independently with bounded concurrency. Expected
unavailable assets and transient failures SHALL NOT discard successful downloads from the same run,
and a retried or restarted attempt SHALL resume without re-downloading items already completed for
that run.

#### Scenario: Steam reports an asset is unavailable
- **WHEN** an asset request returns HTTP 404 or 410
- **THEN** the system records an unavailable result with its check time
- **AND** continues processing the remaining inventory

#### Scenario: One asset fails transiently
- **WHEN** a request times out, loses connectivity, returns another unsuccessful status, or fails image validation
- **THEN** the system counts that item as failed
- **AND** retains every other successfully committed item

#### Scenario: Refresh-all work is retried
- **WHEN** WorkManager restarts a `REFRESH_ALL` run after some assets were refreshed or confirmed unavailable
- **THEN** assets already completed at or after that run's recorded start time are skipped
- **AND** unfinished or transiently failed assets remain eligible

#### Scenario: User cancels an active download
- **WHEN** the user stops an enqueued or running asset download
- **THEN** future requests cease
- **AND** every asset already committed remains stored for the next run

### Requirement: Dedicated asset progress and summary
The system SHALL expose asset-download state independently of Steam sync, including a determinate
processed-versus-total progress value while inventory items are processed and a persisted summary
of stored count, stored bytes, completion time, and per-outcome counts.

#### Scenario: Inventory is being prepared
- **WHEN** the job is enqueued or running but has not established a non-zero total
- **THEN** the asset status is shown as queued or preparing without displaying a misleading `0 / 0` determinate value

#### Scenario: Download is processing assets
- **WHEN** the worker has established its inventory and processed at least one item
- **THEN** progress reports the processed count and total count
- **AND** the processed count never exceeds the total

#### Scenario: Download completes with mixed outcomes
- **WHEN** all inventory items have been attempted or skipped
- **THEN** the job completes without treating expected unavailable items as a whole-job failure
- **AND** the persisted summary distinguishes downloaded or refreshed, already stored, unavailable, and failed items

#### Scenario: Stored asset summary is viewed later
- **WHEN** no asset job is active and Settings is reopened
- **THEN** the system reports the durable stored-asset count and total bytes plus the last completed run summary when available

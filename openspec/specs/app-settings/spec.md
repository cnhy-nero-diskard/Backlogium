# app-settings

## Purpose

Defines the Settings destination: the Steam account section, sync section, editable
gamification rule configuration (and the retroactive-recompute behavior those edits
trigger), and the data-management (history import) controls.
## Requirements
### Requirement: Settings destination
The system SHALL provide a dedicated Settings destination reachable as a top-level navigation
destination, grouping the app's account, sync, data, and rule-configuration controls in one
place so the Home screen carries only progress content.

#### Scenario: Opening Settings
- **WHEN** the user selects the Settings destination from the app's navigation
- **THEN** the Settings screen is shown

#### Scenario: Settings renders offline
- **WHEN** the Settings screen is opened without network
- **THEN** it displays all current values from locally stored state and never blocks on a
  network call

#### Scenario: Settings while unconfigured
- **WHEN** Steam credentials are not configured
- **THEN** the Settings screen does not present a dead end, and the user is able to reach the
  onboarding flow to configure credentials

### Requirement: Steam account section
The Settings screen SHALL present the active SteamID and a masked form of the API key, with an
action that opens the onboarding flow so credentials can be changed. The raw API key SHALL NOT
be displayed.

#### Scenario: Viewing the account
- **WHEN** the Settings screen is shown while credentials are configured
- **THEN** it displays the active SteamID and a masked API key

#### Scenario: Editing credentials
- **WHEN** the user activates the account section's edit action
- **THEN** the onboarding flow opens so the user can change and re-save credentials

#### Scenario: Raw key never shown
- **WHEN** the account section is displayed
- **THEN** the API key appears only in masked form

### Requirement: Run setup section
The Settings screen SHALL present an entry that opens first-run setup, showing each registered
stage, its last recorded outcome, and letting the user select and run any of them. Stages SHALL
default to unselected, so a re-run is deliberate.

#### Scenario: Opening setup from Settings
- **WHEN** the user activates the setup entry
- **THEN** the staged checklist is presented, listing every registered stage

#### Scenario: Last outcome shown per stage
- **WHEN** the checklist is presented from Settings
- **THEN** each stage shows whether it last succeeded, failed, was skipped, or has never run

#### Scenario: Nothing selected by default
- **WHEN** the checklist is presented from Settings
- **THEN** no stage is selected until the user selects one

#### Scenario: Running selected stages
- **WHEN** the user selects one or more stages and starts them
- **THEN** those stages run and their outcomes replace the previously recorded ones

#### Scenario: Setup never run
- **WHEN** setup has never been run
- **THEN** the entry is still present and every stage shows as never run

#### Scenario: Credentials not configured
- **WHEN** no credentials are configured
- **THEN** the entry explains that credentials are required rather than starting stages that cannot
  succeed

### Requirement: Sync section
The Settings screen SHALL present the time of the last successful sync and a control that
triggers an immediate manual sync.

The section SHALL present each operation it offers as its own row carrying that operation's name,
its own status, and its own action, so that every status shown in the section is adjacent to the
control it describes. A status the user cannot act on SHALL be presented as a row with no control
rather than sharing a control with an unrelated operation.

#### Scenario: Viewing last sync
- **WHEN** the Settings screen is shown
- **THEN** it displays when the last sync completed

#### Scenario: Triggering a manual sync
- **WHEN** the user activates the manual sync control
- **THEN** a one-time poll is enqueued and the app reflects the updated state when it completes

#### Scenario: Sync control while a sync runs
- **WHEN** a manual sync is already in flight
- **THEN** the control cannot be triggered again until that sync completes

#### Scenario: Each status sits with its own action
- **WHEN** the Sync section presents more than one operation
- **THEN** each operation's status is presented in the same row as the control that triggers it

#### Scenario: Status with no action
- **WHEN** the section reports the state of work the user cannot trigger
- **THEN** that state is presented as its own row without a control, rather than beside a control
  belonging to a different operation

#### Scenario: Rearrangement preserves control behaviour
- **WHEN** the section is presented in its rearranged form
- **THEN** every control's enabled and disabled conditions are unchanged from before the
  rearrangement

### Requirement: Updates section
The Settings screen SHALL present, in release builds, an Updates section showing the running
version, when a check last completed, whether an update is available, and a control that checks
immediately. The section SHALL be absent in builds that a published release cannot upgrade.

#### Scenario: Viewing the section
- **WHEN** the Settings screen is shown in a release build
- **THEN** it presents the running version and when a check last completed

#### Scenario: No check has completed
- **WHEN** no check has ever completed
- **THEN** the section says so, rather than presenting an error or an empty value

#### Scenario: Update available
- **WHEN** an update is available
- **THEN** the section identifies the available version and offers to apply it

#### Scenario: Declined update still reachable
- **WHEN** the user has declined the available update
- **THEN** the section still shows it and still offers to apply it

#### Scenario: Checking manually
- **WHEN** the user activates the check control
- **THEN** a check runs immediately and the section reflects its outcome, including when the
  outcome is that no update exists

#### Scenario: Check fails
- **WHEN** a manual check cannot reach the release service
- **THEN** the section reports that the check did not complete and remains fully usable

#### Scenario: Development build
- **WHEN** the Settings screen is shown in a development build
- **THEN** the Updates section is absent

### Requirement: Opt-in live monitor setting
The Settings screen SHALL provide an off-by-default Live monitor control. When enabled, it SHALL
keep the app's user-started foreground presence monitor active while no game is running, so a
subsequently started game can be detected without reopening the app or waiting for periodic sync.

#### Scenario: Enabling live monitor
- **WHEN** the user enables Live monitor from Settings
- **THEN** the preference is persisted and the foreground monitor begins while the app is visible

#### Scenario: Disclosing ongoing monitoring
- **WHEN** the Live monitor control is presented
- **THEN** it discloses its 30-second network checks, ongoing notification, battery/data use, and
  Android's approximate six-hour background-service limit

#### Scenario: Disabling live monitor
- **WHEN** the user disables Live monitor while no game is running
- **THEN** idle monitoring stops and its ongoing notification is removed

### Requirement: Editable gamification rules
The Settings screen SHALL allow the user to change the gamification rule configuration the
engine already consumes. The daily quest goal, quest mode, and streak grace allowance SHALL be
presented as primary controls. The XP rate, level curve base, and per-rarity achievement XP
awards SHALL be presented separately as advanced controls that are not shown by default.

#### Scenario: Changing the daily quest goal
- **WHEN** the user changes the daily quest goal and confirms
- **THEN** the new value is persisted and subsequent quest evaluation uses it

#### Scenario: Changing the quest mode
- **WHEN** the user changes the quest mode between counting any game and counting goal games only
- **THEN** the new mode is persisted and subsequent quest evaluation uses it

#### Scenario: Advanced controls hidden by default
- **WHEN** the Settings screen is first shown
- **THEN** the XP rate, level base, and per-tier achievement XP controls are not visible until
  the user expands the advanced section

#### Scenario: Rejecting a degenerate value
- **WHEN** the user enters a rule value the engine cannot meaningfully use, such as a
  non-positive level base
- **THEN** the value is not persisted and the screen indicates why

### Requirement: Rule changes disclose their retroactive effect
Because derived gamification values are recomputed from raw inputs under the current
configuration, changing any rule re-evaluates the player's entire recorded history. The system
SHALL therefore require explicit confirmation before persisting a rule change, and the
confirmation SHALL state the concrete effect on the player's existing progress rather than a
generic warning.

#### Scenario: Confirming a primary rule change
- **WHEN** the user changes the daily quest goal, quest mode, or streak grace and attempts to save
- **THEN** a confirmation is presented stating that past days will be re-evaluated, including
  the resulting change to the player's current and longest streaks, and the change is persisted
  only if the user confirms

#### Scenario: Confirming an advanced rule change
- **WHEN** the user changes the XP rate, level base, or a per-tier achievement XP award and
  attempts to save
- **THEN** a confirmation is presented stating that the player's total XP and level will be
  recalculated, including the resulting level, and the change is persisted only if the user
  confirms

#### Scenario: Declining a rule change
- **WHEN** the user declines the confirmation
- **THEN** no value is persisted and no recompute occurs

### Requirement: Rule changes take effect immediately
When a rule change is persisted, the system SHALL recompute and persist the derived
gamification values without waiting for the next scheduled sync, so no screen displays a level,
quest status, or streak derived from the superseded configuration.

#### Scenario: Progress reflects a saved rule change
- **WHEN** a rule change is confirmed and persisted
- **THEN** the player's XP, level, per-day quest results, and streaks are recomputed under the
  new configuration before the user next views them

#### Scenario: No stale progress pending a sync
- **WHEN** a rule change has been persisted and no sync has run since
- **THEN** the Home screen reflects the recomputed values rather than values derived from the
  previous configuration

### Requirement: Longest streak is never lowered by a recompute
The persisted longest streak SHALL be a high-water mark: once a streak length has been
achieved, no subsequent recompute SHALL reduce the stored value, regardless of the
configuration that recompute runs under. "Longest streak" therefore means the longest ever
achieved, not the longest achievable under the current rules.

#### Scenario: Stricter rules do not erase a record
- **WHEN** the user raises the daily quest goal so that past days no longer qualify, and a
  recompute runs
- **THEN** the stored longest streak retains its previous value rather than dropping to the
  length recomputed under the stricter rule

#### Scenario: A genuinely longer streak still raises the record
- **WHEN** a recompute produces a streak longer than the stored longest streak
- **THEN** the stored longest streak is raised to the new value

#### Scenario: Current streak still reflects current rules
- **WHEN** a rule change causes the current streak to be recomputed to a lower value
- **THEN** the current streak reflects that lower value, and only the longest streak is
  protected from being lowered

### Requirement: Data section
The Settings screen SHALL present the historical-playtime import and its reset as data
controls, separately from the rule-configuration controls.

#### Scenario: Import presented in Settings
- **WHEN** the Settings screen is shown
- **THEN** the Steam history import control is presented there, retaining the confirmation and
  one-time behavior already specified for that control

#### Scenario: Import not presented on Home
- **WHEN** the Home screen is shown
- **THEN** it does not present the Steam history import or its reset

### Requirement: Diagnostics section
The Settings screen SHALL provide access to a diagnostics view listing recent sync runs, with each
run inspectable to see what it did and how it ended, and SHALL present request counters for the
last 24 hours, 30 days, and 365 days, split into successful and unsuccessful requests with a
per-API-route breakdown. The view SHALL render from stored records without a network call, and
SHALL NOT display credential values in any form.

#### Scenario: Opening diagnostics
- **WHEN** the user activates the diagnostics control in Settings
- **THEN** a view listing recent sync runs is shown, most recent first

#### Scenario: Inspecting a run
- **WHEN** the user selects a recorded run
- **THEN** its trigger, duration, request count, work performed, and outcome are shown

#### Scenario: Diagnostics render offline
- **WHEN** the diagnostics view is opened without network
- **THEN** it displays stored records and never blocks on a network call

#### Scenario: No records yet
- **WHEN** the diagnostics view is opened before any run has been recorded
- **THEN** it presents an empty state rather than an error or a blank screen

#### Scenario: Credentials absent from diagnostics
- **WHEN** any diagnostics view or record detail is displayed
- **THEN** no Steam API key or credential value appears, in masked form or otherwise

#### Scenario: Request counters shown
- **WHEN** the diagnostics view is opened
- **THEN** it presents the total requests for the last 24 hours, 30 days, and 365 days, each
  split into successful and unsuccessful counts

#### Scenario: Endpoint breakdown shown
- **WHEN** the user views the request counters
- **THEN** the requests of the selected window are broken down per API route, with successful and
  unsuccessful counts per route

#### Scenario: Counter window selection
- **WHEN** the user changes the counter window selector
- **THEN** the endpoint breakdown recomputes for the chosen window — 24 hours, 30 days, or 365
  days

#### Scenario: Counters empty state
- **WHEN** the diagnostics view is opened before any request has been counted
- **THEN** the counters section presents a neutral empty state rather than an error or a blank
  area

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


## ADDED Requirements

### Requirement: Removed shared games section
Settings SHALL list the family-shared games the player has removed and SHALL allow a removal to be
reversed. The section SHALL be absent when nothing has been removed.

#### Scenario: Viewing removed games
- **WHEN** the player has removed one or more family-shared games and opens Settings
- **THEN** those games are listed by name

#### Scenario: Reversing a removal
- **WHEN** the player reverses a removal
- **THEN** the game is restored as a Family Shared tracked game, appears in Library and collection
  add-game choices, and leaves the list

#### Scenario: Nothing removed
- **WHEN** no family-shared game has been removed
- **THEN** the section is not shown

#### Scenario: Removals survive a restart
- **WHEN** the app is restarted after a removal
- **THEN** the removal is still in effect and the game remains listed

### Requirement: Manual Family Shared import and Steam-data probe
Settings SHALL accept a Steam Store URL or numeric app id, safely determine whether the configured
account owns the title, import an eligible unowned game as Family Shared, and report whether Steam
returns per-player achievement data. The result SHALL distinguish unavailable data from returned
data and SHALL NOT claim that Steam supplied borrowed-game playtime.

#### Scenario: Importing an eligible borrowed game
- **WHEN** the player submits a valid Store URL or app id, `GetOwnedGames` does not contain it, and
  the Steam Store identifies it as a game
- **THEN** it is imported as Family Shared and Settings reports whether player achievements were
  returned

#### Scenario: The title is owned
- **WHEN** `GetOwnedGames` contains the submitted app id
- **THEN** Settings reports that it is owned and does not import a Family Shared row

#### Scenario: Invalid or unsafe input
- **WHEN** the input is invalid, the title is excluded, the Store does not identify it as a game,
  or a required Steam request is unavailable
- **THEN** no game is imported and Settings explains the applicable reason

#### Scenario: Steam has no player data
- **WHEN** the game is imported but `GetPlayerAchievements` returns no usable player data
- **THEN** Settings reports that result without treating it as a failure or inventing playtime

#### Scenario: Import result is prominent
- **WHEN** a manual import check completes
- **THEN** Settings presents an icon-led tonal result card with an explicit outcome headline such
  as game found or game not found, and does not rely on color alone

# app-settings

## Purpose

Defines the Settings destination: the Steam account section, sync section, editable
gamification rule configuration (and the retroactive-recompute behavior those edits
trigger), and the data-management (history import) controls.
## Requirements
### Requirement: Settings uses task-oriented groups
The top-level Settings destination SHALL present exactly four task-oriented groups: Account & sync, Gameplay, Data & privacy, and Advanced & diagnostics. Each group SHALL open a pushed Settings sub-destination and SHALL NOT add another top-level navigation item.

#### Scenario: Opening Settings
- **WHEN** the player selects the Settings top-level destination
- **THEN** the four groups are shown as the primary choices instead of one continuous list of every control

#### Scenario: Opening a Settings group
- **WHEN** the player activates a group
- **THEN** its detail destination opens with a clear title, back navigation to Settings, and all operations assigned to that group

#### Scenario: Returning to Settings
- **WHEN** the player returns from a Settings detail destination
- **THEN** the Settings overview restores its previous scroll position and current summaries

### Requirement: Existing settings operations remain reachable by intent
Every existing Settings operation SHALL remain reachable through one assigned group: account credentials, setup, sync, and updates through Account & sync; quests, live monitoring, Focus-related rules, hidden games, and Family Sharing through Gameplay; history import, offline assets, completion-data contribution, cloud presence, and backup/restore through Data & privacy; diagnostics and advanced rule constants through Advanced & diagnostics.

#### Scenario: Routine account maintenance
- **WHEN** the player needs to connect Steam, rerun setup, sync, or inspect an update
- **THEN** all of those actions are reachable from Account & sync without visiting an unrelated data or advanced screen

#### Scenario: Data and privacy maintenance
- **WHEN** the player needs to import, export, back up, restore, download offline assets, contribute completion data, or configure cloud history
- **THEN** those operations are reachable from Data & privacy with their current disclosures and confirmations preserved

#### Scenario: Advanced operations remain secondary
- **WHEN** the player opens ordinary Settings
- **THEN** diagnostics and editable engine constants are summarized but their full controls remain inside Advanced & diagnostics

### Requirement: Settings summaries communicate state and next action
Each Settings group row SHALL summarize the most important current state in plain language and identify a relevant next action without exposing credentials, tokens, endpoint details, or diagnostic internals.

#### Scenario: Account requires attention
- **WHEN** Steam credentials are missing or the latest manual sync failed
- **THEN** Account & sync communicates the problem and offers the appropriate connect or retry path

#### Scenario: Data operations are healthy
- **WHEN** no data or privacy operation requires attention
- **THEN** Data & privacy presents a quiet healthy summary instead of listing every available maintenance command

#### Scenario: Advanced status
- **WHEN** no advanced rule draft or diagnostic failure needs attention
- **THEN** Advanced & diagnostics remains visually subordinate and does not compete with routine groups

### Requirement: Settings loading preserves a stable shell
Settings SHALL show its four-group structure while locally stored state is loading and SHALL replace placeholders with resolved summaries without blanking the destination.

#### Scenario: Initial Settings load
- **WHEN** Settings has not yet resolved its local state
- **THEN** the four group rows remain identifiable with bounded placeholder summaries and no actionable control falsely claims a resolved state

### Requirement: Settings copy is externalized with locale-aware formatting
Settings overview and detail destinations SHALL use default Android string/plural resources for labels, descriptions, quantities, and formatted dates. This change adds no locale-qualified resources or translations; under a non-default locale copy SHALL fall back to the default English text while dates and quantities follow the device locale. First-level summaries SHALL use player-facing language; necessary technical identifiers SHALL appear only in the relevant detail context.

#### Scenario: Technical cloud configuration
- **WHEN** the player opens Data & privacy and proceeds into cloud-history configuration
- **THEN** endpoint and credential details remain available there, while the Settings overview describes the feature in plain language

#### Scenario: Non-default locale falls back to English copy
- **WHEN** Settings is shown under a non-default locale
- **THEN** overview and detail copy show the default English text with locale-aware date/quantity formatting without changing persisted settings or validation behavior

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

### Requirement: Completion times section
The Settings screen SHALL present a Completion times section showing the state of the applied
HowLongToBeat dataset — when its data was gathered and how many of the user's games it covers — a
control that checks for a newer dataset, and a control that produces a contribution file. The
section SHALL NOT offer any control that looks up HowLongToBeat across the library.

#### Scenario: Viewing the section
- **WHEN** the Settings screen is shown and a dataset has been applied
- **THEN** the section presents when the dataset's data was gathered and how many of the user's
  games it covers

#### Scenario: No dataset applied
- **WHEN** no dataset has ever been applied
- **THEN** the section says so and offers to obtain one, rather than presenting an error or an
  empty value

#### Scenario: Checking for a newer dataset
- **WHEN** the user activates the check control
- **THEN** a check runs immediately and the section reflects its outcome, including when the
  outcome is that the dataset is already up to date

#### Scenario: Control while a check runs
- **WHEN** a check or download is already in flight
- **THEN** the check control cannot be triggered again until it completes

#### Scenario: Check fails
- **WHEN** a check cannot reach the release service
- **THEN** the section reports that the check did not complete, remains fully usable, and continues
  to present the previously applied dataset as in effect

#### Scenario: Producing a contribution file
- **WHEN** the user activates the contribution control
- **THEN** what the file reveals is stated before any file is written, and the user chooses where it
  is written

#### Scenario: No library-wide lookup offered
- **WHEN** the Completion times section is shown
- **THEN** it offers no control that looks up HowLongToBeat across the library

#### Scenario: Each status sits with its own action
- **WHEN** the section presents more than one operation
- **THEN** each operation's status is presented in the same row as the control that triggers it

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

### Requirement: Hidden games section
Settings SHALL provide a section listing every hidden game, from which any can be unhidden
individually or all together, and from which non-game library items can be reviewed and hidden in
bulk.

#### Scenario: Listing hidden games
- **WHEN** games are hidden and the player opens the section
- **THEN** each is named, with when it was hidden

#### Scenario: Unhiding from Settings
- **WHEN** the player unhides a game from the section
- **THEN** it returns to every surface, and the resulting XP and level change is disclosed as for
  any other change with a retroactive effect

#### Scenario: Nothing hidden
- **WHEN** no game is hidden
- **THEN** the section says so rather than presenting an empty list without explanation

#### Scenario: Reviewing non-game items
- **WHEN** the library contains items the store reports as non-games and none are hidden yet
- **THEN** the section offers to review them, naming each

#### Scenario: Bulk hide confirmed from Settings
- **WHEN** the player confirms the reviewed non-game items
- **THEN** they are hidden together and appear in the hidden list

#### Scenario: Section is always reachable
- **WHEN** every game in the library has been hidden
- **THEN** the section is still reachable and still lists them

### Requirement: Cloud presence section
The Settings destination SHALL present a cloud presence section that lets the user configure an
endpoint and credential, shows whether the configuration is active and when it was last read
successfully, and lets the user remove it.

The section SHALL disclose that cloud presence adds a record of when play happened and that the
app functions fully without it, so enabling it reads as an addition rather than a repair of
something broken.

#### Scenario: Not configured
- **WHEN** the section is shown with nothing configured
- **THEN** it offers configuration and states what the feature adds
- **AND** it presents no error and no failed state

#### Scenario: Configuration is verified before it is accepted
- **WHEN** the user submits an endpoint and credential
- **THEN** the values are checked against the endpoint before being stored
- **AND** values that fail verification are not stored

#### Scenario: Account mismatch is refused with a stated cause
- **WHEN** verification succeeds but the endpoint reports a different Steam account than the one
  configured
- **THEN** the configuration is not stored
- **AND** the section states that the endpoint is polling a different account and which one the app
  expects

#### Scenario: Configured and healthy
- **WHEN** the section is shown while configured and a read has succeeded
- **THEN** it shows the active endpoint, a masked credential, and when the last successful read
  occurred

#### Scenario: Configured but failing
- **WHEN** reads have been failing
- **THEN** the section states that, and when the last successful read occurred, rather than
  presenting the configuration as healthy

#### Scenario: Removing the configuration
- **WHEN** the user removes the configuration
- **THEN** the stored credential is destroyed and the section returns to its unconfigured state
- **AND** no recorded play is removed, because this phase records none

### Requirement: Re-file history action

The cloud presence section SHALL offer a one-time action to re-file already-recorded play against a user-confirmed historical range and, separately within that confirmation, offer to date eligible pre-Backlogium owned-game play already counted by the opt-in Steam-history import. It SHALL offer exact reversal once completed. Before starting, it SHALL show the earliest retained observation or clearly say none is available, let the user explicitly choose a valid start date up to the present, and show the effective range that will be acquired. A recent-31-days default SHALL match the existing re-file's start when the user accepts it without choosing a different date. A custom start date SHALL be in the past, SHALL not precede the earliest retained observation's calendar date in the user's timezone, and SHALL not be after the current date. An unavailable or stale bound SHALL be refreshed or rejected before acquisition rather than silently clamped to an undisclosed date.

When the player opts to date pre-Backlogium imported play, Settings SHALL ask them to explicitly confirm a pre-data cutoff date/time, distinct from the selected cloud start date, with timezone and exact effective instant visible. The cutoff SHALL be after the effective start and no later than the fixed end; Settings SHALL not infer a first-sync timestamp from the first recorded session. The player MAY decline the imported-play transfer while still re-dating existing sessions. If the separate Steam-history import was never performed, Settings SHALL not claim that cloud presence can create owned-game minutes. The action SHALL state plainly what it changes and what it does not: dates, daily quests and streaks may change; an opted-in imported-play transfer moves *already-counted* minutes from imported to tracked/dated sessions, but experience, levels, the combined credited minutes, and each game's Steam total do not change. It SHALL say that the poller does not prove every minute of old Steam history or precise Steam accounting at each time. During acquisition, Settings SHALL show the selected range/cutoff, fixed end, completed pages/transitions, and whether a bounded batch needs explicit continuation. Completion SHALL show both the actual covered range and the counts of sessions/dates affected, minutes newly dated and minutes left imported; a completed zero-change re-file SHALL still show its range. While a completed re-file is applied, the section SHALL show its saved range/cutoff and require reversal before applying another or resetting the separate Steam-history import.

Stating the limit is load-bearing rather than polite. The natural expectation of an action that recovers a month of more accurate history is that progression improves, and it will not — only its placement in time and its tracked/imported classification do. An action that lets that expectation stand would read as broken when it worked correctly.

#### Scenario: Offered while configured
- **WHEN** the cloud section is shown while configured and the action has not been run
- **THEN** the action is offered, with its effect, limit, available beginning, selected range and optional imported-play transfer explained

#### Scenario: Not offered while unconfigured
- **WHEN** no cloud endpoint is configured
- **THEN** the action is not offered

#### Scenario: Default and custom start choices
- **WHEN** a user confirms the untouched recent-31-days choice
- **THEN** acquisition uses the same rolling recent-window beginning as today's re-file
- **WHEN** the user instead selects a valid earlier calendar date
- **THEN** the confirmation shows that date and the effective time boundary, including the earliest retained observation if it truncates the selected date

#### Scenario: Invalid or unavailable start
- **WHEN** the selected start is in the future, is not a past date for a custom choice, precedes the earliest retained observation's local date, or the available beginning is unknown
- **THEN** the action does not start and Settings explains the constraint or lookup failure

#### Scenario: Pre-data cutoff is explicitly chosen
- **WHEN** an imported Steam-history balance exists and the player chooses to date part of it
- **THEN** Settings requires confirmation of a cutoff within the chosen range and shows the exact time/zone before starting
- **AND** it explains that the cutoff is the player's estimate of when Backlogium began recording, not a detected first-sync timestamp

#### Scenario: Player only wants date correction
- **WHEN** the player does not choose pre-data dating, or has no Steam-history import
- **THEN** the selected range may still re-date existing sessions without creating new owned-game minutes from the poller

#### Scenario: Completed state
- **WHEN** the action has completed
- **THEN** the section reflects that it has run, shows its effective applied range and optional pre-data cutoff, and offers its reversal instead

#### Scenario: The limit is stated before it runs
- **WHEN** the action is presented
- **THEN** it states that experience, levels and total playtime are unchanged by it

#### Scenario: Outcome reported
- **WHEN** the action completes, even if no session changed
- **THEN** the section reports the selected and actually covered range, how many existing sessions were re-filed, how many imported minutes became dated tracked sessions, how many remain imported, and how many dates were affected

#### Scenario: Partial batch is not completion
- **WHEN** a batch reaches its page or time limit, goes offline, or fails before reaching the fixed end
- **THEN** Settings shows progress and offers an explicit retry or continue action without claiming that the re-file was applied

#### Scenario: Reversal returns the previous attribution
- **WHEN** the user reverses a completed re-filing
- **THEN** original session attribution and transferred imported balances return to their pre-action classification without erasing later ordinary play, and the action is offered again with a new start-date/cutoff choice

#### Scenario: Resetting the Steam-history import while a transfer is applied
- **WHEN** the player requests the separate Steam-history import reset before reversing a cloud pre-data transfer
- **THEN** Settings explains that they must undo the cloud re-file first instead of silently losing or double-counting transferred minutes

### Requirement: Disclose original transition-only timing estimates

Historical re-file confirmation SHALL explain that original v1 transition-only logs estimate timing between a game start and the next recorded change without proving uninterrupted polling. Newer logs SHALL continue using their recorded coverage and gap checks.

#### Scenario: Reviewing a historical transfer
- **WHEN** the player reviews an imported-minute historical re-file
- **THEN** the confirmation distinguishes legacy game-change timing estimates from newer coverage checks and retains the per-game imported-balance ceiling

### Requirement: Cloud catch-up frequency can be understood and bounded

While the reader is configured, Settings SHALL offer Automatic as the initial routine cloud catch-up policy, selected when a reader is first verified and no policy has been persisted, a choice of 12-hour, daily, or 48-hour minimum gaps, and an explicit Off / Manual only choice. Automatic SHALL combine a daily background opportunity with play-end opportunities under a shared 12-hour minimum gap. A chosen cadence SHALL use its selected minimum gap across both routine triggers. Off / Manual only SHALL disable those routine triggers without removing the reader or disabling Read now and accuracy-driven placement reads. The section SHALL state the initial Automatic behavior and explain that its cadence choices limit routine phone reads, not the cloud poller's own minute-by-minute observation or the total number of all cloud requests. It SHALL show the last routine attempt and its outcome and the last successful read; a next eligible opportunity SHALL appear only for an enabled cadence when determinable and SHALL NOT be described as a guaranteed execution time.

#### Scenario: First verified reader
- **WHEN** a reader is first verified and no routine policy has been persisted
- **THEN** Automatic is selected and the meaning of its routine catch-up is available in Settings

#### Scenario: Existing reader upgraded without a policy
- **WHEN** an app starts or upgrades with an already verified reader that has no persisted routine policy
- **THEN** Automatic is initialized without requiring the player to verify the reader again
- **AND** the initial verification-order watermark is seeded for routine admission
- **AND** existing cloud read and ingest positions are preserved

#### Scenario: Replacing a verified reader
- **WHEN** the configured reader is replaced by an endpoint successfully verified for the active Steam account
- **THEN** the selected cadence and shared minimum-gap cooldown are preserved
- **AND** routine work is scheduled for the replacement only when that existing cooldown allows it

#### Scenario: Choosing a slower cadence
- **WHEN** the player selects daily or 48 hours
- **THEN** that minimum gap is persisted for routine background and play-end reads and the displayed eligibility reflects it

#### Scenario: Connected reader used manually only
- **WHEN** the player selects Off / Manual only for a configured reader
- **THEN** Settings keeps the reader connected and Read now available, indicates that routine background and play-end catch-up are off, and does not show a next routine eligibility time

#### Scenario: Manual-only choice survives restart and replacement
- **WHEN** the player restarts the app or successfully replaces the reader for the same Steam account after choosing Off / Manual only
- **THEN** that choice remains selected rather than being interpreted as a missing policy and reset to Automatic

#### Scenario: Re-enable a cadence
- **WHEN** the player switches from Off / Manual only to an enabled cadence
- **THEN** the chosen cadence is displayed without promising an immediate read or resetting the last admitted attempt

#### Scenario: Existing placement needs a read
- **WHEN** the player selects a slower routine cadence
- **THEN** Settings explains that a Steam sync may still read cloud evidence to place delayed play accurately

#### Scenario: Routine read is partial or failed
- **WHEN** an attempt stops before reaching the end of unread history or fails
- **THEN** Settings reports partial progress or failure without describing the reader as caught up

#### Scenario: Unconfigured or offline
- **WHEN** no cloud reader is configured
- **THEN** no routine frequency control or cloud error is shown
- **AND** Settings remains usable offline from local state

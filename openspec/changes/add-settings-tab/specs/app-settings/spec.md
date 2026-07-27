## ADDED Requirements

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

### Requirement: Sync section
The Settings screen SHALL present the time of the last successful sync and a control that
triggers an immediate manual sync.

#### Scenario: Viewing last sync
- **WHEN** the Settings screen is shown
- **THEN** it displays when the last sync completed

#### Scenario: Triggering a manual sync
- **WHEN** the user activates the manual sync control
- **THEN** a one-time poll is enqueued and the app reflects the updated state when it completes

#### Scenario: Sync control while a sync runs
- **WHEN** a manual sync is already in flight
- **THEN** the control cannot be triggered again until that sync completes

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

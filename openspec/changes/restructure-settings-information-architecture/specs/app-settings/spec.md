## ADDED Requirements

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

### Requirement: Settings copy follows Android locale resources
Settings overview and detail destinations SHALL use Android resources for labels, descriptions, quantities, and formatted dates. First-level summaries SHALL use player-facing language; necessary technical identifiers SHALL appear only in the relevant detail context.

#### Scenario: Technical cloud configuration
- **WHEN** the player opens Data & privacy and proceeds into cloud-history configuration
- **THEN** endpoint and credential details remain available there, while the Settings overview describes the feature in plain language

#### Scenario: Supported non-default locale
- **WHEN** Settings is shown under a supported non-default locale
- **THEN** overview and detail copy resolve from that locale without changing persisted settings or validation behavior

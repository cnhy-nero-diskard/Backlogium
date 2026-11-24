# App UI Delta

## ADDED Requirements

### Requirement: Game notifications open validated game detail
An explicit game-targeted notification tap SHALL navigate to its game on a cold or warm launch once the current account, setup, and game eligibility can be checked.

#### Scenario: Cold launch
- **WHEN** a valid account-scoped tap launches the app before navigation is ready
- **THEN** normal startup completes and the tap opens the requested game with normal Back behavior

#### Scenario: Warm launch
- **WHEN** the app receives a second tap for the already-open game
- **THEN** the existing detail is reused without adding a duplicate destination

#### Scenario: Invalid or obsolete target
- **WHEN** the tap is malformed or belongs to a different account or a hidden, removed, or inaccessible game
- **THEN** the existing safe destination remains usable and no other game's detail is opened

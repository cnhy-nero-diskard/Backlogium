## ADDED Requirements

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection, each card
rendering the collection's name and its mode-specific banner. Tapping a collection card SHALL open the
collection's management screen. The collections section SHALL render from locally stored state so it is
usable offline, and SHALL present an empty state when no collections exist. The collections section SHALL
NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces on Home.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection, displaying its name and its mode-specific banner

#### Scenario: Opening a collection
- **WHEN** the user taps a collection card on Home
- **THEN** the collection's management screen is opened

#### Scenario: No collections
- **WHEN** the Home screen is shown and no collections exist
- **THEN** the collections section presents an empty state rather than an empty list

#### Scenario: Offline rendering
- **WHEN** the Home screen is shown without network
- **THEN** the collections section renders from the last stored state without blocking

#### Scenario: Existing Home surfaces preserved
- **WHEN** the collections section is added to Home
- **THEN** the level, XP, daily-quest, streak, and now-playing surfaces remain present and unchanged

### Requirement: Collection management screen
The system SHALL provide a collection management screen, reached as a pushed sub-destination from a Home
collection card, where the user can create a collection, choose its mode, name it, add and remove games,
set a target date for deadline-goal collections, reorder members for ordered-queue collections, and delete
the collection. The screen SHALL render from locally stored state.

#### Scenario: Creating a collection
- **WHEN** the user creates a new collection with a name and a mode
- **THEN** the collection is persisted and appears on the Home collections section

#### Scenario: Adding games to a collection
- **WHEN** the user adds games to a collection from the management screen
- **THEN** those games become members of the collection

#### Scenario: Removing games from a collection
- **WHEN** the user removes a game from a collection
- **THEN** that game is no longer a member of the collection

#### Scenario: Setting a deadline
- **WHEN** the user sets or changes a target date on a deadline-goal collection
- **THEN** the collection's banner reflects the updated countdown

#### Scenario: Reordering an ordered queue
- **WHEN** the user reorders members of an ordered-queue collection
- **THEN** the sequence is persisted and the next-game surface updates

#### Scenario: Deleting a collection
- **WHEN** the user deletes a collection
- **THEN** the collection and its memberships are removed, and it no longer appears on Home

#### Scenario: Empty collection on the management screen
- **WHEN** a collection has no members
- **THEN** the management screen presents an empty state with a control to add games

#### Scenario: Target date only for deadline mode
- **WHEN** the user is editing a collection whose mode is not deadline goal
- **THEN** no target date field is offered

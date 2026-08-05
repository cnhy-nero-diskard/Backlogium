## MODIFIED Requirements

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection, each card
rendering the collection's name and its mode-specific banner. Cards SHALL use an elevated surface
distinct from the Home background and remain visually separated from
one another and styled by mode — goal modes present a progress surface, deadline mode additionally
presents its countdown, ordered-queue mode presents its next game — and a collection with a stored
accent SHALL tint both its card surface and accent affordances with that accent. Tapping a collection card SHALL open the collection's
management screen. The collections section SHALL render from locally stored state so it is
usable offline, and SHALL present an empty state when no collections exist. The collections section SHALL
NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces on Home.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection, displaying its name and its mode-specific banner

#### Scenario: Cards visually separated
- **WHEN** two or more collection cards are shown
- **THEN** consecutive cards are separated by visible spacing

#### Scenario: Mode-specific card surfaces
- **WHEN** a collection's mode is completion goal, deadline goal, or ordered queue
- **THEN** its card presents the mode's structured surface — progress for goal modes, countdown for
  deadline mode, next game for ordered queue — rather than a text line alone

#### Scenario: Completion-goal trophy summary
- **WHEN** a collection's mode is completion goal and achievement counts exist for one or more
  members
- **THEN** its Home card shows aggregate unlocked out of total trophies and the remaining count

#### Scenario: Accent tint applied
- **WHEN** a collection has a stored accent
- **THEN** its card surface and accent affordances use a low-opacity tint from that accent while
  its text remains legible

#### Scenario: Cards remain compact
- **WHEN** the Home screen shows multiple collection cards
- **THEN** each card uses compact internal padding and consecutive cards have visible spacing without
  excessive vertical gaps

#### Scenario: Default styling without accent
- **WHEN** a collection has no stored accent
- **THEN** its card presents the default neutral styling

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
collection card, where the user can create a collection, choose its mode and an accent from the app's
palette, name it, add and remove games, filter the pool of addable games with a search, set a target
date for deadline-goal collections, reorder members and mark them done for ordered-queue collections,
and delete the collection. The save action SHALL remain reachable regardless of the form's scroll
position. The screen SHALL render from locally stored state.

#### Scenario: Creating a collection
- **WHEN** the user creates a new collection with a name and a mode
- **THEN** the collection is persisted and appears on the Home collections section

#### Scenario: Save reachable at any scroll position
- **WHEN** the management screen is shown
- **THEN** the save action is presented as a floating control that remains reachable while the form
  scrolls

#### Scenario: Save blocked without a name
- **WHEN** the collection name is blank
- **THEN** the save action is not usable

#### Scenario: Adding games to a collection
- **WHEN** the user adds games to a collection from the management screen
- **THEN** those games become members of the collection

#### Scenario: Filtering the add-games pool
- **WHEN** the user enters a search query on the management screen
- **THEN** only library games matching the query and not already members are offered for adding

#### Scenario: Search matches nothing
- **WHEN** the search query matches no addable game
- **THEN** the list presents a no-match state beneath the search field and the field remains available
  to clear

#### Scenario: Removing games from a collection
- **WHEN** the user removes a game from a collection
- **THEN** that game is no longer a member of the collection

#### Scenario: Choosing an accent
- **WHEN** the user selects an accent on the management screen
- **THEN** only palette-compatible tokens are offered and the selection is persisted on the collection

#### Scenario: Setting a deadline
- **WHEN** the user sets or changes a target date on a deadline-goal collection
- **THEN** the collection's banner reflects the updated countdown

#### Scenario: Reordering an ordered queue
- **WHEN** the user reorders members of an ordered-queue collection
- **THEN** the sequence is persisted and the next-game surface updates

#### Scenario: Marking a queue member done
- **WHEN** the user marks a member of an ordered-queue collection as done
- **THEN** the member stays listed with its name struck through and its card greyed, and the next-game
  surface skips it

#### Scenario: Deleting a collection
- **WHEN** the user deletes a collection
- **THEN** the collection and its memberships are removed, and it no longer appears on Home

#### Scenario: Empty collection on the management screen
- **WHEN** a collection has no members
- **THEN** the management screen presents an empty state with a control to add games

#### Scenario: Target date only for deadline mode
- **WHEN** the user is editing a collection whose mode is not deadline goal
- **THEN** no target date field is offered

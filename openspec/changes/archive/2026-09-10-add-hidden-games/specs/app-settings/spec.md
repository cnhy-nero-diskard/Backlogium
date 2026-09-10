## ADDED Requirements

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

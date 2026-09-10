## ADDED Requirements

### Requirement: Hiding is reachable from a game's own surface
Game detail SHALL offer to hide the game, presenting the disclosure of what hiding will do before
it takes effect. The action SHALL NOT be offered for a game that is already hidden, since such a
game is not reachable.

#### Scenario: Hiding from game detail
- **WHEN** the player chooses to hide a game from its detail surface
- **THEN** the effect is disclosed and the game is hidden only on confirmation

#### Scenario: Returning after hiding
- **WHEN** a game is hidden from its own detail surface
- **THEN** the player is returned to where they came from, and the game is absent there

#### Scenario: Declining
- **WHEN** the player declines the confirmation
- **THEN** they remain on the game's detail surface and nothing has changed

### Requirement: Hidden games leave no gaps or placeholders
Surfaces SHALL present hidden games as though the library never contained them, without
placeholders, counts of omitted items, or gaps in ordering.

#### Scenario: Library ordering
- **WHEN** a game in the middle of a sorted list is hidden
- **THEN** the list closes over its position with no placeholder or gap

#### Scenario: Collection contents
- **WHEN** a collection contains a hidden game
- **THEN** its contents present the remaining members with no indication that one was omitted

#### Scenario: Totals reflect the visible library
- **WHEN** any count, average, or completion figure is presented
- **THEN** it is consistent with the games the player can see

#### Scenario: A collection whose members are all hidden
- **WHEN** every member of a custom collection is hidden
- **THEN** the collection presents as empty rather than as containing invisible members

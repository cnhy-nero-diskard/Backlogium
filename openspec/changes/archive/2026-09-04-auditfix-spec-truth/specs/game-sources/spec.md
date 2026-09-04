# game-sources

## MODIFIED Requirements

### Requirement: A shared game can be removed and stays removed
The player SHALL be able to remove a family-shared game. A removed game SHALL NOT be re-admitted
when it is next played, and the player SHALL be able to reverse a removal. Reversing a removal
SHALL restore the game immediately, without waiting for it to be observed being played again.

#### Scenario: Removing a shared game
- **WHEN** the player removes a family-shared game
- **THEN** it no longer appears among tracked games

#### Scenario: Removal survives further play
- **WHEN** a removed game is played again
- **THEN** it is not re-admitted and no admission notification is issued

#### Scenario: Reversing a removal
- **WHEN** the player reverses a removal
- **THEN** the game is immediately tracked again as family-shared, appears in the library and
  in collection choices, and no longer appears in the removed-games list

#### Scenario: Reversal does not wait for play
- **WHEN** the player reverses a removal and the game is not currently being played
- **THEN** it is still restored, because the player's reversal is the admission decision and
  requiring a future observation would leave the setting looking inert

#### Scenario: Owned games are unaffected
- **WHEN** removal is considered for a game whose source is owned
- **THEN** it is not offered, because the game's presence in the library is not the app's to decide

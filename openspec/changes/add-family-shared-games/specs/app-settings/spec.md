## ADDED Requirements

### Requirement: Removed shared games section
Settings SHALL list the family-shared games the player has removed and SHALL allow a removal to be
reversed. The section SHALL be absent when nothing has been removed.

#### Scenario: Viewing removed games
- **WHEN** the player has removed one or more family-shared games and opens Settings
- **THEN** those games are listed by name

#### Scenario: Reversing a removal
- **WHEN** the player reverses a removal
- **THEN** the game is eligible for admission again the next time it is observed being played, and
  it leaves the list

#### Scenario: Nothing removed
- **WHEN** no family-shared game has been removed
- **THEN** the section is not shown

#### Scenario: Removals survive a restart
- **WHEN** the app is restarted after a removal
- **THEN** the removal is still in effect and the game remains listed

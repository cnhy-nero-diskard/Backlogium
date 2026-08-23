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

## MODIFIED Requirements

### Requirement: Game detail screen with achievements
The system SHALL provide a game detail screen, reachable by selecting a game from the
Library, that lists that game's achievements with each achievement's unlock state, rarity
tier, and the XP it contributes, using its display name and icon when available. The screen
SHALL also show the game's current Steam concurrent-player count when available, and SHALL
show no such line when it is not.

#### Scenario: Opening a game's detail
- **WHEN** the user selects a game in the Library
- **THEN** a detail screen for that game is shown listing its achievements

#### Scenario: Achievement rarity and XP shown
- **WHEN** the detail screen shows an unlocked achievement that has a rarity snapshot
- **THEN** it displays the achievement's rarity tier and the XP it contributes

#### Scenario: Locked achievement shown without XP
- **WHEN** the detail screen shows a locked achievement
- **THEN** it is displayed as locked and shows no XP contribution

#### Scenario: Game without achievement data
- **WHEN** the user opens the detail for a game that has no stored achievements
- **THEN** the screen indicates there are no achievements to show rather than appearing broken

#### Scenario: Current player count shown
- **WHEN** the detail screen opens and Steam reports a current player count for the game
- **THEN** the summary displays that count

#### Scenario: Current player count unavailable
- **WHEN** the detail screen opens and no current player count is available (lookup failed or
  Steam has none for that app)
- **THEN** the summary shows no player-count line, rather than a zero or a placeholder

#### Scenario: Player count does not block the rest of the summary
- **WHEN** the detail screen opens and the player-count lookup has not yet resolved
- **THEN** the rest of the summary and the achievement list render immediately from local data,
  and the player count appears afterward if and when it resolves

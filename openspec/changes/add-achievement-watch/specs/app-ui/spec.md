## ADDED Requirements

### Requirement: Unlock feedback uses one claimed presentation surface
The app SHALL present a claimed foreground unlock group accessibly with game identity, achievement names/count, and an action to the specific game detail. A background group SHALL use a notification carrying the same destination. Notification permission, alert preferences, current account, and hidden-game policy SHALL be checked before dispatch; suppressed groups SHALL NOT later flood the app.

#### Scenario: Several achievements
- **WHEN** a foreground group contains multiple unlocks
- **THEN** one presentation identifies the game and all unlocks/count without a burst of alerts

#### Scenario: Background game destination
- **WHEN** the player taps a background unlock notification
- **THEN** the app opens that game's detail after ordinary configured-account routing

#### Scenario: Unavailable notification permission
- **WHEN** a background group cannot be presented because permission or alerts are disabled
- **THEN** it is suppressed durably while achievement persistence remains successful

#### Scenario: Reduced motion and accessibility
- **WHEN** a group is presented with reduced motion or TalkBack
- **THEN** its content/actions remain understandable without animation or color alone

### Requirement: Achievement browsing explains catalogue and progress availability
Game detail SHALL show cached achievement objectives before play, load missing data without blocking the summary, and distinguish loading, unavailable data with retry, confirmed no achievements, and unavailable personal progress. Catalogue-only objectives SHALL NOT be labelled as confirmed locked or used to claim completion or earned XP. Both entry points SHALL expose the same behaviour accessibly.

#### Scenario: Unplayed game with objectives but no player stats
- **WHEN** game detail has a catalogue but no confirmed personal achievement progress
- **THEN** objectives remain visible with personal progress identified as unavailable and no invented unlocked/total completion or earned XP

#### Scenario: Offline without cache
- **WHEN** a game is opened offline with no stored catalogue or player achievements
- **THEN** the summary remains usable and the achievement area explains unavailable data with a retry action rather than claiming the game has no achievements

#### Scenario: Failure with cached content
- **WHEN** a detail hydration fails with last-good objectives or player rows stored
- **THEN** those rows stay visible and the screen explains that fresh data could not be loaded

#### Scenario: Catalogue is confirmed empty
- **WHEN** a successful complete catalogue has no objectives and no retained player achievements contradict it
- **THEN** the achievement area states that the game has no achievements

#### Scenario: Collection overlay and accessibility
- **WHEN** the same unplayed game is opened from a collection with TalkBack enabled
- **THEN** catalogue availability, progress availability, and retry actions are understandable as in the Library entry point

## MODIFIED Requirements

### Requirement: Game detail screen with achievements
The system SHALL provide a game detail screen, reachable by selecting a game from the
Library or by selecting a game tile in a collection overview, that lists that game's
achievements with each achievement's known unlock state, available rarity information, and the XP it contributes,
using its display name and icon when available. The screen SHALL also show the game's current
Steam concurrent-player count when available, and SHALL show no such line when it is not. The
screen SHALL present the game's summary above the achievement list, so a game with no
achievement data still shows its own information rather than only an empty state. The screen
SHALL present the same content regardless of which entry point opened it.

#### Scenario: Opening a game's detail
- **WHEN** the user selects a game in the Library
- **THEN** a detail screen for that game is shown listing its achievements

#### Scenario: Opening a game's detail from a collection
- **WHEN** the user selects a game tile in a collection overview
- **THEN** a detail screen for that game is shown listing its achievements, with the same
  summary and achievement content the Library entry point produces

#### Scenario: Achievement rarity and XP shown
- **WHEN** the detail screen shows an unlocked achievement that has a rarity snapshot
- **THEN** it displays the achievement's rarity tier and the XP it contributes

#### Scenario: Locked achievement shown without XP
- **WHEN** the detail screen shows a locked achievement
- **THEN** it is displayed as locked and shows no XP contribution

#### Scenario: Game without achievement data
- **WHEN** the user opens the detail for a game that has no stored achievements
- **THEN** the game's summary is still shown while targeted catalogue hydration proceeds when online; the achievement area distinguishes loading, unavailable data with retry, and confirmed no achievements

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

### Requirement: Achievement surfaces follow what Steam reports
Where Steam reports achievement progress for a family-shared game, the existing achievement,
rarity, and standing surfaces SHALL present it as for any other game. Where neither a catalogue nor player achievement data is available, the game SHALL be presented without an empty or zeroed progress surface. A positive catalogue SHALL remain browsable when personal progress is unavailable, without inventing completion, rarity standing, or earned XP.

#### Scenario: Achievements available for a shared game
- **WHEN** Steam reports achievement progress for a family-shared game
- **THEN** achievements, rarity tiers, and rarity standing are presented as for an owned game

#### Scenario: No achievement data
- **WHEN** neither catalogue nor player achievement data is available for a family-shared game
- **THEN** no achievement surface is shown for it, and no empty or zeroed surface appears in its
  place

#### Scenario: Rarity XP unaffected
- **WHEN** achievements are reported for a family-shared game and unlocked
- **THEN** they contribute rarity-tiered XP on the same terms as an owned game's

#### Scenario: Shared catalogue without personal progress
- **WHEN** Steam supplies objectives for a shared game but no usable player progress
- **THEN** game detail shows the catalogue with personal progress unavailable and no fabricated completion or XP

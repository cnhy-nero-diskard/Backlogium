## ADDED Requirements

### Requirement: Game summary section
The game detail screen SHALL present a summary of the game above its achievement list, showing the
game's art, its playtime, its known HowLongToBeat completion lengths, its achievement completion,
and its XP contribution.

#### Scenario: Viewing the summary
- **WHEN** the game detail screen is opened
- **THEN** a summary section above the achievement list shows the game's art, playtime, achievement
  completion, and XP contribution

#### Scenario: HowLongToBeat lengths shown when known
- **WHEN** the game has resolved HowLongToBeat data
- **THEN** the summary presents its known completion lengths

#### Scenario: HowLongToBeat data absent
- **WHEN** the game has no HowLongToBeat data
- **THEN** the summary omits completion lengths rather than showing empty or zero values

#### Scenario: Imported history distinguished
- **WHEN** a game's playtime includes imported historical playtime
- **THEN** the summary distinguishes tracked playtime from imported playtime

#### Scenario: XP contribution consistent with the Library
- **WHEN** the summary shows the game's XP contribution
- **THEN** it is the same value the Library shows for that game

### Requirement: Achievement sorting
The game detail screen SHALL let the user sort achievements by date achieved or by rarity, and SHALL
group locked achievements after unlocked ones in both orders.

#### Scenario: Default order
- **WHEN** the game detail screen is opened
- **THEN** achievements are ordered by date achieved, most recent first

#### Scenario: Sorting by rarity
- **WHEN** the user sorts by rarity
- **THEN** achievements are ordered from rarest to most common

#### Scenario: Locked achievements grouped last
- **WHEN** a game has both unlocked and locked achievements
- **THEN** unlocked achievements are listed first in the chosen order, followed by locked ones

#### Scenario: Sort not persisted
- **WHEN** the user leaves the screen and returns
- **THEN** the default order is applied again

### Requirement: Achievement unlock rate
The game detail screen SHALL show, on each achievement, the share of players who have unlocked it,
using the same percentage that determined that achievement's rarity tier so the two never disagree.

#### Scenario: Rate shown for an unlocked achievement
- **WHEN** an unlocked achievement has a stored rarity snapshot
- **THEN** its row displays that snapshot as the share of players who have unlocked it, consistent
  with the rarity tier shown on the same row

#### Scenario: Rate shown for a locked achievement
- **WHEN** a locked achievement has a known global unlock percentage
- **THEN** its row displays that percentage as the share of players who have unlocked it

#### Scenario: Rate unknown
- **WHEN** an achievement has neither a rarity snapshot nor a known global unlock percentage
- **THEN** its row displays no unlock rate rather than showing a zero or placeholder value

#### Scenario: Rate agrees with the rarity sort
- **WHEN** achievements are sorted by rarity
- **THEN** the order follows the same percentages the rows display

### Requirement: Achievement descriptions
The game detail screen SHALL show each achievement's description beneath its name when one is
known, and SHALL indicate when an achievement is hidden by Steam rather than showing empty space.

#### Scenario: Description shown
- **WHEN** an achievement has a stored description
- **THEN** it is displayed beneath the achievement's name

#### Scenario: Description not yet available
- **WHEN** an achievement has no stored description
- **THEN** the row displays the achievement's name without a description and without an error or
  placeholder text

#### Scenario: Hidden achievement
- **WHEN** an achievement is hidden by Steam and not yet unlocked
- **THEN** the row indicates that the achievement is hidden

#### Scenario: Hidden achievement once unlocked
- **WHEN** a hidden achievement has been unlocked and Steam supplies its description
- **THEN** the description is displayed normally

## MODIFIED Requirements

### Requirement: Game detail screen with achievements
The system SHALL provide a game detail screen, reachable by selecting a game from the
Library, that lists that game's achievements with each achievement's unlock state, rarity
tier, and the XP it contributes, using its display name and icon when available. The screen
SHALL present the game's summary above the achievement list, so a game with no achievement data
still shows its own information rather than only an empty state.

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
- **THEN** the game's summary is still shown, and the achievement area indicates there are no
  achievements to show rather than appearing broken

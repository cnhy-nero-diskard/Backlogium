## ADDED Requirements

### Requirement: Recency badge on game surfaces
Where a game carries a recency state, the system SHALL present that state as a symbol on the game's
Library row or grid cell, on its detail screen, and on Home's game surfaces. The symbol SHALL be
shown at every display density, SHALL identify its state to accessibility services by name, and
SHALL NOT displace or obscure the currently-playing signal or the selection indicator.

#### Scenario: Badge on a Library row
- **WHEN** the Library shows a game carrying a recency state as a row
- **THEN** the row presents that state's symbol

#### Scenario: Badge in a grid cell
- **WHEN** the Library shows a game carrying a recency state in a grid
- **THEN** the cell presents that state's symbol

#### Scenario: Badge survives the densest grid
- **WHEN** a game carrying a recency state is shown at the densest display density
- **THEN** its symbol is still presented

#### Scenario: Badge on game detail
- **WHEN** the detail screen is opened for a game carrying a recency state
- **THEN** that state's symbol is presented in the screen's header

#### Scenario: Badge on Home
- **WHEN** Home presents a game carrying a recency state
- **THEN** that state's symbol is presented on that game's surface

#### Scenario: Game with no state
- **WHEN** a game carries no recency state
- **THEN** no symbol is presented and its layout is otherwise unchanged

#### Scenario: One symbol at a time
- **WHEN** a game is presented on any surface
- **THEN** at most one recency symbol is shown

#### Scenario: Symbol is named
- **WHEN** a recency symbol is reached by an accessibility service
- **THEN** the state it represents is announced by name

#### Scenario: Coexistence with selection
- **WHEN** a badged game is shown in a grid while selection mode is active
- **THEN** both the selection indicator and the recency symbol are visible and neither obscures the
  other

#### Scenario: Coexistence with currently-playing
- **WHEN** a badged game is currently being played
- **THEN** the currently-playing signal remains fully legible

### Requirement: Newly acquired games banner on Home
Home SHALL present the announcement of newly acquired games as a non-modal banner reporting how many
arrived and naming at least one, offering an action that opens the Library and an action that
dismisses it. Home SHALL remain usable while the banner is shown.

#### Scenario: Banner shown after an acquisition
- **WHEN** Home is shown while an unexpired, undismissed acquisition announcement exists
- **THEN** a banner reports how many games arrived and names at least one of them

#### Scenario: Home remains usable
- **WHEN** the banner is shown
- **THEN** the rest of Home can still be scrolled and interacted with

#### Scenario: Viewing the games
- **WHEN** the user activates the banner's view action
- **THEN** the Library is opened

#### Scenario: Dismissing the banner
- **WHEN** the user dismisses the banner
- **THEN** it is removed and is not shown again for that set of games

#### Scenario: Banner absent when nothing was acquired
- **WHEN** Home is shown and no unexpired announcement exists
- **THEN** no banner is presented and Home's layout is unchanged

#### Scenario: Many games acquired
- **WHEN** more games arrived than the banner names individually
- **THEN** it names some and reports the number of remaining ones

## MODIFIED Requirements

### Requirement: Game summary section
The game detail screen SHALL present a summary of the game above its achievement list, showing the
game's art, its playtime, its known HowLongToBeat completion lengths, its achievement completion,
its XP contribution, and when it was last played. The summary SHALL offer a link to the game's Steam
store page, presented directly below the summary's own content, which opens that page outside the
app.

#### Scenario: Viewing the summary
- **WHEN** the game detail screen is opened
- **THEN** a summary section above the achievement list shows the game's art, playtime, achievement
  completion, XP contribution, and when it was last played

#### Scenario: Last played shown when known
- **WHEN** the game has a known last-played time
- **THEN** the summary presents when it was last played

#### Scenario: Game never played
- **WHEN** the game has no recorded playtime
- **THEN** the summary states that it has never been played, rather than showing an empty or unknown
  date

#### Scenario: Played but date unknown
- **WHEN** the game has recorded playtime but no known last-played time
- **THEN** the summary states that the date is unknown, distinctly from stating it was never played

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

#### Scenario: Opening the game on Steam
- **WHEN** the user selects the Steam link below the summary
- **THEN** that game's Steam store page is opened outside the app, and the game detail screen is left
  as it was so returning to the app resumes where the user left off

#### Scenario: Steam link identifies the game
- **WHEN** the summary's Steam link is presented
- **THEN** it targets the store page for the game being shown, not a generic store destination

#### Scenario: Steam link independent of other data
- **WHEN** the game has no HowLongToBeat data, no achievements, and no player count
- **THEN** the Steam link is still presented, since it depends only on the game's identity

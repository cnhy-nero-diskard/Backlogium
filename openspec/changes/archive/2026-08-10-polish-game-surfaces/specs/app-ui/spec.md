## MODIFIED Requirements

### Requirement: Game summary section
The game detail screen SHALL present a summary of the game above its achievement list, showing the
game's art, its playtime, its known HowLongToBeat completion lengths, its achievement completion,
and its XP contribution. The summary SHALL offer a link to the game's Steam store page, presented
directly below the summary's own content, which opens that page outside the app.

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

## ADDED Requirements

### Requirement: Circular game thumbnails in compact rows
Where games are represented as small thumbnails in a horizontal row rather than as list rows, the
system SHALL render those thumbnails as circles. This SHALL apply to the member thumbnails on Home's
collection cards and to the game thumbnails on History day tiles. Achievement icons SHALL remain
non-circular, so that a row of games and a row of achievements are distinguishable at a glance
without reading either.

Full-size game icons in list rows, game detail, and the most-played list SHALL be unaffected and
SHALL keep their existing shape.

#### Scenario: Collection teaser thumbnails are circular
- **WHEN** a Home collection card shows member thumbnails
- **THEN** each thumbnail is rendered as a circle

#### Scenario: History day thumbnails are circular
- **WHEN** a History day tile shows game thumbnails
- **THEN** each thumbnail is rendered as a circle

#### Scenario: Achievement icons stay distinguishable
- **WHEN** a surface shows both game thumbnails and achievement icons
- **THEN** the achievement icons are not circular, so the two rows are distinguishable by shape alone

#### Scenario: List rows unaffected
- **WHEN** a game is shown as a full list row, in game detail, or in the most-played list
- **THEN** its icon keeps its existing non-circular shape

#### Scenario: Thumbnail without artwork
- **WHEN** a game thumbnail has no artwork or its artwork fails to load
- **THEN** its themed fallback is rendered in the same circular shape rather than reverting to a
  square

### Requirement: History day game thumbnails
A History day tile SHALL show thumbnails of the games played on that day, in a horizontal row, so a
day can be identified without expanding it. The row SHALL show a bounded number of thumbnails and
SHALL indicate how many further games the day holds, following the same capped-with-overflow
treatment the day tile's achievement row already uses. Thumbnails SHALL be ordered consistently with
the day's expanded game list.

#### Scenario: Day tile shows its games
- **WHEN** a History day tile represents a day with one or more games played
- **THEN** the tile shows a horizontal row of those games' thumbnails without the day being expanded

#### Scenario: Thumbnail overflow
- **WHEN** a day holds more games than the row's cap
- **THEN** the row shows the capped number of thumbnails followed by a count of the remaining games

#### Scenario: Day with no games
- **WHEN** a History day tile represents a day with recorded progress but no games played
- **THEN** no thumbnail row is shown, rather than an empty row

#### Scenario: Thumbnail order matches the expanded list
- **WHEN** the user expands a day whose thumbnails are shown
- **THEN** the expanded game list begins with the games whose thumbnails were shown, in the same
  order

#### Scenario: Games and achievements distinguishable on one tile
- **WHEN** a day tile shows both game thumbnails and achievement icons
- **THEN** the two rows are visually distinct and separately identifiable

### Requirement: Game detail manual refresh
The game detail screen SHALL provide a pull-down gesture that refreshes the game's current Steam
player count on demand, in addition to the screen's existing periodic polling. The gesture SHALL
indicate that a refresh is in progress and SHALL indicate its completion. A manual refresh SHALL NOT
be immediately followed by an already-scheduled periodic poll.

A refresh that fails or returns no count SHALL leave the screen showing no player-count line, in the
same omit-rather-than-placeholder manner as a failed periodic poll, rather than surfacing an error
state over the rest of the summary.

#### Scenario: Refreshing the player count
- **WHEN** the user pulls down on the game detail screen
- **THEN** the game's current player count is fetched again and the summary shows the new value when
  it resolves

#### Scenario: Refresh in progress
- **WHEN** a manual refresh is in flight
- **THEN** the screen indicates that a refresh is happening, and indicates when it has finished

#### Scenario: Refresh completion is independent of periodic polling
- **WHEN** the selected game's current-player response resolves
- **THEN** the manual refresh indicator stops immediately, while the next periodic poll remains
  scheduled relative to that response and does not keep the manual refresh active

#### Scenario: Manual refresh resets the polling interval
- **WHEN** the user manually refreshes the player count
- **THEN** the next periodic poll is scheduled relative to the manual refresh, rather than firing
  immediately afterwards from the previous schedule

#### Scenario: Refresh fails
- **WHEN** a manual refresh fails or Steam reports no count for the game
- **THEN** the summary shows no player-count line, and no error state is presented over the rest of
  the summary

#### Scenario: Refresh does not disturb local content
- **WHEN** a manual refresh is in flight or has failed
- **THEN** the summary's locally-derived content and the achievement list remain rendered and usable
  throughout

#### Scenario: Refresh scope
- **WHEN** the user performs the pull-down refresh
- **THEN** only the player count is refreshed, and no library sync, achievement fetch, or
  HowLongToBeat lookup is triggered by the gesture

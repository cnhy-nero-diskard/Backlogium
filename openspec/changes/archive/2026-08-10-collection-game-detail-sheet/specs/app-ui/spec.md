## MODIFIED Requirements

### Requirement: Game detail screen with achievements
The system SHALL provide a game detail screen, reachable by selecting a game from the
Library or by selecting a game tile in a collection overview, that lists that game's
achievements with each achievement's unlock state, rarity tier, and the XP it contributes,
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
- **THEN** the game's summary is still shown, and the achievement area indicates there are no
  achievements to show rather than appearing broken

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

## ADDED Requirements

### Requirement: Game detail presentation by entry point
The system SHALL present game detail as a full screen when it is opened from the Library, and
as a partial-height overlay rising from the bottom of the screen when it is opened from a
collection overview. The overlay SHALL leave part of the collection overview visible above it,
so the collection remains the evident context, and SHALL be dismissible by a downward swipe and
by system back, both returning to that collection overview. Dismissing the overlay SHALL NOT
leave the collection overview scrolled or otherwise repositioned.

#### Scenario: Collection entry point presents an overlay
- **WHEN** the user selects a game tile in a collection overview
- **THEN** game detail rises from the bottom as a partial-height overlay and part of the
  collection overview remains visible above it

#### Scenario: Library entry point presents a full screen
- **WHEN** the user selects a game in the Library
- **THEN** game detail is presented as a full screen, not as an overlay

#### Scenario: Dismissing the overlay by swipe
- **WHEN** the user swipes the game detail overlay downward
- **THEN** the overlay is dismissed and the collection overview it was opened from is shown

#### Scenario: Dismissing the overlay by system back
- **WHEN** the game detail overlay is shown and the user triggers system back
- **THEN** the overlay is dismissed and the collection overview it was opened from is shown,
  rather than the collection itself being closed

#### Scenario: Scrolling the overlay's content
- **WHEN** the game detail overlay is shown and the user scrolls its achievement list away from
  the list's top
- **THEN** the list scrolls and the overlay is not dismissed

### Requirement: Game detail accent wash containment
The game detail screen derives a muted accent color from the game's header art and paints it as
a background wash. When game detail is presented as a full screen, the wash SHALL span the app
shell so it renders behind the shell's profile header as well as the screen's own content. When
game detail is presented as an overlay, the wash SHALL be confined to the overlay's own bounds
and SHALL NOT tint the collection overview behind it. The wash SHALL NOT persist on any screen
after game detail is left or dismissed.

#### Scenario: Full-screen wash spans the shell
- **WHEN** game detail is opened from the Library for a game whose header art resolves
- **THEN** the accent wash renders behind the app shell's profile header as well as the screen
  content

#### Scenario: Overlay wash stays inside the overlay
- **WHEN** game detail is opened as an overlay from a collection overview for a game whose
  header art resolves
- **THEN** the accent wash renders only within the overlay, and the collection overview visible
  above it is not tinted

#### Scenario: Wash cleared on dismissal
- **WHEN** the user leaves game detail by any means from either entry point
- **THEN** no accent wash from that game remains on the screen that is shown next

#### Scenario: Game without resolvable header art
- **WHEN** game detail is opened for a game whose header art is absent or fails to load
- **THEN** no accent wash is painted, in either presentation, and the screen renders on the
  theme's own background

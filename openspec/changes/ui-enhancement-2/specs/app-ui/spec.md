## ADDED Requirements

### Requirement: The display-density control is identified by symbol
The control that changes a game list's display density SHALL identify the active density by a
symbol rather than by its name, and SHALL occupy the same width whichever density is active, so
that changing density does not change the size of any control beside it. The control SHALL remain
identifiable without sight of the symbol, and the density names SHALL remain visible where the
choice is made.

#### Scenario: Control width is independent of the active density
- **WHEN** the user changes the display density
- **THEN** the density control occupies the same width as before, and every control sharing its row
  keeps the width it had

#### Scenario: Densest setting does not shrink the search field
- **WHEN** a game list is shown at its densest setting
- **THEN** the search field beside the density control is no narrower than at any other density

#### Scenario: Choosing a density still names it
- **WHEN** the user opens the density control to change the density
- **THEN** each available density is presented by name, and the active one is marked

#### Scenario: Active density is announced
- **WHEN** the density control is reached by an accessibility service
- **THEN** the active density is announced by name

### Requirement: The achievement-rarity total never wraps
The achievement-rarity breakdown's header SHALL present its count of unlocked achievements on a
single line for any magnitude of that count. Where the header cannot fit all of its contents, the
optional expansion control SHALL yield space before the count does.

#### Scenario: Four-digit unlocked count
- **WHEN** the player's unlocked achievement count reaches four or more digits
- **THEN** the header presents the count on one line, with no part of its wording broken across
  lines

#### Scenario: Header is too narrow for all of its contents
- **WHEN** the header's title, expansion control, and count cannot all fit
- **THEN** the expansion control is the element that is truncated, and the count is presented in full

#### Scenario: Count is not abbreviated
- **WHEN** the unlocked count is large
- **THEN** it is presented as its exact figure rather than rounded or abbreviated

## MODIFIED Requirements

### Requirement: Game list display density
The system SHALL let the user choose the display density of a list of games, offering a full-detail
list and at least two grid densities showing progressively more games with progressively less detail
per game. Each surface offering a density choice SHALL remember its own choice between visits,
independently of any other surface's.

Density SHALL govern only how much of a game's information is shown, never which games are shown or
in what order. Detail SHALL be dropped in a fixed order as density increases, so a denser view is
always a strict subset of a less dense one:

1. the game's identity — its name and its icon — SHALL be shown at every density;
2. playtime SHALL be shown at every density except the densest;
3. completion progress against a HowLongToBeat length SHALL be shown in the list and the least dense
   grid;
4. the count of unlocked achievements out of a game's total SHALL be shown in the list and the least
   dense grid;
5. the contributed-XP badge SHALL be shown in the list only.

A game's currently-playing state SHALL remain visible at every density, since it is a live signal
rather than detail.

#### Scenario: Choosing a density
- **WHEN** the user chooses a display density for a game list
- **THEN** that list re-renders at the chosen density without changing which games it contains or
  their order

#### Scenario: Density remembered
- **WHEN** the user leaves a surface whose density they changed and returns to it
- **THEN** the list is still shown at the chosen density

#### Scenario: Densities remembered independently
- **WHEN** the user chooses different densities on two surfaces that each offer a density choice
- **THEN** each surface keeps its own choice and neither affects the other

#### Scenario: Identity always shown
- **WHEN** a game list is shown at any density
- **THEN** every game shows its name and its icon

#### Scenario: Denser views are strict subsets
- **WHEN** the user increases the density of a game list
- **THEN** the information shown per game is a subset of what the previous density showed, with
  nothing newly appearing

#### Scenario: Achievement count survives into the least dense grid
- **WHEN** a game with stored achievement data is shown in the least dense grid
- **THEN** its unlocked-of-total count is shown, and its contributed-XP badge is not

#### Scenario: Densest grid carries neither badge
- **WHEN** a game with stored achievement data is shown at the densest setting
- **THEN** neither its achievement count nor its contributed-XP badge is shown

#### Scenario: Currently-playing survives every density
- **WHEN** a game is currently being played and its list is shown at the densest setting
- **THEN** that game is still distinguishable as currently playing

#### Scenario: Selection available at every density
- **WHEN** a list supports selecting games and is shown at any density
- **THEN** games can still be selected and the selected state is visible

#### Scenario: Unrecognized stored density
- **WHEN** a stored density value cannot be recognized
- **THEN** the list falls back to its default density rather than failing to render

### Requirement: Per-list Library sorting
The system SHALL let the user choose the sort order of each Library list independently, offering at
least playtime, name, recent activity, and contributed XP, and SHALL remember each list's chosen
order between visits.

The system SHALL additionally let the user reverse each list's sort direction, independently of the
other list and independently of the chosen key, and SHALL remember each list's chosen direction
between visits. Each key SHALL have a default direction, and a list with no stored direction SHALL
use its key's default, so a list the user has never reversed is ordered as it was before directions
existed.

#### Scenario: Sorting a list
- **WHEN** the user chooses a sort order for a Library list
- **THEN** that list is reordered accordingly and the other list's order is unaffected

#### Scenario: Available orders
- **WHEN** the sort options for a list are presented
- **THEN** they include ordering by playtime, by name, by recent activity, and by contributed XP

#### Scenario: Reversing a list
- **WHEN** the user reverses a Library list's sort direction
- **THEN** that list is presented in the opposite order under the same key, and the other list's
  direction is unaffected

#### Scenario: Direction is independent of the key
- **WHEN** the user reverses a list and then changes its sort key
- **THEN** the reversed direction still applies, under the newly chosen key

#### Scenario: Order remembered
- **WHEN** the user leaves the Library and returns
- **THEN** each list is still ordered as the user last chose, in the direction they last chose

#### Scenario: Default orders
- **WHEN** the user has never chosen a sort order
- **THEN** each list uses its existing default order, in that key's default direction

#### Scenario: Default direction per key
- **WHEN** a list is sorted by a key whose direction the user has never reversed
- **THEN** name is ordered ascending, and playtime, recent activity, and contributed XP are ordered
  descending

#### Scenario: Stable ordering
- **WHEN** two games compare equal under the chosen sort key
- **THEN** their relative order is determined consistently rather than arbitrarily

#### Scenario: Games missing the sort key
- **WHEN** a list is sorted by a key that some games have no value for
- **THEN** those games are ordered last rather than being omitted or placed arbitrarily

#### Scenario: Games missing the sort key under a reversed direction
- **WHEN** a list sorted by a key that some games have no value for is reversed
- **THEN** those games are ordered first, consistently with the reversal, rather than being omitted
  or held in place

#### Scenario: Sorting combined with search
- **WHEN** a search filter is active
- **THEN** the matching games are presented strongest-match first, and the chosen sort order
  arranges games that matched the query equally strongly

#### Scenario: Reversal does not invert search relevance
- **WHEN** a search filter is active and the list's direction is reversed
- **THEN** the strongest matches are still presented first, and only the ordering within an equally
  matched group is reversed

#### Scenario: Chosen sort restored when search is cleared
- **WHEN** the user clears an active search
- **THEN** each list returns to being ordered solely by its chosen sort order and direction

### Requirement: Per-game achievement count on Library rows
The system SHALL display, on each Library game row and on each game cell in the least dense grid
that has stored achievement data, a compact count of unlocked achievements out of that game's total.

#### Scenario: Row shows unlocked-of-total count
- **WHEN** the Library shows a game with stored achievement data
- **THEN** the row displays how many of the game's achievements are unlocked out of its total

#### Scenario: Grid cell shows unlocked-of-total count
- **WHEN** the Library shows a game with stored achievement data in the least dense grid
- **THEN** the cell displays how many of the game's achievements are unlocked out of its total

#### Scenario: Row without achievement data
- **WHEN** the Library shows a game with no stored achievement data
- **THEN** the row shows no achievement count and is otherwise unchanged

#### Scenario: Grid cell without achievement data
- **WHEN** the Library shows a game with no stored achievement data in the least dense grid
- **THEN** the cell shows no achievement count and its layout is otherwise unchanged

### Requirement: Distinct visual signal for a fully-completed game
The system SHALL visually distinguish a game whose achievements are all unlocked from one
that is merely in progress, both on its Library row and on its detail screen. Where a game's
unlocked-of-total count is shown in the least dense grid, the same distinction SHALL apply there.

#### Scenario: Fully-completed game stands out on the Library row
- **WHEN** the Library shows a game whose unlocked achievement count equals its total (and
  that total is greater than zero)
- **THEN** the row displays a distinct "100% Completed" indicator in place of the plain
  unlocked-of-total count

#### Scenario: Fully-completed game stands out in the least dense grid
- **WHEN** the least dense grid shows a game whose unlocked achievement count equals its total (and
  that total is greater than zero)
- **THEN** the cell displays the same completion indicator in place of the plain unlocked-of-total
  count

#### Scenario: Fully-completed game is announced on its detail screen
- **WHEN** the user opens the detail screen for a game whose achievements are all unlocked
- **THEN** the screen displays a prominent completion banner distinct from the per-achievement
  list

#### Scenario: In-progress game shows no completion signal
- **WHEN** a game has stored achievement data but its unlocked count is less than its total
- **THEN** neither the Library row, nor its grid cell, nor the detail screen displays the completion
  indicator

## MODIFIED Requirements

### Requirement: Library search
The system SHALL provide a search that filters the Library by game name or any known genre label, ignoring case and preserving the section structure for sections that still contain matches. The search field SHALL communicate that both games and genres are searchable.

#### Scenario: Filtering by name
- **WHEN** the user enters text contained in a game's name in the Library search
- **THEN** that game is shown regardless of whether genre metadata is available

#### Scenario: Filtering by genre
- **WHEN** the user enters text contained in one or more known genre labels
- **THEN** games carrying any matching genre are shown

#### Scenario: One game matches name and genre
- **WHEN** the same game matches the query through both its name and a genre label
- **THEN** the game is shown once in its existing section

#### Scenario: Sections preserved while filtering
- **WHEN** a filter is active and matches exist in more than one section
- **THEN** each section with matches keeps its heading

#### Scenario: No matches
- **WHEN** a filter matches no game name or known genre
- **THEN** an empty state explains that no games match, rather than showing a blank list

#### Scenario: Clearing the filter
- **WHEN** the user clears the search
- **THEN** the full Library is shown again

## ADDED Requirements

### Requirement: Game detail genre tiles
The game detail summary SHALL display every known genre for the game as compact, non-interactive tiles that wrap across available width. The tiles SHALL use the cached Store order and SHALL be omitted when the game has no known genres.

#### Scenario: Game has known genres
- **WHEN** the user opens game detail for a game with one or more cached genres
- **THEN** all known genre labels appear as wrapping tiles in the summary above the achievement list

#### Scenario: Game has no known genres
- **WHEN** the user opens game detail for a game whose genres are unknown or empty
- **THEN** no genre-tile section or genre error placeholder is shown

#### Scenario: Genre tile is informational
- **WHEN** the user taps a genre tile in the initial genre release
- **THEN** no navigation or filter change occurs

### Requirement: Collection add-game genre filtering
The collection management screen SHALL provide a compact multi-select genre control for the Add games pool. With multiple genres selected, a non-member game SHALL remain addable when it has any selected genre. When both a text query and genre selections are active, the game SHALL satisfy the text query and at least one selected genre. Filtering SHALL never change collection membership by itself.

#### Scenario: No genre selected
- **WHEN** the user has selected no genre filter
- **THEN** every non-member game allowed by the text query remains in the Add games pool

#### Scenario: One genre selected
- **WHEN** the user selects one genre
- **THEN** only non-member games carrying that genre and satisfying any active text query are offered

#### Scenario: Additional genre selected additively
- **WHEN** the user selects another genre while one or more genres are already selected
- **THEN** the pool expands to include non-member games carrying any selected genre rather than requiring every selected genre

#### Scenario: Text and genre filters combine
- **WHEN** the user has both a text query and selected genres
- **THEN** a game is offered only when its name contains the text ignoring case and it carries at least one selected genre

#### Scenario: Unknown genre under an active genre filter
- **WHEN** a non-member game has unknown or empty genres while a genre filter is active
- **THEN** that game is not offered until the genre filter is cleared or matching metadata becomes available

#### Scenario: Adding a filtered game preserves filters
- **WHEN** the user adds an offered game while text or genre filters are active
- **THEN** that game becomes a draft member and the active filters remain available for continued curation

#### Scenario: Clearing selected genres
- **WHEN** the user clears all selected genres
- **THEN** genre filtering is removed without changing draft collection membership or the text query

#### Scenario: Filtering never bulk-adds games
- **WHEN** the user selects or clears a genre
- **THEN** no game is added to or removed from the draft collection automatically

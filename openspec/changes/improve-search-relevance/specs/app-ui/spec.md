## MODIFIED Requirements

### Requirement: Library search
The system SHALL provide a search that filters the Library by game name or any known genre label,
ignoring case and preserving the section structure for sections that still contain matches. The
search field SHALL communicate that both games and genres are searchable.

Matches SHALL be presented in order of how closely they matched the query, strongest first: an exact
name match, then a name beginning with the query, then a name containing a word beginning with the
query, then a name containing the query elsewhere, then a match on a genre label alone. Ranking
SHALL ignore case. The search SHALL also offer a genre filter that narrows results to games carrying
any selected genre. That genre selection SHALL apply to the current visit only and SHALL NOT be
remembered between visits, unlike each list's chosen sort order.

The search field SHALL keep a stable width and a legible input while it is focused and while text is
entered, so neither focusing the field nor typing into it changes the size of the field or of the
text within it.

#### Scenario: Filtering by name
- **WHEN** the user enters text contained in a game's name in the Library search
- **THEN** that game is shown regardless of whether genre metadata is available

#### Scenario: Filtering by genre
- **WHEN** the user enters text contained in one or more known genre labels
- **THEN** games carrying any matching genre are shown

#### Scenario: One game matches name and genre
- **WHEN** the same game matches the query through both its name and a genre label
- **THEN** the game is shown once in its existing section, ranked by its name match

#### Scenario: Stronger name match ranked first
- **WHEN** one game's name begins with the query and another game's name contains the query only
  in the middle of a word
- **THEN** the game whose name begins with the query is presented first, regardless of either
  game's playtime or other sort values

#### Scenario: Word prefix outranks a mid-word match
- **WHEN** the query matches the beginning of a word inside one game's name and matches only the
  middle of a word in another game's name
- **THEN** the game matching at a word boundary is presented first

#### Scenario: Name match outranks a genre-only match
- **WHEN** one game matches through its name and another matches only through a genre label
- **THEN** the game matching by name is presented first

#### Scenario: Sections preserved while filtering
- **WHEN** a filter is active and matches exist in more than one section
- **THEN** each section with matches keeps its heading

#### Scenario: Genre filter narrows the search
- **WHEN** the user selects one or more genres in the Library search
- **THEN** only games carrying at least one selected genre are shown, ranked as above

#### Scenario: Genre filter not remembered between visits
- **WHEN** the user selects genres in the Library search, leaves the Library, and returns
- **THEN** no genre filter is active and the full Library is shown, while each list's chosen sort
  order is still remembered

#### Scenario: Field stable under focus and input
- **WHEN** the user focuses the Library search field and types
- **THEN** the field's width and the size of the text within it are unchanged from their unfocused,
  empty state

#### Scenario: No matches
- **WHEN** a filter matches no game name or known genre
- **THEN** an empty state explains that no games match, rather than showing a blank list

#### Scenario: Clearing the filter
- **WHEN** the user clears the search
- **THEN** the full Library is shown again

### Requirement: Per-list Library sorting
The system SHALL let the user choose the sort order of each Library list independently, offering at
least playtime, name, recent activity, and contributed XP, and SHALL remember each list's chosen order
between visits.

#### Scenario: Sorting a list
- **WHEN** the user chooses a sort order for a Library list
- **THEN** that list is reordered accordingly and the other list's order is unaffected

#### Scenario: Available orders
- **WHEN** the sort options for a list are presented
- **THEN** they include ordering by playtime, by name, by recent activity, and by contributed XP

#### Scenario: Order remembered
- **WHEN** the user leaves the Library and returns
- **THEN** each list is still ordered as the user last chose

#### Scenario: Default orders
- **WHEN** the user has never chosen a sort order
- **THEN** each list uses its existing default order

#### Scenario: Stable ordering
- **WHEN** two games compare equal under the chosen sort key
- **THEN** their relative order is determined consistently rather than arbitrarily

#### Scenario: Games missing the sort key
- **WHEN** a list is sorted by a key that some games have no value for
- **THEN** those games are ordered last rather than being omitted or placed arbitrarily

#### Scenario: Sorting combined with search
- **WHEN** a search filter is active
- **THEN** the matching games are presented strongest-match first, and the chosen sort order
  arranges games that matched the query equally strongly

#### Scenario: Chosen sort restored when search is cleared
- **WHEN** the user clears an active search
- **THEN** each list returns to being ordered solely by its chosen sort order

### Requirement: Collection add-game genre filtering
The collection management screen SHALL provide a compact multi-select genre control for the Add games pool. With multiple genres selected, a non-member game SHALL remain addable when it has any selected genre. When both a text query and genre selections are active, the game SHALL satisfy the text query and at least one selected genre. Filtering SHALL never change collection membership by itself.

The text query SHALL match a game's name or any known genre label, ignoring case, and offered games
SHALL be presented in the same strongest-match-first order the Library search uses. The Add games
search field and the games it offers SHALL be positioned so that results remain visible while the
user is typing into the field.

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
- **THEN** a game is offered only when it matches the text through its name or a genre label,
  ignoring case, and it carries at least one selected genre

#### Scenario: Offered games ranked by match strength
- **WHEN** a text query is active and several non-member games match it in different ways
- **THEN** they are offered strongest-match first, by the same ranking the Library search applies

#### Scenario: Results visible while typing
- **WHEN** the user types into the Add games search field
- **THEN** the offered games remain visible without the user first dismissing the keyboard or
  scrolling away from the field

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

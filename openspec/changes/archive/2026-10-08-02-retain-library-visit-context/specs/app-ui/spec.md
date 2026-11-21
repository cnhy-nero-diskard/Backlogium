## MODIFIED Requirements

### Requirement: Library search
The system SHALL provide a search that filters the Library by game name or any known genre label,
ignoring case and preserving the section structure for sections that still contain matches. The
search field SHALL communicate that both games and genres are searchable.

Matches SHALL be presented in order of how closely they matched the query, strongest first: an exact
name match, then a name beginning with the query, then a name containing a word beginning with the
query, then a name containing the query elsewhere, then a match on a genre label alone. Ranking
SHALL ignore case. The search SHALL also offer a genre filter that narrows results to games carrying
any selected genre. The text query and genre selection SHALL remain active for the current Library
visit as defined by Library visit context retention, but SHALL NOT be remembered after that visit
expires, unlike each list's chosen sort order.

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

#### Scenario: Genre filter retained during a visit
- **WHEN** the user selects genres, leaves Library for another tab, and returns in less than five minutes
- **THEN** the selected genres remain active with the other Library discovery filters

#### Scenario: Genre filter not remembered between visits
- **WHEN** the user selects genres, leaves Library for another tab, and returns five minutes or more later
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

## ADDED Requirements

### Requirement: Library visit context retention
The system SHALL preserve the Library text query, selected genres, coverage-only and Family
Shared-only filters, and the current useful scroll position during a Library visit. A pushed screen
opened from Library, including game detail or HLTB review, SHALL remain part of that visit. Switching
to another top-level tab or backgrounding the app SHALL start a five-minute absence interval. If
both happen, the earliest departure time SHALL govern; backgrounding SHALL NOT extend an interval
already running. Returning while less than five minutes have elapsed SHALL resume the visit. At
five minutes or later, the next Library presentation SHALL start a fresh visit with those filters
cleared and scroll reset to the top. A cold launch SHALL start a fresh visit. Library sort and
density preferences SHALL retain their existing persistence behavior.

#### Scenario: Return from a pushed screen
- **WHEN** the player opens game detail or HLTB review from a filtered Library and returns, regardless
  of time spent on that screen while the app remains foregrounded
- **THEN** the same query and filters remain active and the Library returns to the same useful scroll
  position when its prior content is still available

#### Scenario: Short tab departure
- **WHEN** the player switches away from Library and returns less than five minutes later
- **THEN** the query, filters, and useful scroll position are restored

#### Scenario: Tab departure reaches the threshold
- **WHEN** the player switches away from Library and returns five minutes or more later
- **THEN** the query and filters are cleared and Library opens at the top

#### Scenario: Short background departure
- **WHEN** the player backgrounds the app while in Library or a screen pushed from it, and resumes
  the Library visit less than five minutes later
- **THEN** the query, filters, and useful scroll position are restored

#### Scenario: Background departure reaches the threshold
- **WHEN** the player backgrounds the app while in Library or a screen pushed from it, and next sees
  Library five minutes or more later
- **THEN** the query and filters are cleared and Library opens at the top

#### Scenario: Backgrounding does not renew an earlier tab departure
- **WHEN** the player switches away from Library, later backgrounds the app, and returns to Library
  five minutes or more after the tab switch
- **THEN** a fresh Library visit starts even if less than five minutes have passed since backgrounding

#### Scenario: Repeated short departures
- **WHEN** the player returns to Library before expiry, then leaves it again
- **THEN** the next five-minute absence interval starts from the later departure

#### Scenario: Configuration recreation
- **WHEN** the device configuration recreates the current Library visit
- **THEN** the query, filters, scroll context, and any running absence interval remain unchanged

#### Scenario: Cold launch
- **WHEN** the app launches into a new process after a previous Library visit
- **THEN** Library starts with no transient query or filters and at the top, while saved sort and
  density preferences remain available

#### Scenario: Explicit clear
- **WHEN** the player uses a Library Clear control during an active visit
- **THEN** the selected query or filters clear immediately without waiting for visit expiry

#### Scenario: Items change during a retained visit
- **WHEN** Library content changes while the player is away and the visit has not expired
- **THEN** the retained query and filters apply to current content and the scroll position stays near
  the previously visible item when possible, or at the nearest valid position when that item is gone

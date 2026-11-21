## ADDED Requirements

### Requirement: Wishlist matches in Library search
Library text search SHALL include cached wishlist titles alongside owned and Family Shared games.
Wishlist-only matches SHALL appear in a clearly labeled wishlist result section and SHALL use
wishlist-appropriate store actions rather than owned-game tracking or collection actions. A title
present in both sources SHALL not appear as two indistinguishable results. Search SHALL preserve
the existing owned-game ranking and grouping behavior.

#### Scenario: Wishlist title matches
- **WHEN** a text query matches a cached wishlist-only title
- **THEN** that title appears as a labeled wishlist result in list and both grid densities

#### Scenario: Owned and wishlist title overlap
- **WHEN** a Steam app ID appears in both owned/shared results and the cached wishlist
- **THEN** its owned/shared result retains the Library detail and tracking actions, and no duplicate
  wishlist-only result is shown

#### Scenario: Wishlist-only action
- **WHEN** the player opens a wishlist-only search result
- **THEN** its available store action is offered without owned-game tracking or collection controls

#### Scenario: Owned-only filters with a query
- **WHEN** an owned-game genre, HLTB coverage, or Family Shared filter is active with a text query
- **THEN** wishlist-only titles are not presented as if they satisfied that filter

#### Scenario: Wishlist read fails with cached entries
- **WHEN** the latest wishlist read fails but cached wishlist entries exist
- **THEN** matching cached entries remain searchable and the wishlist's unavailable or stale state
  remains distinguishable from a fresh result

#### Scenario: Wishlist read fails without cached entries
- **WHEN** the wishlist cannot be read and has no cached entries
- **THEN** search does not claim there are no matching wishlist games; it identifies wishlist
  unavailability separately from an owned-game no-match state

### Requirement: Added recently Library sorting
Each owned/shared Library section SHALL offer an Added recently sort based on the game's recorded
first observation by Backlogium. It SHALL be separate from Recent activity, which describes play.
Known observation times SHALL order by time, with stable title and app-ID tie-breaks. Unknown times,
including baseline and legacy games, SHALL remain grouped after known times in either direction
without implying an acquisition order. The chosen sort and direction SHALL retain the existing
per-section preference behavior.

#### Scenario: Newest observed games first
- **WHEN** the player selects Added recently in its default direction
- **THEN** games with known observation times appear newest first within that Library section

#### Scenario: Reverse added order
- **WHEN** the player reverses Added recently
- **THEN** games with known observation times appear oldest first, while unknown times remain after
  dated games

#### Scenario: Equal observation time
- **WHEN** multiple games share the same observation time, including a first-sync batch
- **THEN** their order is stable by title and app ID and does not claim one was acquired first

#### Scenario: Unknown observation time
- **WHEN** a baseline or legacy game has no recorded observation time
- **THEN** it remains sortable and is not assigned a fabricated date

#### Scenario: Sort explanation
- **WHEN** the Added recently option or its explanation is shown
- **THEN** the app makes clear the time is when Backlogium first observed the game, not its Steam
  purchase date

### Requirement: Concise Hide game confirmation
The Hide game confirmation SHALL state the immediate consequence and how to restore the game in
short, direct copy. When hiding changes XP, level, or Focus membership, the dialog SHALL still
disclose those material effects. It SHALL retain distinct confirm and cancel actions.

#### Scenario: Confirm or cancel hiding
- **WHEN** the player chooses Hide game from Library
- **THEN** the dialog explains that the game will leave visible surfaces and can be restored from
  Hidden Games, with clear Hide and Cancel choices

#### Scenario: Material progress effect
- **WHEN** hiding the selected game changes XP, level, or Focus membership
- **THEN** the confirmation states those effects concisely before the player confirms

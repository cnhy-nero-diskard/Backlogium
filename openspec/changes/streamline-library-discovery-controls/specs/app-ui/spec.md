## ADDED Requirements

### Requirement: Library presents discovery before enrichment tools
Library SHALL keep search and active filters in its primary control area. Display density and HowLongToBeat enrichment SHALL remain reachable through labeled secondary controls without competing with the search field for first attention. Independent Focus/Your games sorting SHALL remain beside its section heading. Pending review and active refresh states SHALL remain visible without opening those controls.

#### Scenario: Routine Library entry
- **WHEN** Library opens with no active batch operation or review queue
- **THEN** search and filter state are the primary controls and enrichment tools are available through a labeled secondary entry point

#### Scenario: HLTB work needs attention
- **WHEN** a refresh is running or one or more matches need review
- **THEN** Library surfaces the progress or review count in the primary flow while preserving search and active-filter access

#### Scenario: Density preserved
- **WHEN** the player opens the secondary Library controls
- **THEN** every existing density option remains available and retains its current persistence behavior

#### Scenario: Section-local sorting preserved
- **WHEN** the player views the Focus and Your games sections
- **THEN** each section exposes its independent sort options beside its heading and retains its current persistence behavior

### Requirement: Library filter state has complete recovery
Library SHALL model the text query, selected genres, coverage-only state, and Family Shared-only state as one visible active-filter set. Each active filter SHALL be removable individually, and a clear-all action SHALL restore the unfiltered library.

#### Scenario: Family Shared filter has no matches
- **WHEN** Family Shared-only is active and no visible game matches
- **THEN** the empty result explicitly names the Family Shared filter and offers an action that clears it

#### Scenario: Combined filters have no matches
- **WHEN** two or more active filters produce no visible games
- **THEN** the empty result states that the active filters have no matches and offers both individual removal and clear-all recovery

#### Scenario: Clear all filters
- **WHEN** the player activates clear all
- **THEN** the text query, genres, coverage-only state, and Family Shared-only state are all reset while density and sort preferences remain unchanged

### Requirement: Genre filtering scales to a large catalog
The genre picker SHALL provide text search over the available genre labels, preserve multi-selection while searching, and render a scrollable result set suitable for a large catalog.

#### Scenario: Search genres
- **WHEN** the player types into the genre picker search field
- **THEN** the picker shows matching genre labels without clearing selections that are outside the current result set

#### Scenario: Apply multiple genres
- **WHEN** the player selects multiple genres and dismisses the picker
- **THEN** the Library applies the existing any-selected-genre matching behavior and shows the selected genres as removable active filters

### Requirement: Library selection is discoverable without long press
Library SHALL retain long-press selection as an accelerator and SHALL also provide a visible, labeled way to enter selection mode. Assistive technology SHALL receive a long-click label and clear selected-state semantics for each game.

#### Scenario: Enter selection without a gesture
- **WHEN** the player activates the visible Select action
- **THEN** Library enters selection mode and exposes selection controls without requiring a long press

#### Scenario: Long-press accelerator
- **WHEN** the player long-presses a game outside selection mode
- **THEN** that game becomes selected and accessibility services identify the gesture as selecting the game

#### Scenario: Exit selection
- **WHEN** the player clears selection or leaves Library
- **THEN** selection mode ends and ordinary game-opening behavior is restored

### Requirement: Library copy follows Android locale resources
User-visible Library labels, counts, plurals, and formatted values SHALL resolve through Android resources and locale-aware formatting.

#### Scenario: Localized selection count
- **WHEN** Library is shown under a supported locale with one or multiple selected games
- **THEN** the selection count and associated actions use the correct localized quantity forms

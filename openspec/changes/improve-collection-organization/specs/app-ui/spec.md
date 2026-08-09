## MODIFIED Requirements

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection. Each card SHALL foreground the collection name, one concise mode-relevant status line, and a structured progress surface when applicable without rendering a separate uppercase mode label. The mode icon SHALL remain visually and accessibly identifiable. Cards SHALL use an elevated surface distinct from the Home background, remain visually separated, and use a stored accent to tint card and accent affordances. Tapping a card SHALL open its collection overview. The section SHALL render from locally stored state, present an empty state when no collections exist, and SHALL NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces.

Cards SHALL be presented in the collection's stored display order, and the user SHALL be able to
change that order by pressing and holding a card and dragging it to a new position. The reordering
gesture SHALL be distinguishable from the section's own scrolling, so neither gesture triggers the
other. A completed reorder SHALL be persisted so the new order is present on the next visit. The
collection description SHALL NOT be rendered on the Home card, which stays limited to the name,
one status line, progress, and member thumbnails.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection with its name, mode icon, and concise mode-relevant state

#### Scenario: Cards shown in stored order
- **WHEN** the Home collections section is shown
- **THEN** the cards appear in the collection's stored display order

#### Scenario: Reordering a collection card
- **WHEN** the user presses and holds a collection card and drags it to another position
- **THEN** the card follows the drag, the other cards move aside, and on release the new order is
  persisted

#### Scenario: Reordered collections persist
- **WHEN** the user reorders collections and later returns to Home
- **THEN** the collections are presented in the order the user left them

#### Scenario: Drag distinguished from scrolling
- **WHEN** the user scrolls the Home screen with a swipe that begins on a collection card
- **THEN** the screen scrolls and no card is picked up for reordering

#### Scenario: Reorder abandoned
- **WHEN** the user picks up a card and releases it at its original position
- **THEN** the order is unchanged and no reorder is persisted

#### Scenario: Single collection
- **WHEN** only one collection exists
- **THEN** it presents no reordering affordance, since there is no other position to move it to

#### Scenario: Description absent from the Home card
- **WHEN** a collection has a stored description
- **THEN** its Home card does not render that description, keeping the card limited to name, status
  line, progress, and member thumbnails

#### Scenario: Cards visually separated
- **WHEN** two or more collection cards are shown
- **THEN** consecutive cards are separated by visible spacing

#### Scenario: Mode-specific card surfaces
- **WHEN** a collection's mode is completion goal, deadline goal, or ordered queue
- **THEN** its card presents compact structured information relevant to that mode without an uppercase mode heading or a multi-detail sentence

#### Scenario: Healthy deadline card stays quiet
- **WHEN** a reliable complete deadline plan is on track
- **THEN** its Home card shows concise countdown and progress information without buffer or corrective copy

#### Scenario: At-risk deadline card shows required pace
- **WHEN** a reliable complete deadline plan is at risk
- **THEN** its Home card replaces verbose estimate detail with one concise required-pace or attention state

#### Scenario: Incomplete forecast is not expanded on Home
- **WHEN** a collection forecast is incomplete because history or HLTB estimates are missing
- **THEN** Home uses at most a compact incomplete state and leaves the detailed explanation to the collection overview

#### Scenario: Completion-goal trophy summary
- **WHEN** a collection's mode is completion goal and achievement counts exist for one or more members
- **THEN** its Home card may include aggregate trophy progress within its single concise mode-relevant status line

#### Scenario: Accent tint applied
- **WHEN** a collection has a stored accent
- **THEN** its card surface and accent affordances use a low-opacity tint from that accent while its text remains legible

#### Scenario: Cards remain compact
- **WHEN** the Home screen shows multiple collection cards
- **THEN** each card uses compact internal padding and consecutive cards have visible spacing without excessive vertical gaps

#### Scenario: Collection member thumbnail preview
- **WHEN** a Home collection card has one or more members
- **THEN** the card shows up to three small member-game thumbnails in stored member order on the right

#### Scenario: Collection member thumbnail overflow
- **WHEN** a Home collection card has more than three members
- **THEN** the card shows three thumbnails followed by the number of remaining members using the existing `N+` convention, such as `8+` for an eleven-game collection

#### Scenario: Default styling without accent
- **WHEN** a collection has no stored accent
- **THEN** its card presents the default neutral styling

#### Scenario: Opening an existing collection
- **WHEN** the user taps a collection card on Home
- **THEN** a read-only overview of that collection is opened, with its selected games and local collection metrics visible before customization controls

#### Scenario: No collections
- **WHEN** the Home screen is shown and no collections exist
- **THEN** the collections section presents an empty state rather than an empty list

#### Scenario: Offline rendering
- **WHEN** the Home screen is shown without network
- **THEN** the collections section renders from the last stored state without blocking

#### Scenario: Existing Home surfaces preserved
- **WHEN** the collections section is shown on Home
- **THEN** the level, XP, daily-quest, streak, and now-playing surfaces remain present and unchanged

### Requirement: Collection management screen
The system SHALL provide a collection management screen, reached as a pushed sub-destination from the
collection create entry point or an existing collection's explicit customization action, where the user
can create a collection, choose its mode and an accent from the app's
palette, name it, give it an optional description, add and remove games, filter the pool of addable games with a search, set a target
date for deadline-goal collections, reorder members and mark them done for ordered-queue collections,
and delete the collection. The save action SHALL remain reachable regardless of the form's scroll
position. Deleting a collection SHALL require an explicit confirmation that names the collection and
states that its game memberships are removed with it, and SHALL NOT delete anything until that
confirmation is given. The screen SHALL render from locally stored state.

#### Scenario: Creating a collection
- **WHEN** the user creates a new collection with a name and a mode
- **THEN** the collection is persisted and appears on the Home collections section

#### Scenario: Customizing an existing collection
- **WHEN** the user chooses the collection actions control from an existing collection overview
- **THEN** the management form opens with the collection's current settings and members

#### Scenario: Add games hidden from the overview
- **WHEN** an existing collection overview is shown
- **THEN** the name/mode/accent fields and add-games pool are not shown until the user opens
  customization

#### Scenario: Save reachable at any scroll position
- **WHEN** the management screen is shown
- **THEN** the save action is presented as a floating control that remains reachable while the form
  scrolls

#### Scenario: Save blocked without a name
- **WHEN** the collection name is blank
- **THEN** the save action is not usable

#### Scenario: Describing a collection
- **WHEN** the user enters a description on the management screen and saves
- **THEN** the description is persisted on the collection

#### Scenario: Description is optional
- **WHEN** the user saves a collection without entering a description
- **THEN** the collection is saved and no description is required or rendered for it

#### Scenario: Clearing a description
- **WHEN** the user clears a previously saved description and saves
- **THEN** the collection is stored with no description and none is rendered

#### Scenario: Description shown on the overview
- **WHEN** a collection with a stored description is opened
- **THEN** its overview presents that description

#### Scenario: Adding games to a collection
- **WHEN** the user adds games to a collection from the management screen
- **THEN** those games become members of the collection

#### Scenario: Filtering the add-games pool
- **WHEN** the user enters a search query on the management screen
- **THEN** only library games matching the query and not already members are offered for adding

#### Scenario: Search matches nothing
- **WHEN** the search query matches no addable game
- **THEN** the list presents a no-match state beneath the search field and the field remains available
  to clear

#### Scenario: Removing games from a collection
- **WHEN** the user removes a game from a collection
- **THEN** that game is no longer a member of the collection

#### Scenario: Choosing an accent
- **WHEN** the user selects an accent on the management screen
- **THEN** only palette-compatible tokens are offered and the selection is persisted on the collection

#### Scenario: Setting a deadline
- **WHEN** the user sets or changes a target date on a deadline-goal collection
- **THEN** the collection's banner reflects the updated countdown

#### Scenario: Choosing a deadline estimate basis
- **WHEN** the user edits a deadline-goal collection
- **THEN** the setup offers Main Story, Main + Extra, Completionist, and All Styles as the HLTB
  time-estimate basis choices

#### Scenario: Hindsight deadline guidance
- **WHEN** the user opens an existing deadline-goal collection overview
- **THEN** it shows the selected basis, time until or past the deadline, estimated time remaining,
  and a shortfall warning only when the differential is negative

#### Scenario: Shortcut to change an infeasible deadline
- **WHEN** the selected estimate has a negative differential
- **THEN** the overview recommends changing the deadline and provides a direct target-date picker

#### Scenario: Reordering an ordered queue
- **WHEN** the user reorders members of an ordered-queue collection
- **THEN** the sequence is persisted and the next-game surface updates

#### Scenario: Marking a queue member done
- **WHEN** the user marks a member of an ordered-queue collection as done
- **THEN** the member stays listed with its name struck through and its card greyed, and the next-game
  surface skips it

#### Scenario: Deleting a collection
- **WHEN** the user deletes a collection and confirms the deletion
- **THEN** the collection and its memberships are removed, and it no longer appears on Home

#### Scenario: Delete requires confirmation
- **WHEN** the user chooses the delete action for a collection
- **THEN** a confirmation is presented naming the collection and stating that its game memberships are
  removed with it, and nothing is deleted until the user confirms

#### Scenario: Cancelling a deletion
- **WHEN** the user dismisses or cancels the delete confirmation
- **THEN** the collection and all of its memberships remain unchanged

#### Scenario: Empty collection on the management screen
- **WHEN** a collection has no members
- **THEN** the management screen presents an empty state with a control to add games

#### Scenario: Collection overview highlights selected games
- **WHEN** an existing collection has one or more members
- **THEN** the overview presents those members as larger visually highlighted tiles, each showing
  cached playtime and session count and showing trophy progress when stored achievement data exists

#### Scenario: Collection overview summary metrics
- **WHEN** an existing collection overview is shown
- **THEN** it summarizes member count, aggregate playtime, aggregate session count, and aggregate
  trophy progress when achievement data exists

#### Scenario: Target date only for deadline mode
- **WHEN** the user is editing a collection whose mode is not deadline goal
- **THEN** no target date field is offered

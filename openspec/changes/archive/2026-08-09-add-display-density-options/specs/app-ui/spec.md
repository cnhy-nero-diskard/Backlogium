## ADDED Requirements

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
4. achievement and XP badges SHALL be shown in the list only.

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
- **WHEN** the user chooses different densities on two surfaces that each offer the choice
- **THEN** each surface keeps its own choice and neither affects the other

#### Scenario: Identity always shown
- **WHEN** a game list is shown at any density
- **THEN** every game shows its name and its icon

#### Scenario: Denser views are strict subsets
- **WHEN** the user increases the density of a game list
- **THEN** the information shown per game is a subset of what the previous density showed, with
  nothing newly appearing

#### Scenario: Currently-playing survives every density
- **WHEN** a game is currently being played and its list is shown at the densest setting
- **THEN** that game is still distinguishable as currently playing

#### Scenario: Selection available at every density
- **WHEN** a list supports selecting games and is shown at any density
- **THEN** games can still be selected and the selected state is visible

#### Scenario: Unrecognized stored density
- **WHEN** a stored density value cannot be recognized
- **THEN** the list falls back to its default density rather than failing to render

## MODIFIED Requirements

### Requirement: Library screen
The system SHALL provide a Library screen separating a curated, actively-tracked set of games from
the rest of the library, and SHALL allow adding a game to that set and removing it. Any game SHALL
display progress against a HowLongToBeat-sourced completion length when one is available and the
chosen display density shows completion progress, whether or not it belongs to the curated set, and
SHALL display no completion-based progress when no length is available. The curated set SHALL be
labelled in terms of active tracking rather than in terms of a user-entered target, since no such
target is collected, and the remaining games SHALL be labelled without implying that they are
unplayed or awaiting play. The Library SHALL offer a display density choice for its game lists.

#### Scenario: Game with an HLTB length shows progress
- **WHEN** the Library is shown at a density that includes completion progress and a game has a
  HowLongToBeat-sourced completion length
- **THEN** the game displays its name, icon, and playtime, and a progress indicator measuring its
  playtime against that completion length, regardless of whether it belongs to the curated set

#### Scenario: Progress omitted at denser settings
- **WHEN** the Library is shown at a density that does not include completion progress
- **THEN** a game with a HowLongToBeat-sourced completion length shows no progress indicator, and the
  indicator returns when a density that includes it is chosen

#### Scenario: Game played past its completion length
- **WHEN** a game's playtime exceeds its HowLongToBeat-sourced completion length and progress is
  shown at the chosen density
- **THEN** its progress indicator represents the whole playtime, showing the completion length and
  the excess beyond it as visually distinct portions of one full indicator, rather than resting at
  full with the excess unrepresented

#### Scenario: Game without an HLTB length shows no progress
- **WHEN** the Library is shown and a game has no HowLongToBeat-sourced completion length yet
- **THEN** the game displays its name and icon, displays its playtime at any density that includes
  playtime, and does not display completion-based progress at any density

#### Scenario: Adding a game to the tracked set
- **WHEN** the user adds a game to the tracked set, or removes one from it
- **THEN** the game moves between the tracked section and the rest of the library and the change
  persists, without prompting for a typed target

#### Scenario: Managing the tracked set at every density
- **WHEN** the Library is shown at any density
- **THEN** a game can still be added to or removed from the tracked set

#### Scenario: Tracked games appear once
- **WHEN** a game belongs to the tracked set
- **THEN** it appears only in the tracked section and not also among the remaining games

#### Scenario: Sections preserved across densities
- **WHEN** the user changes the Library's display density
- **THEN** the tracked section and the remaining-games section keep their headings and their
  contents, each rendered at the chosen density

#### Scenario: Labelling free of an implied target
- **WHEN** the tracked section and its actions are presented
- **THEN** their labels describe active tracking, and no label implies a completion target set by the
  user

#### Scenario: Remaining games labelled without implying they are unplayed
- **WHEN** the section holding games outside the tracked set is presented
- **THEN** its label does not describe those games as a backlog or as awaiting play, since a game with
  substantial playtime and visible completion progress can belong to it

#### Scenario: Tracked minutes still accounted separately
- **WHEN** playtime is recorded for a game in the tracked set
- **THEN** it continues to be accounted separately in per-day progress and reflected in History, as
  it is today

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

The configuration controls SHALL be presented compactly, so that the collection's games are reachable
without scrolling past a full screen of settings. No configuration option SHALL be removed to achieve
this. The collection overview's member list SHALL offer a display density choice.

#### Scenario: Creating a collection
- **WHEN** the user creates a new collection with a name and a mode
- **THEN** the collection is persisted and appears on the Home collections section

#### Scenario: Customizing an existing collection
- **WHEN** the user chooses the collection actions control from an existing collection overview
- **THEN** the management form opens with the collection's current settings and members

#### Scenario: Configuration presented compactly
- **WHEN** the management form is shown for a collection
- **THEN** its configuration controls occupy materially less vertical space than a full screen before
  the collection's games are reachable

#### Scenario: No option removed for compactness
- **WHEN** the management form is shown
- **THEN** every configuration option remains available — name, description, mode, order, accent, and
  for deadline collections the target date and estimate basis — whether directly or through a
  disclosure the user can open

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

#### Scenario: Queue order legible at every density
- **WHEN** an ordered-queue collection's members are shown at any density
- **THEN** their sequence remains legible and the next game remains identifiable

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
- **WHEN** an existing collection has one or more members and its overview is shown at its least
  dense setting
- **THEN** the overview presents those members as larger visually highlighted tiles, each showing
  cached playtime and session count and showing trophy progress when stored achievement data exists

#### Scenario: Collection overview at a denser setting
- **WHEN** the user chooses a denser setting for the collection overview's member list
- **THEN** more members are visible at once with less detail each, following the display density
  ladder, and no member is omitted

#### Scenario: Collection overview summary metrics
- **WHEN** an existing collection overview is shown
- **THEN** it summarizes member count, aggregate playtime, aggregate session count, and aggregate
  trophy progress when achievement data exists

#### Scenario: Target date only for deadline mode
- **WHEN** the user is editing a collection whose mode is not deadline goal
- **THEN** no target date field is offered

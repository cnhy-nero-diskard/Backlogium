## MODIFIED Requirements

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection. Each card SHALL foreground the collection name, one concise mode-relevant status line, and a structured progress surface when applicable without rendering a separate uppercase mode label. The mode icon SHALL remain visually and accessibly identifiable. Cards SHALL use an elevated surface distinct from the Home background, remain visually separated, and use a stored accent to tint card and accent affordances. Tapping a card SHALL open its collection overview. The section SHALL render from locally stored state, present an empty state when no collections exist, and SHALL NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces.

Cards SHALL be presented in the collection's stored display order, and the user SHALL be able to change that order by pressing and holding a card and dragging it to a new position. The reordering gesture SHALL be distinguishable from the section's own scrolling, so neither gesture triggers the other. A completed reorder — one the user commits by releasing the dragged card — SHALL be persisted so the new order is present on the next visit, including after closing and reopening the screen. A drag that is cancelled rather than released SHALL leave both the stored order and the in-memory presentation unchanged, so a reorder the user saw started but did not commit does not appear to land and then revert. The collection description SHALL NOT be rendered on the Home card, which stays limited to the name, one status line, progress, and member thumbnails.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection with its name, mode icon, and concise mode-relevant state

#### Scenario: Cards shown in stored order
- **WHEN** the Home collections section is shown
- **THEN** the cards appear in the collection's stored display order

#### Scenario: Reordering a collection card
- **WHEN** the user presses and holds a collection card and drags it to another position
- **THEN** the card follows the drag, the other cards move aside, and on release the new order is persisted

#### Scenario: Reordered collections persist
- **WHEN** the user reorders collections and later returns to Home, including after closing and reopening the screen
- **THEN** the collections are presented in the order the user left them

#### Scenario: Drag distinguished from scrolling
- **WHEN** the user scrolls the Home screen with a swipe that begins on a collection card
- **THEN** the screen scrolls and no card is picked up for reordering

#### Scenario: Reorder abandoned
- **WHEN** the user picks up a card and releases it at its original position
- **THEN** the order is unchanged and no reorder is persisted

#### Scenario: Reorder cancelled mid-drag
- **WHEN** the user picks up a collection card to reorder and the drag is cancelled before a clean release
- **THEN** the stored order is unchanged and the in-memory presentation reverts to that stored order, leaving no
  stale reorder that would revert on the next visit

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
- **THEN** its card presents compact structured information relevant to that mode without an uppercase
  mode heading or a multi-detail sentence

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
- **WHEN** a collection's mode is completion goal and achievement counts exist for one or more
  members
- **THEN** its Home card shows aggregate unlocked out of total trophies and the remaining count

#### Scenario: Accent tint applied
- **WHEN** a collection has a stored accent
- **THEN** its card surface and accent affordances use a low-opacity tint from that accent while
  its text remains legible

#### Scenario: Cards remain compact
- **WHEN** the Home screen shows multiple collection cards
- **THEN** each card uses compact internal padding and consecutive cards have visible spacing without
  excessive vertical gaps

#### Scenario: Collection member thumbnail preview
- **WHEN** a Home collection card has one or more members
- **THEN** the card shows up to three small member-game thumbnails in stored member order on the right

#### Scenario: Collection member thumbnail overflow
- **WHEN** a Home collection card has more than three members
- **THEN** the card shows three thumbnails followed by the number of remaining members using the existing `N+`
  convention, such as `8+` for an eleven-game collection

#### Scenario: Default styling without accent
- **WHEN** a collection has no stored accent
- **THEN** its card presents the default neutral styling

#### Scenario: Opening an existing collection
- **WHEN** the user taps a collection card on Home
- **THEN** a read-only overview of that collection is opened, with its selected games and local
  collection metrics visible before customization controls

#### Scenario: No collections
- **WHEN** the Home screen is shown and no collections exist
- **THEN** the collections section presents an empty state rather than an empty list

#### Scenario: Offline rendering
- **WHEN** the Home screen is shown without network
- **THEN** the collections section renders from the last stored state without blocking
#### Scenario: Existing Home surfaces preserved
- **WHEN** the collections section is shown on Home
- **THEN** the level, XP, daily-quest, streak, and now-playing surfaces remain present and unchanged

### Requirement: Collection add-game genre filtering
The collection management screen SHALL provide a compact multi-select genre control for the Add games pool. With multiple genres selected, a non-member game SHALL remain addable when it has any selected genre. When both a text query and genre selections are active, the game SHALL satisfy the text query and at least one selected genre. Filtering SHALL never change collection membership by itself. The text query SHALL match a game's name or any known genre label, ignoring case, and offered games SHALL be presented in the same strongest-match-first order the Library search uses. The Add games search field and the games it offers SHALL be positioned so that results remain visible while the user is typing into the field. Adding an offered game SHALL NOT cause a disorienting scroll reset or viewport jump that displaces the Add games pool from view, and the search field SHALL retain focus across the add so the keyboard is not dismissed by the act of adding.

#### Scenario: No genre selected
- **WHEN** the user has selected no genre filter
- **THEN** every non-member game allowed by the text query remains in the Add games pool

#### Scenario: One genre selected
- **WHEN** the user selects one genre
- **THEN** only non-member games carrying that genre and satisfying any active text query are offered

#### Scenario: Additional genre selected additively
- **WHEN** the user selects another genre while one or more genres are already selected
- **THEN** the pool expands to include non-member games carrying any selected genre rather than requiring every
  selected genre

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

#### Scenario: Adding a game does not reset the scroll position
- **WHEN** the user taps to add an offered game while the Add games search is in view
- **THEN** the form's scroll position does not jump to the top, the offered games remain visible, and the search
  field retains focus so the keyboard is not dismissed by the add

#### Scenario: Clearing selected genres
- **WHEN** the user clears all selected genres
- **THEN** genre filtering is removed without changing draft collection membership or the text query

#### Scenario: Filtering never bulk-adds games
- **WHEN** the user selects or clears a genre
- **THEN** no game is added to or removed from the draft collection automatically

## ADDED Requirements

### Requirement: Collection management form keyboard behavior
When the collection management form is shown and the software keyboard (IME) is raised, the form content SHALL be presented flush against the top of the keyboard with no blank band between the keyboard and the content, and the form's primary save action SHALL remain reachable without first dismissing the keyboard. The keyboard inset SHALL be owned at a single layer so it is not double-applied.

#### Scenario: No blank gap above the keyboard
- **WHEN** the user focuses a text field in the collection management form and the keyboard is raised
- **THEN** the form content sits flush against the top of the keyboard with no visible blank band between them

#### Scenario: Save reachable while typing
- **WHEN** the keyboard is raised over the collection management form
- **THEN** the save action remains reachable without the user first dismissing the keyboard

#### Scenario: Keyboard inset owned once
- **WHEN** the keyboard is raised and lowered over the collection management form
- **THEN** the content adjusts by exactly the keyboard height, with no double-reserved space

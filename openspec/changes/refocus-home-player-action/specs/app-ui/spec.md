## ADDED Requirements

### Requirement: Home prioritizes the player's next action
Home SHALL present one concise next-action surface ahead of the level, quest, and streak summaries. While a game is currently running, the now-playing panel SHALL remain the first and most prominent Home surface. The next action SHALL be derived only from locally stored Focus and collection data and SHALL open the relevant game, collection, Library, or planning destination.

#### Scenario: Continue a Focus game
- **WHEN** Home has one or more incomplete Focus games and no game is currently running
- **THEN** Home leads with a next-action surface for the most recently played eligible Focus game

#### Scenario: Continue a collection mission
- **WHEN** no eligible Focus game exists and the first ordered-queue collection in the player's collection display order has an incomplete next game (skipping basic, completion-goal, deadline-goal, empty, and completed-queue collections, which expose no `nextUp`)
- **THEN** Home leads with a next-action surface that opens that collection or its next game

#### Scenario: No next action is derivable
- **WHEN** neither Focus nor collection data yields an incomplete game
- **THEN** Home presents a compact action to browse the Library or create a Focus choice rather than an empty recommendation card

#### Scenario: Now playing remains primary
- **WHEN** live status identifies a currently running game
- **THEN** the now-playing panel remains above the next-action surface and the next action does not duplicate the running game as a competing recommendation

### Requirement: Home collection actions are understandable and accessible
Home SHALL present one primary collection action and SHALL keep secondary collection and release-gap actions available without crowding the section heading. Reordering SHALL have a visible affordance and equivalent accessibility actions that do not require a long press or drag gesture.

#### Scenario: Collection section actions
- **WHEN** the Collections section is shown
- **THEN** its heading exposes one visually primary action and groups the remaining actions as clearly labeled secondary choices

#### Scenario: Reorder with touch
- **WHEN** the player drags a visible collection reorder affordance and releases it at a new position
- **THEN** the new order is persisted with the same cancellation and scroll-arbitration guarantees as the existing reorder behavior

#### Scenario: Reorder with accessibility actions
- **WHEN** assistive technology focuses a reorderable collection card
- **THEN** named move-up and move-down actions are available whenever that move is possible and announce the resulting position

### Requirement: Home loading never appears broken
Home SHALL retain locally available content during refresh. Before any locally renderable Home state exists, it SHALL show a stable loading presentation rather than an empty content area.

#### Scenario: First Home load
- **WHEN** Home state is still loading and no cached presentation is available
- **THEN** the screen shows a bounded progress or skeleton presentation in the normal Home layout

#### Scenario: Refresh with cached content
- **WHEN** Home refreshes while locally stored content is already available
- **THEN** the existing content remains visible and the updating state is communicated without replacing the screen with blank space

### Requirement: Home celebrations respect reduced motion
Level-up and streak-milestone presentations SHALL preserve the earned-event message while avoiding animated playback when the system requests reduced motion.

#### Scenario: Celebration with reduced motion
- **WHEN** a level-up or streak milestone is pending and reduced motion is requested
- **THEN** Home shows a static earned-state treatment, delivers the applicable non-visual feedback, and acknowledges the event exactly once without playing the Lottie motion

#### Scenario: Celebration with motion allowed
- **WHEN** a level-up or streak milestone is pending and reduced motion is not requested
- **THEN** the existing inline celebration behavior remains available

### Requirement: Home copy follows Android locale resources
User-visible Home labels, quantities, and formatted dates SHALL come from Android resources and locale-aware formatting rather than fixed English composition in the screen.

#### Scenario: Localized Home
- **WHEN** the device uses a supported non-default locale
- **THEN** Home resolves its labels and pluralized day/minute copy from that locale without changing stored values or navigation behavior

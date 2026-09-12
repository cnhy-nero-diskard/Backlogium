## ADDED Requirements

### Requirement: Gap-plan builder entry and setup
The system SHALL expose the gap-plan builder as a pushed experience reachable from Home and the
Collections surface rather than as a new bottom navigation destination. Setup SHALL collect an
anticipated title, future target date, Story or Completionist intent, and whether unplayed and
already-started games may be included. When Personal Pace is learning, setup SHALL collect a
positive total-hours budget and explain why it is needed.

#### Scenario: Open from Home
- **WHEN** the player activates the gap-planning entry on Home
- **THEN** the setup surface opens without changing the bottom navigation destinations

#### Scenario: Open from Collections
- **WHEN** the player activates the gap-planning entry on the Collections surface
- **THEN** the same setup behavior opens

#### Scenario: Reliable pace setup
- **WHEN** Personal Pace is reliable
- **THEN** setup identifies recent tracked activity as the capacity source and does not require manual hours

#### Scenario: Learning pace setup
- **WHEN** Personal Pace is learning
- **THEN** setup explains that tracked history is insufficient and requires a positive one-off total-hours budget

### Requirement: Three single-game suggestions
The result surface SHALL present exactly three suggestions — Relaxed, Balanced, and Full — one game
each. It SHALL state the request's full forecast capacity once for all three, and each SHALL then
show its own share of that capacity, its pick's selected-basis remaining time, and its capacity
source. A tier that plans below full capacity SHALL make the withheld portion visible rather than
presenting its reduced share as all the time the player has.

Each pick SHALL show the facts that let the player judge it without leaving the surface: Store
genres, Steam review description with review count, and current player count for a multiplayer pick.
Missing HLTB coverage, ratings, genres, or live counts SHALL be disclosed or omitted without
rendering a false zero and without blocking otherwise valid suggestions.

#### Scenario: Three picks presented
- **WHEN** generation succeeds
- **THEN** the result distinguishes Relaxed, Balanced, and Full, each offering one game of a
  different length

#### Scenario: Withheld capacity stays visible
- **WHEN** the Relaxed tier uses 70% of the forecast capacity
- **THEN** the surface shows both that tier's own share and the full forecast capacity, so the
  deliberately withheld time is not presented as unavailable

#### Scenario: Pick facts are shown
- **WHEN** a pick has cached genres, a cached review summary, and an available player count
- **THEN** its card presents all of them alongside its remaining time

#### Scenario: Incomplete HLTB coverage
- **WHEN** library games were excluded because the selected estimate is missing
- **THEN** the result states that suggestion coverage is incomplete without treating those games as short or complete

#### Scenario: Offline result
- **WHEN** the device is offline
- **THEN** locally selected picks render immediately, unavailable live facts are omitted or identified as unavailable, and no blocking network error replaces them

#### Scenario: Family-shared suggestion
- **WHEN** a family-shared game is suggested
- **THEN** the game is visibly identified as family-shared

#### Scenario: A tier with nothing that fits
- **WHEN** no eligible game fits a tier's share of capacity
- **THEN** that tier says so and the other tiers still present their picks

### Requirement: Recommendations are read at a glance
The result SHALL read as a recommendation the player is being offered, not as a description of a
gap, and SHALL be legible without reading prose. All three recommendations SHALL be simultaneously
visible on a typical phone screen without scrolling, which means the setup inputs SHALL collapse to
a summary once a result exists rather than continuing to occupy the surface above it.

Each recommendation SHALL occupy its own bounded, outlined card, so the region that responds to a
touch is visible before it is touched rather than appearing only as a highlight once it is.

Quantities that exist in proportion to one another — a tier's share against the full forecast, and a
started game's progress against its estimate — SHALL be shown as proportional indicators rather than
stated as separate sentences. A single indicator SHALL be able to carry the full forecast, the
tier's share of it, the withheld remainder, and the pick's own length together.

Every factual indicator SHALL lead with an icon and carry the shortest form of its value that
remains unambiguous. Review standing SHALL additionally be conveyed by colour, derived from the
ratio of positive to total reviews rather than by matching the review description's words, because
that description is localized text and a word match would silently mis-colour every non-English
response.

#### Scenario: All three fit one screen
- **WHEN** a result is generated on a typical phone screen
- **THEN** all three recommendations are visible at once, and the setup inputs are collapsed to a
  summary that can be reopened to change them

#### Scenario: A card's bounds are visible before it is touched
- **WHEN** the player looks at a recommendation without touching it
- **THEN** the card carries a visible outline marking the region that will respond

#### Scenario: Proportions are drawn, not narrated
- **WHEN** a tier plans below the full forecast capacity
- **THEN** its share, the withheld remainder, and the pick's own length are carried by one
  proportional indicator rather than by separate sentences stating each figure

#### Scenario: Review standing is coloured by ratio
- **WHEN** a pick's cached review summary reports mostly positive, mixed, or mostly negative counts
- **THEN** its review indicator is coloured to match that standing, and the colour follows the
  positive-to-total ratio rather than the wording of the description

#### Scenario: A matched genre stands out
- **WHEN** a pick matches a genre the player has recently been playing
- **THEN** the genre itself is emphasised within the statement rather than reading as ordinary text

### Requirement: Inspect a suggestion without leaving the surface
Activating a suggestion SHALL open a compact game-detail overlay over the gap-plan surface, using
the same partial-height overlay treatment the Collection surface uses for its own game detail. The
overlay SHALL leave part of the gap-plan result visible above it, SHALL be dismissible by its own
control and by system back, and both SHALL return to the result with its picks unchanged. Opening or
dismissing the overlay SHALL NOT reroll, regenerate, or otherwise alter the suggestions.

#### Scenario: Open a suggestion's detail
- **WHEN** the player activates a suggested game's card
- **THEN** a partial-height game-detail overlay rises over the gap-plan result, which remains partly visible above it

#### Scenario: Dismiss returns to the same picks
- **WHEN** the player dismisses the overlay by its control or by system back
- **THEN** the gap-plan result returns with exactly the same three picks it had before

#### Scenario: Inspection is not a reroll
- **WHEN** the player opens and closes several suggestions in turn
- **THEN** no suggestion changes as a result

### Requirement: Rebuilding produces a visibly different result
The result surface SHALL offer exactly one control that produces a new set of suggestions, and
activating it SHALL change the picks whenever the eligible pool allows a different set. The surface
SHALL NOT present two controls for the same action, and SHALL NOT offer a control whose activation
leaves the result unchanged without saying why.

#### Scenario: Rebuild rerolls
- **WHEN** the player activates the rebuild control and more eligible candidates exist than those shown
- **THEN** a different set of three picks replaces the previous one

#### Scenario: Rebuild cannot vary
- **WHEN** the eligible pool is too small to produce a different set
- **THEN** the surface says so rather than appearing to have ignored the action

#### Scenario: Inputs change while a result is open
- **WHEN** recommendation inputs change while an unaccepted result is open
- **THEN** the displayed picks remain stable until the player rebuilds

### Requirement: Save an accepted suggestion
Each tier with a pick SHALL offer an explicit action to review it and create a deadline collection.
The confirmation SHALL show the generated collection name, target date, HLTB basis, and the game it
will contain. A successful save SHALL navigate to or expose the created collection; a failed save
SHALL stop its busy indication and keep the suggestion available for retry.

#### Scenario: Confirm save
- **WHEN** the player confirms the reviewed details
- **THEN** the accepted pick is submitted for atomic deadline-collection creation

#### Scenario: Save succeeds
- **WHEN** deadline-collection creation succeeds
- **THEN** the stable created collection becomes available through the normal collection experience

#### Scenario: Save fails
- **WHEN** deadline-collection creation fails
- **THEN** the UI reports the failure, becomes interactive again, and retains the accepted suggestion for retry

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

### Requirement: Three explainable plan results
The result surface SHALL present Relaxed, Balanced, and Full variants distinctly. It SHALL state the
request's full forecast capacity once for all three variants, and each variant SHALL then show its own
budget, its planned time, its remaining reserve measured against that variant's own budget, its
capacity source, and its suggested games. Stating only the per-variant figures is not sufficient: a
variant that plans below full capacity SHALL make the withheld portion visible rather than presenting
its reduced budget as all the time the player has. Each game SHALL show selected-basis remaining time
and concise available recommendation reasons. Missing HLTB coverage, ratings, genre affinity, or live
counts SHALL be disclosed without rendering a false zero or blocking otherwise valid results.

#### Scenario: Three variants generated
- **WHEN** generation succeeds
- **THEN** the result distinguishes Relaxed, Balanced, and Full plans and their different time budgets

#### Scenario: Withheld capacity stays visible
- **WHEN** a Relaxed plan budgets 70% of the forecast capacity and fills nearly all of it
- **THEN** the surface shows both the small reserve against the Relaxed budget and the full forecast capacity, so the deliberately withheld time is not presented as unavailable

#### Scenario: Incomplete HLTB coverage
- **WHEN** library games were excluded because the selected estimate is missing
- **THEN** the result states that recommendation coverage is incomplete without treating those games as short or complete

#### Scenario: Offline result
- **WHEN** the device is offline
- **THEN** cached local plans render immediately, unavailable live facts are omitted or identified as unavailable, and no blocking network error replaces the plans

#### Scenario: Family-shared recommendation
- **WHEN** a family-shared game is recommended
- **THEN** the game is visibly identified as family-shared

### Requirement: Stable result interaction
The result surface SHALL hold one generated snapshot stable until the player explicitly regenerates
or edits it. The player SHALL be able to remove a suggested game or replace it with another eligible
candidate without exceeding the selected variant's budget. In-flight enrichment belonging to an
abandoned generation SHALL NOT update its replacement, and enrichment belonging to the current
generation SHALL only reorder rows or add explanations — it SHALL NOT change which games a variant
contains after the player has been shown it.

#### Scenario: Remove a game
- **WHEN** the player removes a game from a variant
- **THEN** the variant updates its planned and reserve time while the other accepted choices remain

#### Scenario: Replacement would exceed budget
- **WHEN** a replacement candidate would make the variant exceed its budget
- **THEN** that replacement cannot be confirmed as part of the variant

#### Scenario: Explicit regeneration
- **WHEN** the player regenerates after recommendation inputs changed
- **THEN** a new snapshot replaces the prior unaccepted result

#### Scenario: Live counts arrive after the plan renders
- **WHEN** current-player counts for the current generation resolve while the player is reading the result
- **THEN** the same games remain in the same variants, gaining live explanations and multiplayer row ordering only

### Requirement: Save accepted variant
Each non-empty variant SHALL offer an explicit action to review its final membership and create a
deadline collection. The confirmation SHALL show the generated collection name, target date, HLTB
basis, and member count. A successful save SHALL navigate to or expose the created collection; a
failed save SHALL stop its busy indication and keep the preview available for retry.

#### Scenario: Confirm save
- **WHEN** the player confirms the reviewed plan details
- **THEN** the accepted variant is submitted for atomic deadline-collection creation

#### Scenario: Save succeeds
- **WHEN** deadline-collection creation succeeds
- **THEN** the stable created collection becomes available through the normal collection experience

#### Scenario: Save fails
- **WHEN** deadline-collection creation fails
- **THEN** the UI reports the failure, becomes interactive again, and retains the accepted preview for retry

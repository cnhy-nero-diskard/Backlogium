## Purpose

Defines rerollable, deadline-constrained single-game suggestions — one per planning intensity —
drawn from playable library games that fit either a player's reliable Personal Pace or an explicit
one-off time budget, and presented with the facts needed to judge them.

## ADDED Requirements

### Requirement: Gap plan request
The system SHALL let the player create a gap-plan request with a non-empty anticipated-title label,
a future local target date, and either Story or Completionist play intent. The target date SHALL be
no more than 1,095 days after the current local date, the same planning horizon the existing pace
fit derivation applies. The request SHALL allow unplayed games and already-started unfinished games
to be included, with both included by default.

#### Scenario: Complete request
- **WHEN** the player supplies an anticipated title, a date after the current local date, and a play intent
- **THEN** the system can generate gap-plan suggestions from the request

#### Scenario: Target date is not in the future
- **WHEN** the selected target date is today or earlier
- **THEN** generation remains unavailable until the player selects a future date

#### Scenario: Target date is beyond the planning horizon
- **WHEN** the selected target date is more than 1,095 days after the current local date
- **THEN** generation remains unavailable, because capacity is projected one day at a time and a
  forecast over that span is neither bounded work nor a meaningful plan

#### Scenario: Started games are included
- **WHEN** the request permits started games
- **THEN** an unfinished game with recorded playtime remains eligible based on its remaining selected-basis time

### Requirement: Capacity source and planning window
The system SHALL calculate plan capacity from tomorrow through the target date inclusive. When the
Personal Pace profile is reliable, capacity SHALL be its projected gaming minutes for that range.
When the profile is learning, the system SHALL require a positive one-off total-hours budget instead
and SHALL identify the resulting suggestions as manually budgeted rather than Personal Pace
forecasts.

#### Scenario: Reliable Personal Pace
- **WHEN** the player generates a request with a reliable Personal Pace profile
- **THEN** the full capacity equals the profile's expected gaming minutes from tomorrow through the target date inclusive

#### Scenario: Personal Pace is learning
- **WHEN** the player generates a request while Personal Pace is learning
- **THEN** generation requires a positive total-hours budget and does not make a Personal Pace feasibility claim

#### Scenario: Manual budget supplied
- **WHEN** the player supplies a positive total-hours budget for a learning profile
- **THEN** that duration becomes the full capacity and the suggestions are labeled as manually budgeted

### Requirement: One pick per planning intensity
Each successful request SHALL produce exactly three picks — Relaxed, Balanced, and Full — each
containing exactly one game, whose selected-basis remaining time SHALL NOT exceed that tier's share
of the request's full capacity: respectively 70%, 85%, and 100%.

A tier SHALL **target** its share rather than merely cap it: the pick SHALL be drawn from the
eligible candidates nearest that tier's share, so the three represent three meaningfully different
lengths of commitment. Treating a share only as a ceiling is insufficient, because every tier could
then return the same very short game and the choice between tiers would carry no information.

The three picks SHALL be distinct games.

The three picks SHALL also be ordered by commitment: the Relaxed pick's remaining time SHALL NOT
exceed the Balanced pick's, which SHALL NOT exceed the Full pick's. This holds unconditionally, not
only when the pool spans the request's capacity. A lower intensity offering a longer game than a
higher one contradicts what the tier labels promise, and a pool whose eligible games cluster well
below every share — ordinary for a library with few resolved lengths — otherwise produces exactly
that.

#### Scenario: Tiers differ in length
- **WHEN** the eligible pool contains games spanning the full range of the request's capacity
- **THEN** each pick approaches its own share of capacity rather than sitting arbitrarily far below
  it, so the three represent three meaningfully different lengths of commitment

#### Scenario: A clustered pool still orders its tiers
- **WHEN** every eligible candidate sits well below all three shares, so several are equally near
  more than one tier
- **THEN** the picks are still ordered shortest to longest across Relaxed, Balanced, and Full,
  rather than landing in whatever tiers they were drawn for

#### Scenario: Intensity ceilings
- **WHEN** the full capacity is 6,000 minutes
- **THEN** the Relaxed pick needs at most 4,200 minutes, Balanced at most 5,100, and Full at most 6,000

#### Scenario: Picks are distinct
- **WHEN** one game is the nearest fit for more than one tier
- **THEN** it occupies only one of them and the others take their next-nearest eligible candidate

#### Scenario: No game fits a tier
- **WHEN** no eligible candidate fits a tier's share of capacity
- **THEN** that tier has no pick and explains that no covered game fits its available time, without
  preventing the other tiers from offering theirs

### Requirement: Candidate eligibility and remaining work
The system SHALL derive candidates from visible Steam-owned and family-shared library games. It
SHALL exclude hidden games, games disallowed by the request's started/unplayed selection, games
already complete at the selected HLTB basis, games lacking a positive estimate for that basis, and
games whose remaining time cannot fit even the Full share. Remaining work SHALL be
`max(selected estimate - truthful displayed playtime, 0)`.

#### Scenario: Family-shared game is eligible
- **WHEN** a visible family-shared game has a positive selected estimate and remaining work that fits
- **THEN** it can be suggested and its source remains identifiable

#### Scenario: Partially played game
- **WHEN** a permitted game has 300 played minutes against a 900-minute selected estimate
- **THEN** its required work is 600 minutes

#### Scenario: Estimate is missing
- **WHEN** a game lacks a positive estimate for the selected basis
- **THEN** it is excluded as unknown rather than treated as zero work, and the result reports incomplete HLTB coverage

#### Scenario: Recommendation derivation does not trigger HLTB lookup
- **WHEN** candidate eligibility is derived
- **THEN** the system reads cached or applied-dataset HLTB state and issues no implicit HLTB request

#### Scenario: Game already meets the selected estimate
- **WHEN** truthful displayed playtime is at or beyond the selected estimate
- **THEN** the game is excluded as already complete for this request

### Requirement: Unweighted selection among eligible candidates
Within the candidates eligible for a tier, the system SHALL select uniformly at random. It SHALL NOT
rank or weight candidates by Steam review quality, genre affinity, completion momentum, or any
composite of them, and SHALL NOT present a selection score to the player.

Those signals SHALL instead be presented as facts about a pick, so the player decides whether a
suggestion is worth their time. A missing rating or genre signal SHALL remain distinguishable from a
poor one, SHALL NOT make a fitting game ineligible, and SHALL NOT be rendered as a zero.

#### Scenario: A well-reviewed game holds no selection advantage
- **WHEN** two candidates are equally near a tier's target and one has far stronger Steam reviews
- **THEN** both remain equally likely to be picked, and the difference is shown on whichever is
  picked rather than deciding which is picked

#### Scenario: An un-enriched game can still be suggested
- **WHEN** a fitting candidate has no cached review summary or Store genres
- **THEN** it remains equally eligible, and its card identifies the missing facts as unavailable
  rather than showing a fabricated rating or genre

#### Scenario: Selection uses no personal preference signal
- **WHEN** the player's recent history strongly favours one genre
- **THEN** that history does not make its games more likely to be picked, though it may still be
  stated on a pick that matches it

### Requirement: Rerollable picks with a stable shown result
Each generation SHALL draw a seed. Identical request inputs, identical local state, and an identical
seed SHALL produce identical picks. When an exact-difference fallback is used for a reroll, the
previous visible app-id sequence SHALL be retained as part of the snapshot's replay state, and
replaying that snapshot SHALL use the retained sequence together with its seed. An explicit reroll
SHALL draw a new seed.

A shown result SHALL remain stable until the player rerolls or edits it, so a suggestion can be
considered and committed to rather than changing underfoot. A reroll SHALL produce a visibly
different set of picks whenever the eligible pool is large enough to allow one.

#### Scenario: Reroll changes the picks
- **WHEN** the player rerolls and more eligible candidates exist than the three already shown
- **THEN** the picks change, rather than repeating the previous set

#### Scenario: The shown result holds still
- **WHEN** ratings, Personal Pace, playtime, or live counts change while a result is open
- **THEN** the displayed picks remain exactly as shown until the player rerolls

#### Scenario: An exact fallback is replayable
- **WHEN** a reroll uses the exact-difference fallback and the same request inputs are replayed
- **THEN** the retained previous visible app-id sequence and seed reproduce the fallback's picks

#### Scenario: A pool too small to vary
- **WHEN** the eligible pool cannot produce a different set
- **THEN** the result says so rather than appearing to have ignored the reroll

#### Scenario: Accepted picks are unaffected by later change
- **WHEN** recommendation inputs change after the player confirms a pick for collection creation
- **THEN** the created collection retains exactly the accepted game

### Requirement: Picks are presented with the facts that judge them
Every pick SHALL show its selected-basis remaining time, its Store genres, its Steam review
description with review count, and — for a pick whose cached participation categories identify
multiplayer, online co-op, or a massively multiplayer mode — its current player count. Any of these
facts that is unavailable SHALL be omitted or identified as unavailable rather than rendered as a
zero, an empty rating, or a fabricated genre. The system SHALL NOT present an unexplained composite
score as justification.

#### Scenario: A fully enriched pick
- **WHEN** a pick has cached genres, a cached review summary, and an available player count
- **THEN** all four facts are shown alongside its remaining time

#### Scenario: A single-player pick
- **WHEN** a pick advertises no multiplayer participation category
- **THEN** no player count is shown for it and none is requested

#### Scenario: Sparse metadata
- **WHEN** a pick has neither cached reviews nor cached genres
- **THEN** its remaining time is still shown and the absent facts are omitted rather than invented

### Requirement: Live counts decorate finalized picks only
Pick selection SHALL be derived entirely from local state, and all three picks SHALL finalize before
any current-player lookup is issued. After they finalize, the system MAY fetch a current-player
count for a pick whose cached Store participation categories identify multiplayer, online co-op, or
a massively multiplayer mode. An available count SHALL supply a factual statement about that pick
and nothing more; it SHALL NOT change which game any tier offers. An unavailable count SHALL NOT
exclude a pick or prevent local suggestions from being produced.

#### Scenario: Single-player pick
- **WHEN** a pick has no multiplayer participation category
- **THEN** gap-plan generation issues no current-player lookup for it

#### Scenario: Multiplayer count is available
- **WHEN** a multiplayer pick has an available current-player count
- **THEN** the count appears as a factual statement on that pick

#### Scenario: Live lookup is unavailable
- **WHEN** a current-player lookup fails or the device is offline
- **THEN** the locally selected picks remain usable and the missing count is not interpreted as zero players

#### Scenario: Enrichment cannot change the picks
- **WHEN** the same request and seed are generated once with every live lookup succeeding and once with every live lookup failing
- **THEN** both runs offer exactly the same three games in the same tiers, differing only in the presence of live player counts

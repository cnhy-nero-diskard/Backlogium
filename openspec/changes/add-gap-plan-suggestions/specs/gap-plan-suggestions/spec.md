## Purpose

Defines explainable, deadline-constrained bundles of playable library games that fit either a
player's reliable Personal Pace or an explicit one-off time budget.

## ADDED Requirements

### Requirement: Gap plan request
The system SHALL let the player create a gap-plan request with a non-empty anticipated-title label,
a future local target date, and either Story or Completionist play intent. The request SHALL allow
unplayed games and already-started unfinished games to be included, with both included by default.

#### Scenario: Complete request
- **WHEN** the player supplies an anticipated title, a date after the current local date, and a play intent
- **THEN** the system can generate gap plans from the request

#### Scenario: Target date is not in the future
- **WHEN** the selected target date is today or earlier
- **THEN** generation remains unavailable until the player selects a future date

#### Scenario: Started games are included
- **WHEN** the request permits started games
- **THEN** an unfinished game with recorded playtime remains eligible based on its remaining selected-basis time

### Requirement: Capacity source and planning window
The system SHALL calculate plan capacity from tomorrow through the target date inclusive. When the
Personal Pace profile is reliable, capacity SHALL be its projected gaming minutes for that range.
When the profile is learning, the system SHALL require a positive one-off total-hours budget instead
and SHALL identify the resulting plans as manually budgeted rather than Personal Pace forecasts.

#### Scenario: Reliable Personal Pace
- **WHEN** the player generates a request with a reliable Personal Pace profile
- **THEN** the full plan budget equals the profile's expected gaming minutes from tomorrow through the target date inclusive

#### Scenario: Personal Pace is learning
- **WHEN** the player generates a request while Personal Pace is learning
- **THEN** generation requires a positive total-hours budget and does not make a Personal Pace feasibility claim

#### Scenario: Manual budget supplied
- **WHEN** the player supplies a positive total-hours budget for a learning profile
- **THEN** that duration becomes the full plan budget and the plans are labeled as manually budgeted

### Requirement: Three planning intensities
Each successful request SHALL produce Relaxed, Balanced, and Full plan variants whose budgets are
respectively 70%, 85%, and 100% of the request's full capacity. Each variant SHALL contain at most
five distinct games, and the sum of their selected-basis remaining minutes SHALL NOT exceed that
variant's budget.

#### Scenario: Intensity budgets
- **WHEN** the full capacity is 6,000 minutes
- **THEN** Relaxed uses at most 4,200 minutes, Balanced uses at most 5,100 minutes, and Full uses at most 6,000 minutes

#### Scenario: Several short games fit
- **WHEN** more than five eligible short games fit a variant's budget
- **THEN** that variant contains no more than five games

#### Scenario: Only one game fits
- **WHEN** only one eligible candidate can fit a variant's budget
- **THEN** the variant may contain that one game rather than requiring an arbitrary minimum count

#### Scenario: No game fits
- **WHEN** no eligible candidate fits a variant's budget
- **THEN** that variant is empty and explains that no covered game fits its available time

### Requirement: Candidate eligibility and remaining work
The system SHALL derive candidates from visible Steam-owned and family-shared library games. It
SHALL exclude hidden games, games disallowed by the request's started/unplayed selection, games
already complete at the selected HLTB basis, games lacking a positive estimate for that basis, and
games whose remaining time cannot fit even the Full budget. Remaining work SHALL be
`max(selected estimate - truthful displayed playtime, 0)`.

#### Scenario: Family-shared game is eligible
- **WHEN** a visible family-shared game has a positive selected estimate and remaining work that fits
- **THEN** it can be recommended and its source remains identifiable

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

### Requirement: Explainable candidate preference
The system SHALL prefer candidates using selected-basis fit, confidence-adjusted Steam review
quality, recent genre affinity, and completion momentum for already-started games. It SHALL favor
genre variety when otherwise comparable candidates are combined into one plan. A missing rating or
genre signal SHALL remain distinguishable from a poor value and SHALL NOT by itself make a fitting
game ineligible. Identical inputs SHALL produce identical plans with app id as the final tie-breaker.

#### Scenario: Review confidence breaks an otherwise equal choice
- **WHEN** two candidates are otherwise equal and one has the higher confidence-adjusted Steam review quality
- **THEN** the higher-quality candidate is preferred

#### Scenario: Recent genre affinity breaks an otherwise equal choice
- **WHEN** two candidates are otherwise equal and one matches genres represented more strongly in the player's recent completed-session history
- **THEN** the matching candidate is preferred

#### Scenario: Existing progress contributes momentum
- **WHEN** two otherwise equal candidates fit and one has meaningful unfinished progress
- **THEN** the started candidate receives a completion-momentum preference without automatically displacing every unplayed candidate

#### Scenario: Comparable bundle choices differ in variety
- **WHEN** two candidate combinations have comparable fit and quality but one covers a broader set of Store genres
- **THEN** the more varied combination is preferred

#### Scenario: Rating unavailable offline
- **WHEN** a fitting candidate has no cached Steam review summary
- **THEN** it remains eligible with rating identified as unavailable rather than receiving a fabricated zero rating

#### Scenario: Deterministic tie
- **WHEN** all recommendation inputs for two candidates are equal
- **THEN** their Steam app ids provide a stable final ordering

### Requirement: Recommendation explanations
Every recommended game SHALL expose its estimated remaining time and the available reasons that
materially supported its recommendation. Reasons SHALL use recognizable facts such as Steam review
quality and volume, recent genre affinity, existing progress, bundle fit, family-sharing source, and
multiplayer viability; the system SHALL NOT present an unexplained composite score as user-facing
justification.

#### Scenario: Multiple reasons are available
- **WHEN** a game has review, genre, progress, and fit signals
- **THEN** its recommendation presents concise fact-based reasons derived from those signals

#### Scenario: Sparse metadata
- **WHEN** a game is recommended using only duration and library facts
- **THEN** its explanation states the available fit facts without inventing rating, preference, or live-community claims

### Requirement: Optional multiplayer live enrichment
After deriving a bounded local shortlist, the system MAY fetch a current-player count only for a
candidate whose cached Store participation categories identify multiplayer, online co-op, or a
massively multiplayer mode. Available counts SHALL act only as a multiplayer-viability preference
among shortlisted candidates. An unavailable count SHALL NOT exclude a candidate or prevent local
plans from being produced.

#### Scenario: Single-player candidate
- **WHEN** a shortlisted candidate has no multiplayer participation category
- **THEN** gap-plan generation issues no current-player lookup for it

#### Scenario: Multiplayer count is available
- **WHEN** a shortlisted multiplayer candidate has an available current-player count
- **THEN** the count can refine its multiplayer-viability preference and appears as a factual explanation

#### Scenario: Live lookup is unavailable
- **WHEN** a multiplayer current-player lookup fails or the device is offline
- **THEN** the locally derived plan remains usable and the missing count is not interpreted as zero players

### Requirement: Plans remain transient until accepted
Generated variants SHALL be transient recommendation snapshots. Regeneration MAY replace an
unaccepted snapshot, but changes in ratings, Personal Pace, playtime, or live counts SHALL NOT alter
the membership of a plan after the player confirms it for collection creation.

#### Scenario: Inputs change before regeneration
- **WHEN** recommendation inputs change while an unaccepted result is open
- **THEN** the displayed snapshot remains stable until the player explicitly regenerates it

#### Scenario: Plan is accepted
- **WHEN** the player confirms one variant and its current membership
- **THEN** that exact accepted membership is submitted for stable collection creation

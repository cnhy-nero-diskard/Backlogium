## ADDED Requirements

### Requirement: A closed provenance vocabulary
The system SHALL define a closed vocabulary of exactly three provenance terms describing how a
presented figure was obtained, and every figure the app presents that derives from Steam data SHALL
be classified into exactly one of them:

- **Observed** — the value is Steam's own, stored as reported.
- **Tracked** — the amount is Steam's own, but the time window it is attributed to is the app's.
- **Inferred** — the quantity being measured is one the app constructed, not one Steam reported.

Adding a figure without a term SHALL NOT be possible.

#### Scenario: A figure declares one term
- **WHEN** a figure derived from Steam data is presented
- **THEN** exactly one provenance term applies to it

#### Scenario: A new figure forces a decision
- **WHEN** a figure is added to a surface covered by this vocabulary
- **THEN** the classification does not compile or does not pass until a term is supplied for it

#### Scenario: The vocabulary does not grow silently
- **WHEN** a case arises that none of the three terms describes
- **THEN** it is resolved by an explicit change to the vocabulary rather than by omitting a term

### Requirement: Steam-reported values are observed
The system SHALL classify as observed every value Steam reports directly and the app stores without
reinterpretation, including a game's lifetime playtime, an achievement's unlock time, an
achievement's global unlock percentage, and a game's current player count.

#### Scenario: Lifetime playtime
- **WHEN** a game's total Steam playtime is presented
- **THEN** it is classified as observed

#### Scenario: Achievement unlock time
- **WHEN** an achievement's unlock time is presented
- **THEN** it is classified as observed, since Steam reports the moment of unlock rather than the
  app inferring it

#### Scenario: Global unlock percentage
- **WHEN** an achievement's rarity percentage is presented
- **THEN** it is classified as observed

### Requirement: Windowed play totals are tracked
The system SHALL classify as tracked any figure whose amount comes from Steam's playtime counter but
whose attribution to a day, a window, or a game list is decided by the app, including the daily
playtime chart, per-day totals, quest totals, and the most-played-games ranking within a window.

#### Scenario: Daily playtime
- **WHEN** a day's played minutes are presented
- **THEN** they are classified as tracked, because the minutes are Steam's and the day is the app's
  attribution

#### Scenario: Most played within a window
- **WHEN** games are ranked by minutes within a selected window
- **THEN** the ranking is classified as tracked

#### Scenario: Tracked is not presented as doubtful
- **WHEN** a tracked figure is presented
- **THEN** its disclosure conveys that the amount is Steam's own, distinctly from an inferred
  figure's disclosure, rather than expressing the same degree of doubt

### Requirement: Session-shaped figures are inferred
The system SHALL classify as inferred every figure derived from a synthesized session's boundaries,
including a session's start time, the count of sessions, average session length, longest session,
and any bucketing of play by time of day.

#### Scenario: Session start
- **WHEN** a session's start time is presented
- **THEN** it is classified as inferred

#### Scenario: Session count
- **WHEN** a count of sessions is presented
- **THEN** it is classified as inferred, because a session is a unit the app constructs from
  successive polls rather than one Steam reports

#### Scenario: Session length statistics
- **WHEN** an average or longest session length is presented
- **THEN** it is classified as inferred

#### Scenario: Time-of-day bucketing
- **WHEN** play is bucketed by time of day
- **THEN** it is classified as inferred

### Requirement: Provenance is disclosed where the figure is read
The system SHALL disclose a figure's provenance at the figure itself rather than only at the screen
or section level. Where several figures presented together share one term, the system MAY disclose
that term once for the group rather than repeating it per figure.

#### Scenario: Disclosure travels with the figure
- **WHEN** a figure is presented
- **THEN** its provenance is discoverable without leaving the figure's own surface

#### Scenario: A shared term is stated once
- **WHEN** several figures presented in one group all carry the same term
- **THEN** the term may be stated once for the group

#### Scenario: Differing terms are not merged
- **WHEN** figures with different terms appear on one screen
- **THEN** each is disclosed according to its own term, and no single screen-level statement is used
  in place of the per-figure disclosure

### Requirement: The mechanism is explained once and reachable
The system SHALL provide one explanation of how derived figures are produced, reachable from any
inferred figure's disclosure, and SHALL NOT repeat that explanation beside each figure. The
explanation SHALL state that sessions are derived by comparing Steam's cumulative playtime between
periodic checks, that a session's start is the time of the check before the increase appeared, and
that a longer interval between checks moves a session's apparent start earlier than it occurred.

#### Scenario: Reaching the explanation
- **WHEN** the user selects an inferred figure's disclosure
- **THEN** the shared explanation is presented

#### Scenario: The explanation is not duplicated
- **WHEN** several inferred figures are presented on one screen
- **THEN** the full explanation appears at most once, and each figure carries only its compact
  disclosure

#### Scenario: The explanation names the cause
- **WHEN** the explanation is presented
- **THEN** it states that play is attributed to the interval in which it was discovered, so a
  device that was not checking for a long period shifts a session earlier

### Requirement: Time-of-day carries a figure-specific caveat
Because a time-of-day figure's entire content is which interval play fell into, the system SHALL
present, alongside it, a statement that a long interval between checks attributes play to the start
of that interval. This SHALL be in addition to the shared term, not a substitute for it.

#### Scenario: Time-of-day disclosure
- **WHEN** a time-of-day pattern is presented
- **THEN** it carries both the inferred term and a statement that a long gap between checks moves
  play into an earlier part of the day

#### Scenario: A peak claim is qualified
- **WHEN** a peak time-of-day bucket is highlighted
- **THEN** the highlight does not assert the peak as an observation of when the player plays

### Requirement: Provenance never replaces a confidence state
Where a figure also carries a statistical confidence state — such as a Personal Pace forecast that is
reliable or still learning — the system SHALL present one legible statement rather than two stacked
hedges. The confidence state SHALL lead, since it changes with more data, and the provenance term
SHALL remain reachable through the shared explanation.

#### Scenario: A learning forecast
- **WHEN** a Personal Pace forecast is in its learning state
- **THEN** the surface states that the app is still learning, and does not additionally present a
  separate provenance hedge beside it

#### Scenario: A reliable forecast
- **WHEN** a Personal Pace forecast is reliable
- **THEN** its inferred provenance remains discoverable without contradicting or diluting the
  reliability statement

#### Scenario: Confidence is not restated as provenance
- **WHEN** a figure carries only a confidence state and no separate derivation concern
- **THEN** no provenance term is added merely to fill the slot

### Requirement: Provenance is announced, not only rendered
The system SHALL make each figure's provenance available to accessibility services as part of that
figure's description. A visual-only marker — a symbol, a tint, or a typographic treatment — SHALL NOT
be the sole means of disclosure.

#### Scenario: Provenance announced with the figure
- **WHEN** a figure is reached by an accessibility service
- **THEN** its provenance term is announced along with its value

#### Scenario: A visual marker alone is insufficient
- **WHEN** provenance is conveyed visually
- **THEN** an equivalent statement is present in the figure's announced description

#### Scenario: The explanation is reachable non-visually
- **WHEN** the shared explanation is offered from a figure
- **THEN** it is reachable and readable by an accessibility service

### Requirement: Provenance describes, and changes nothing
Adopting the vocabulary SHALL NOT change any stored value, any computation, any request, or which
figures are presented. No provenance term SHALL be persisted per record.

#### Scenario: No figure is removed
- **WHEN** the vocabulary is applied to a surface
- **THEN** every figure that surface presented before is still presented

#### Scenario: No value changes
- **WHEN** the vocabulary is applied
- **THEN** the numbers presented are identical to those presented before

#### Scenario: Nothing is stored
- **WHEN** provenance is determined for a figure
- **THEN** it is determined from the figure's identity, and no per-record provenance is written to
  storage

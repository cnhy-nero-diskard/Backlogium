## ADDED Requirements

### Requirement: Analytics presents personal momentum with independent dated scope
Analytics SHALL present a compact personal momentum card explicitly describing recorded personal activity over the last seven completed local days versus the preceding seven. Both actual date ranges and the fixed scope's independence from the main Analytics period selector SHALL be understandable in visible text and semantics. The card SHALL use the eligibility, ranking, limit, and caveats of personal-play-momentum, and distinguish growth from newly recorded activity. It SHALL not replace the main selected-window headline, label personal momentum as Steam community growth, or duplicate a recent-play collection's membership view.

#### Scenario: Main period selector changes
- **WHEN** the player selects a different Analytics month, year, or rolling window
- **THEN** the personal card retains its own fixed dated comparison and clearly identifies that it describes the recent completed weeks

#### Scenario: Candidate inspected
- **WHEN** an eligible title appears
- **THEN** its current/baseline recorded minutes, growth amount or newly-recorded reason, and both period scopes are inspectable

#### Scenario: Learning or no qualifying increase
- **WHEN** history is insufficient or no title qualifies
- **THEN** the card shows the corresponding concise explanation without unsupported growth or community claims

### Requirement: Personal momentum rows offer accessible safe detail navigation
Momentum game rows SHALL offer named accessible detail actions with adequate touch targets, locale-aware amounts/dates, and textual growth meaning independent of color. Essential scope and caveats SHALL remain legible on narrow screens, larger fonts, and both themes. A title that becomes hidden or unavailable before navigation SHALL produce a clear unavailable result; returning to Analytics SHALL preserve its existing main-period selection.

#### Scenario: Game detail opened and closed
- **WHEN** the player opens a momentum title and returns
- **THEN** the main Analytics selection is preserved and the personal comparison uses current local evidence

#### Scenario: Target visibility changes
- **WHEN** a title is hidden before its detail action executes
- **THEN** no hidden detail is opened and the card refreshes its eligibility with clear action feedback

#### Scenario: Screen reader or larger text
- **WHEN** the player reads a momentum row with assistive technology or larger text
- **THEN** game identity, both amounts, comparison reason, and detail action remain understandable

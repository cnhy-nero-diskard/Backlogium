## ADDED Requirements

### Requirement: History distinguishes current and earlier activity
History SHALL label the current-day group and earlier-day groups according to their actual role. Expandable day and game rows SHALL expose their expanded state and named expand/collapse actions to assistive technology.

#### Scenario: Current day and earlier history
- **WHEN** History contains today and one or more earlier days
- **THEN** the current day is presented as the current activity group and earlier days appear under an earlier-history heading rather than a generic daily-stats label

#### Scenario: Accessible day expansion
- **WHEN** assistive technology focuses an expandable day
- **THEN** it announces the date, played-time summary, quest state, expanded state, and a named expand or collapse action

#### Scenario: Accessible game expansion
- **WHEN** assistive technology focuses an expandable game inside a day
- **THEN** it announces the game, played time, expanded state, and a named expand or collapse action

### Requirement: History explains session measurement
History SHALL provide concise contextual help explaining that session starts are approximate and tracked session minutes can differ from Steam lifetime-counter changes.

#### Scenario: Requesting measurement help
- **WHEN** the player opens the History measurement explanation
- **THEN** the app explains the poll-quantized start time and tracked-minute semantics without implying data loss or an exact start-end interval

### Requirement: Analytics leads with an interpretable insight
Analytics SHALL present a plain-language summary derived from the selected window before secondary chart configuration. It SHALL visually distinguish selected-window figures from all-time figures.

#### Scenario: Window contains activity
- **WHEN** Analytics has tracked activity in the selected window
- **THEN** the first analytical summary names the dominant useful fact from that window, such as total play, active-day pattern, leading game, or change from the immediately preceding comparable window

#### Scenario: Window contains no activity
- **WHEN** the selected window has no tracked activity
- **THEN** Analytics states that directly, retains period navigation, and does not present all-time achievements or streaks as if they described the empty window

### Requirement: Analytics chart inspection is accessible without precise touch
Every day represented by the daily-playtime chart SHALL be inspectable through explicit previous/next-day controls and accessible semantics. Chart meaning SHALL also be available as text, including the selected date, minutes, goal state, and per-game breakdown.

#### Scenario: Inspect next day
- **WHEN** the player activates the next-day control and a later chart day exists
- **THEN** that day becomes selected and its date, minutes, goal state, and game breakdown are updated and announced

#### Scenario: Inspect previous day
- **WHEN** the player activates the previous-day control and an earlier chart day exists
- **THEN** that day becomes selected and its details are updated and announced

#### Scenario: Selection reaches a boundary
- **WHEN** the selected day is the first or last displayed day
- **THEN** the unavailable direction is disabled and its state is exposed to assistive technology

#### Scenario: Direct chart touch remains available
- **WHEN** a sighted touch user selects a chart position
- **THEN** the nearest represented day is selected with the same detail and announcement behavior as explicit navigation

### Requirement: Analytics period navigation is reversible
Analytics SHALL provide earlier, later, and return-to-current controls, enabling only moves that are valid for the selected window and available history.

#### Scenario: Return from an earlier period
- **WHEN** the player is viewing an earlier anchor period
- **THEN** later and return-to-current actions are available and restore the corresponding valid period

#### Scenario: Current period
- **WHEN** the selected window already ends in the current period
- **THEN** later and return-to-current actions are disabled or omitted without disturbing the earlier action

### Requirement: Activity screens follow the device locale
History and Analytics SHALL resolve user-visible strings, dates, casing, quantities, and durations through Android locale resources while preserving the stored local-date boundaries used for attribution.

#### Scenario: Non-US locale
- **WHEN** the device locale does not use US date order or English casing rules
- **THEN** History and Analytics format labels for that locale without changing which local day, month, year, or rolling window the data belongs to

### Requirement: Activity loading states preserve orientation
History and Analytics SHALL show explicit bounded loading states and SHALL keep already available local summaries visible during recomputation.

#### Scenario: Analytics recomputes a window
- **WHEN** the selected period changes and its derived figures are recomputing
- **THEN** the selected period remains visible and the screen communicates updating rather than temporarily presenting empty or unrelated figures

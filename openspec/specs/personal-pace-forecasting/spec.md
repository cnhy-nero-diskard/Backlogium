# personal-pace-forecasting

## Purpose

Defines the local Personal Pace profile used to estimate active gaming capacity for collection
planning without network or Android dependencies.

## Requirements

### Requirement: Local Personal Pace profile
The system SHALL derive Personal Pace entirely from locally stored eligible dated activity over the latest 56 completed local dates. It SHALL sum closed timestamped sessions by start date and eligible single-day unknown-time credits by their frozen allocation dates, exclude the current partial date and open sessions, and issue no network request. Multi-day estimated allocations SHALL be excluded from habit training and confidence. Unresolved dates affected by a multi-day estimate SHALL not be synthesized as observed zero-play dates; mixed dates with incomplete placement SHALL remain unknown for training unless independent evidence establishes their complete dated total. Pending date-unknown quantity SHALL supply no habit observation. The derivation SHALL accept injected date/zone and observation eligibility inputs and have no Android dependency.

#### Scenario: Multiple sessions on one date
- **WHEN** two or more closed sessions start on the same local date
- **THEN** their minutes are summed into one daily observation for Personal Pace

#### Scenario: Current date is incomplete
- **WHEN** the current local date contains tracked play
- **THEN** that date is excluded from profile training until it becomes a completed date

#### Scenario: Open session is incomplete
- **WHEN** a session remains open within the lookback window
- **THEN** it is excluded from profile training until it is closed

#### Scenario: Offline deterministic derivation
- **WHEN** the same stored sessions, date, and zone are supplied repeatedly
- **THEN** the same profile is returned without issuing a network request or reading an Android clock

#### Scenario: Multi-day distribution is not a habit observation
- **WHEN** a gap's minutes are distributed across Tuesday through Friday
- **THEN** those estimates do not train weekday duration or frequency and do not qualify as observed covered or active dates
- **AND** removing the estimate from training does not turn its unresolved dates into observed zero-play days

#### Scenario: Single-day unknown-time play
- **WHEN** a completed date contains a single-day fallback with a known credited date
- **THEN** those minutes can train date-level habits without supplying any time-of-day evidence

#### Scenario: Evidence upgrade restores eligibility
- **WHEN** delayed validated evidence fully resolves an estimated date's activity
- **THEN** the now-eligible dated total participates in the next deterministic profile derivation

### Requirement: Robust habit estimation
The Personal Pace profile SHALL distinguish eligible active dates, eligible zero-minute dates and unknown estimated dates, derive expected active gaming days and typical active-day minutes, give more weight to recent observations, and account for weekday-specific behavior. Duration estimation SHALL use an outlier-resistant statistic so one unusually long day does not define the user's normal capacity. A weekday with sparse active observations SHALL be blended toward the user's global active-day pattern rather than treated as independently reliable.

#### Scenario: Zero-minute dates affect frequency
- **WHEN** eligible completed dates contain no tracked play and are not unresolved multi-day estimated dates
- **THEN** those dates reduce active-day frequency without becoming zero-length active sessions
- **AND** unknown estimated dates contribute neither an active-day observation nor a zero-day observation

#### Scenario: Recent behavior carries more weight
- **WHEN** recent completed dates show a sustained play pattern different from older dates in the window
- **THEN** the resulting profile is closer to the recent pattern than an equally weighted average would be

#### Scenario: Marathon session is an outlier
- **WHEN** one active date is substantially longer than the user's other active dates
- **THEN** it does not by itself raise typical active-day minutes to that outlier duration

#### Scenario: Weekday sample is sparse
- **WHEN** a weekday has fewer than four active observations in the lookback
- **THEN** its expected duration is blended with the global active-day duration

### Requirement: Confidence-aware profile
The system SHALL classify Personal Pace as reliable only when the eligible local history covers at least 14
completed dates and includes at least six eligible active dates. A profile that does not meet both
thresholds SHALL remain in a learning state and SHALL NOT support definitive feasibility or
infeasibility claims.

The two thresholds answer different questions and both SHALL be required. The covered-date
threshold asks whether enough time has been observed to describe a pattern; the active-date
threshold asks whether enough of that time was actually spent playing. Six active dates within a
14-date span is a substantially denser requirement than the same six within 28, so shortening the
observed span SHALL NOT be accompanied by relaxing the active-date floor.

#### Scenario: Sufficient history
- **WHEN** at least 14 completed dates are covered and at least six are active
- **THEN** the Personal Pace profile is classified as reliable

#### Scenario: Too few covered dates
- **WHEN** fewer than 14 completed dates are covered
- **THEN** the profile remains learning regardless of the minutes recorded

#### Scenario: Too few active dates
- **WHEN** fewer than six covered dates contain tracked play
- **THEN** the profile remains learning regardless of the total minutes recorded

#### Scenario: A qualifying profile is reclassified without stored state
- **WHEN** a profile that was learning under the previous threshold already covers at least 14
  completed dates with at least six active
- **THEN** it is classified as reliable on its next derivation from stored sessions, with no
  migration, recompute, or stored confidence value involved

#### Scenario: An already reliable profile is unaffected
- **WHEN** a profile satisfied the previous, longer covered-date threshold
- **THEN** it remains reliable and its expected active days and expected gaming minutes are
  unchanged, because the threshold gates classification only and is not an input to the forecast

#### Scenario: Estimates cannot manufacture reliability
- **WHEN** spreading one recovered increase creates enough estimated dates to appear to cross a confidence threshold
- **THEN** confidence does not increase from those estimated dates

### Requirement: Date-range capacity forecast
Given a Personal Pace profile and an inclusive future local-date range, the system SHALL project expected active gaming days and expected gaming minutes by summing the profile's weekday-adjusted expectations for each date. It SHALL also calculate the minutes required per projected active day when a positive amount of required work is supplied. Forecast outputs SHALL be unformatted numeric values suitable for pure domain tests.

#### Scenario: Forecast across future dates
- **WHEN** a future range spans weekdays with different observed habits
- **THEN** its projected active days and minutes reflect the weekday-adjusted expectations for every date in the range

#### Scenario: Required pace is calculable
- **WHEN** required work is positive and the forecast contains projected active days
- **THEN** the forecast reports required minutes per projected active day

#### Scenario: No projected active days
- **WHEN** the profile projects no active gaming day in the requested range
- **THEN** required pace is unavailable rather than divided by zero

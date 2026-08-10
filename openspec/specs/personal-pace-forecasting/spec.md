# personal-pace-forecasting

## Purpose

Defines the local Personal Pace profile used to estimate active gaming capacity for collection
planning without network or Android dependencies.

## Requirements

### Requirement: Local Personal Pace profile
The system SHALL derive a Personal Pace profile entirely from locally stored synthesized sessions over the latest 56 completed local dates. It SHALL aggregate multiple sessions that start on the same local date, exclude the current partial date and open sessions from profile training, and issue no network request. The derivation SHALL accept injected date and zone inputs and SHALL have no Android dependency.

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

### Requirement: Robust habit estimation
The Personal Pace profile SHALL distinguish active dates from zero-minute dates, derive expected active gaming days and typical active-day minutes, give more weight to recent observations, and account for weekday-specific behavior. Duration estimation SHALL use an outlier-resistant statistic so one unusually long day does not define the user's normal capacity. A weekday with sparse active observations SHALL be blended toward the user's global active-day pattern rather than treated as independently reliable.

#### Scenario: Zero-minute dates affect frequency
- **WHEN** the lookback contains dates with no tracked sessions
- **THEN** those dates reduce expected active-day frequency without being treated as zero-length active sessions

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
The system SHALL classify Personal Pace as reliable only when the local history covers at least 28 completed dates and includes at least six active dates. A profile that does not meet both thresholds SHALL remain in a learning state and SHALL NOT support definitive feasibility or infeasibility claims.

#### Scenario: Sufficient history
- **WHEN** at least 28 completed dates are covered and at least six are active
- **THEN** the Personal Pace profile is classified as reliable

#### Scenario: Too few covered dates
- **WHEN** fewer than 28 completed dates are covered
- **THEN** the profile remains learning regardless of the minutes recorded

#### Scenario: Too few active dates
- **WHEN** fewer than six covered dates contain tracked play
- **THEN** the profile remains learning regardless of the total minutes recorded

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

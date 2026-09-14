## MODIFIED Requirements

### Requirement: Session synthesis by playtime diffing
The system SHALL synthesize play sessions by comparing each game's `playtime_forever`
against the previously stored value, since the Steam Web API does not expose session
or "currently playing" data. This mechanism SHALL apply only to games for which Steam reports
playtime — those in the player's own library — so that a game whose sessions are derived from
observed presence is never also diffed.

The diffed increase SHALL be the sole authority on how many minutes were played. Where a record of
observed presence covers the period the increase was earned in, that record MAY determine the
boundaries of the sessions the increase is written into — including producing more than one session
from a single increase. The record SHALL NOT change the number of minutes synthesized, and its
absence SHALL leave this mechanism behaving exactly as specified without it.

#### Scenario: Playtime increases
- **WHEN** a game's `playtime_forever` is greater than its stored value
- **THEN** an open session for that game is created if none exists, extended by the delta minutes, and its last-increase timestamp updated

#### Scenario: Playtime unchanged
- **WHEN** a game with an open session shows no increase on the next poll
- **THEN** the session is closed with its end time set to the last-increase timestamp

#### Scenario: Playtime decreases
- **WHEN** a game's `playtime_forever` is less than its stored value (e.g. family sharing or refund)
- **THEN** no session is emitted and the decrease does not produce negative playtime

#### Scenario: An increase placed against observed presence
- **WHEN** a playtime increase is observed and a record of observed presence covers the period
- **THEN** the increase may be written as one or more sessions bounded by the observed intervals
- **AND** the minutes across them sum to the observed increase

#### Scenario: No presence record available
- **WHEN** a playtime increase is observed and no record of observed presence covers the period
- **THEN** the increase is written as a single session exactly as it is without the record

#### Scenario: The record cannot change the total
- **WHEN** the observed intervals span more or less time than the diffed increase
- **THEN** the minutes written still equal the diffed increase

### Requirement: Playtime is attributed to a session's start date
Observed playtime SHALL be credited to the local calendar date on which its session began,
not to the date of the poll that observed it. A single poll SHALL be able to credit more
than one date when the sessions it observed began on different dates. A session's minutes
SHALL NOT be divided across dates.

Where several sessions are synthesized from one observed increase, each SHALL be attributed
independently by its own start date. This rule is unchanged by that: more sessions means more start
dates, not divided minutes.

#### Scenario: Session crossing midnight
- **WHEN** a session begins before local midnight and continues after it
- **THEN** all of its minutes are credited to the date on which it began

#### Scenario: Open session extended past midnight
- **WHEN** an already-open session accumulates further minutes on a poll occurring on a
  later date
- **THEN** those minutes are credited to the date the session began, not the date of the
  poll

#### Scenario: One poll spanning two dates
- **WHEN** a single poll observes minutes for one session that began yesterday and another
  that began today
- **THEN** both dates receive their respective minutes

#### Scenario: One increase credited to several dates
- **WHEN** one observed increase is written as several sessions that began on different dates
- **THEN** each date receives the minutes of the sessions that began on it
- **AND** no session's minutes are divided between them

#### Scenario: Crediting a past date reopens its evaluation
- **WHEN** minutes are credited to a date whose quest was previously evaluated
- **THEN** that date's quest status is re-evaluated, and a change from unmet to met is
  persisted

#### Scenario: Attribution does not depend on poll timing
- **WHEN** the same play activity is observed by polls at different times
- **THEN** the date credited is the same in every case

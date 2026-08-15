# steam-sync

## ADDED Requirements

### Requirement: Playtime is attributed to a session's start date
Observed playtime SHALL be credited to the local calendar date on which its session began,
not to the date of the poll that observed it. A single poll SHALL be able to credit more
than one date when the sessions it observed began on different dates. A session's minutes
SHALL NOT be divided across dates.

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

#### Scenario: Crediting a past date reopens its evaluation
- **WHEN** minutes are credited to a date whose quest was previously evaluated
- **THEN** that date's quest status is re-evaluated, and a change from unmet to met is
  persisted

#### Scenario: Attribution does not depend on poll timing
- **WHEN** the same play activity is observed by polls at different times
- **THEN** the date credited is the same in every case

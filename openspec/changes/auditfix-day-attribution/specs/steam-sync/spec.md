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

#### Scenario: Attribution does not depend on which poll observes it
- **WHEN** a session's minutes are observed across several polls, on more than one date
- **THEN** every one of those polls credits the date the session began, regardless of the
  date on which the poll ran

### Requirement: A session's start is known only to within one poll gap
A synthesized session's start SHALL be taken as the previous poll's timestamp. The minutes
a poll observes occurred somewhere in the interval between that poll and the one before it,
and the app has no evidence of where. The scheduler's period is a floor rather than a
guarantee — a deferred poll widens that interval without bound — so the credited date MAY
precede the date on which the play actually occurred.

#### Scenario: Play observed after a deferred poll
- **WHEN** a poll is deferred well past its scheduled period and then observes new minutes
- **THEN** the session starts at the previous poll's timestamp, and its credited date is
  that of the previous poll even if the play itself happened on a later date

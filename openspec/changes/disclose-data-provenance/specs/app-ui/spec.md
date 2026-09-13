## ADDED Requirements

### Requirement: Analytics figures declare their provenance
The Analytics screen SHALL disclose each figure's provenance term where that figure is read. The
daily playtime chart, the most-played-games list, and the quest-met day count SHALL be disclosed as
tracked. The session-insights summary and the time-of-day pattern SHALL be disclosed as inferred.
The achievement-rarity breakdown SHALL be disclosed as observed. The session-insights summary MAY
carry one disclosure for its three figures, since all three share a term.

#### Scenario: Tracked figures disclosed
- **WHEN** the daily playtime chart and the most-played-games list are presented
- **THEN** each conveys that its amounts come from Steam and its window attribution is the app's

#### Scenario: Session insights disclosed once
- **WHEN** the session-insights summary presents session count, average length, and longest session
- **THEN** the inferred term is conveyed for the summary as a whole rather than three times

#### Scenario: Rarity breakdown disclosed as observed
- **WHEN** the achievement-rarity breakdown is presented
- **THEN** it conveys that its percentages are Steam's own

#### Scenario: Terms are not merged into one screen statement
- **WHEN** the Analytics screen presents figures with different terms
- **THEN** each is disclosed by its own term, rather than a single screen-level caveat standing for
  all of them

#### Scenario: Disclosure does not displace the figures
- **WHEN** provenance is disclosed on Analytics
- **THEN** every figure, the window selector, and the chart remain present and usable as before

### Requirement: The Analytics time-of-day pattern states its specific limitation
The time-of-day pattern on the Analytics screen SHALL present, alongside its inferred term, a
statement that a long interval between checks attributes play to the start of that interval, so an
implausible peak can be recognized as an artefact rather than a habit.

#### Scenario: Pattern carries its caveat
- **WHEN** the time-of-day pattern is presented
- **THEN** the statement about long intervals is presented with it

#### Scenario: Peak bucket qualified
- **WHEN** a peak bucket is highlighted
- **THEN** the highlight is presented as the pattern's peak rather than as an observation of when the
  player plays

#### Scenario: Caveat reaches the explanation
- **WHEN** the user selects the time-of-day caveat
- **THEN** the shared explanation of how sessions are derived is presented

### Requirement: The shared derivation explanation is reachable from Analytics and History
The system SHALL make the shared explanation of how sessions are derived reachable from the
Analytics screen's inferred figures and from the History screen's session times, and SHALL present
the same explanation from both.

The History screen's existing approximate-start treatment SHALL be expressed as an instance of the
shared inferred term, using the same wording every other inferred figure uses, rather than as a
presentation local to that screen. No other History behaviour changes.

#### Scenario: Reached from Analytics
- **WHEN** the user selects an inferred figure's disclosure on Analytics
- **THEN** the shared explanation is presented

#### Scenario: Reached from History
- **WHEN** the user selects a session's approximate-start indication on History
- **THEN** the same shared explanation is presented

#### Scenario: One explanation, not two
- **WHEN** the explanation is presented from either screen
- **THEN** its content is identical, so the two screens do not describe the same mechanism
  differently

#### Scenario: History uses the shared wording
- **WHEN** a session's approximate start is presented on History
- **THEN** it carries the shared inferred term rather than wording unique to that screen

#### Scenario: History behaviour otherwise unchanged
- **WHEN** the History screen is shown
- **THEN** its day totals, quest states, expansion behaviour, thumbnails, and empty state are exactly
  as they are today

## MODIFIED Requirements

### Requirement: Surfaces agree on a session's date
Every surface that attributes tracked play to a calendar day SHALL use the local date of the
session's start, so History, Analytics, per-day progress, and quest evaluation cannot disagree about
which day a session belongs to.

Every such surface SHALL additionally disclose that attribution as tracked provenance: the minutes
are Steam's own, while the day they are credited to is the app's attribution, derived from a session
start that is itself an estimate.

#### Scenario: One session, one day, everywhere
- **WHEN** a session begins on one local date and ends on the next
- **THEN** every surface attributes its minutes to the date it began

#### Scenario: Day attribution disclosed as tracked
- **WHEN** a surface presents a day's tracked minutes
- **THEN** it conveys that the amount is Steam's and the day attribution is the app's

#### Scenario: Attribution disclosure does not alter attribution
- **WHEN** the disclosure is presented
- **THEN** the date a session is attributed to is unchanged from the date it is attributed to today

### Requirement: Collection overview Personal Pace presentation
Collection overviews SHALL present Personal Pace detail only for modes that benefit from pacing. They SHALL distinguish reliable forecasts, learning history, and missing estimate data; use approximate human-readable durations; and SHALL show `Change deadline` only when the collection domain marks that action eligible.

A Personal Pace figure is inferred provenance and also carries a confidence state. The overview SHALL
present one statement rather than two: the confidence state leads, and the provenance term SHALL NOT
be presented as an additional hedge beside it.

#### Scenario: Reliable deadline detail
- **WHEN** a deadline overview has a reliable complete Personal Pace forecast
- **THEN** it shows approximate required pace, recent tracked pace, projected capacity, and on-track or at-risk state

#### Scenario: Learning state
- **WHEN** Personal Pace does not yet have sufficient history
- **THEN** the overview explains that Backlogium is learning from tracked activity and makes no definitive fit claim

#### Scenario: Confidence leads, provenance does not stack
- **WHEN** a Personal Pace figure carries both a confidence state and inferred provenance
- **THEN** the confidence state is the statement presented, and no separate provenance hedge is shown
  beside it

#### Scenario: Provenance still reachable
- **WHEN** the user follows the pacing surface's explanation
- **THEN** the shared derivation explanation is reachable, so the figure's basis is discoverable

#### Scenario: Missing estimate detail
- **WHEN** one or more members lack the applicable HLTB estimate
- **THEN** the overview identifies the incomplete estimate count and makes no definitive fit claim

#### Scenario: Conditional deadline action visible
- **WHEN** the collection domain marks deadline intervention eligible
- **THEN** the overview shows the direct `Change deadline` action

#### Scenario: Conditional deadline action hidden
- **WHEN** the collection domain marks deadline intervention ineligible
- **THEN** the overview does not show the direct `Change deadline` action

#### Scenario: Basic list overview
- **WHEN** the collection mode is basic list
- **THEN** the overview presents no Personal Pace section

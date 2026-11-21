## Purpose

Identify explainable growth in a player's recorded library activity using bounded comparable local periods while preserving uncertainty about unrecorded play.

## ADDED Requirements

### Requirement: Personal momentum compares two completed local weeks
Personal momentum SHALL compare the seven completed local dates immediately before today with the preceding seven local dates, using canonical session-start attribution. It SHALL use positive recorded minutes from finalized sessions for eligible visible current library games. Today's activity, open sessions, hidden games, unknown non-library contributors, undated imported/manual credit, and lifetime counters SHALL NOT contribute. Both periods SHALL be labelled with their actual dates; a local date or zone change SHALL recompute their bounds coherently.

#### Scenario: Comparison on October 3
- **WHEN** today's local date is October 3
- **THEN** current activity covers September 26 through October 2 and baseline activity covers September 19 through September 25

#### Scenario: Open or overnight session
- **WHEN** a session crosses midnight or remains open
- **THEN** a finalized session contributes on its canonical start date, while an open session contributes to neither momentum period until finalized

#### Scenario: Lifetime import
- **WHEN** a title has imported lifetime minutes without corresponding dated finalized sessions
- **THEN** those minutes do not manufacture personal momentum

### Requirement: Personal momentum uses explicit activity and growth thresholds
A growth candidate SHALL have at least 60 current-period recorded minutes across at least two active local dates, at least 30 baseline minutes, at least 30 additional minutes, and at least 25 percent growth over baseline. Eligibility SHALL evaluate the unrounded comparison. A title with zero baseline minutes and the same current-period minimum SHALL instead qualify as newly recorded activity, without a percentage or first-ever-play claim. A nonzero baseline below 30 minutes SHALL not qualify for growth. Titles SHALL remain ineligible when the available recorded-history boundary is unknown or later than the baseline's start.

#### Scenario: Growth qualifies
- **WHEN** a game has 120 current minutes on two dates and 80 baseline minutes, and recorded history reaches the baseline start
- **THEN** it qualifies with 40 additional recorded minutes and 50 percent growth

#### Scenario: Relative growth insufficient
- **WHEN** a game rises from 200 to 230 recorded minutes with enough active dates
- **THEN** it is excluded because 15 percent growth is below the relative threshold

#### Scenario: Tiny denominator
- **WHEN** a game rises from 5 to 60 recorded minutes
- **THEN** no growth percentage or ranked growth claim is shown for it

#### Scenario: Newly recorded activity
- **WHEN** a game has zero baseline minutes and 90 current minutes across two dates within eligible recorded-history bounds
- **THEN** it can appear as newly recorded activity without an infinite growth score or first-ever-play claim

#### Scenario: Tracking begins inside baseline
- **WHEN** the available recorded-history boundary lies inside either comparison period
- **THEN** ranked comparisons are withheld and insufficient-history context is supplied

### Requirement: Personal momentum ordering and evidence are explainable
Eligible growth candidates SHALL be ordered by descending additional recorded minutes, then descending current minutes, then stable game identity. Newly recorded activity SHALL be a separately labelled group ordered by descending current minutes then stable identity, after growth candidates. Presentation SHALL take at most five titles from that ordered result. Each row SHALL expose both period amounts and its qualifying reason. Empty eligibility SHALL not fabricate a trend. All claims SHALL be about recorded personal activity and disclose that missing tracking can affect comparison; an old first record SHALL NOT be presented as proof of continuous coverage or no play in missing dates.

#### Scenario: Equal growth
- **WHEN** candidates have equal additional minutes
- **THEN** current minutes and stable identity determine a repeatable order

#### Scenario: No eligible game
- **WHEN** no title meets the documented criteria
- **THEN** the result explains that no qualifying recorded increase exists rather than choosing an arbitrary most-played game

#### Scenario: Missing observations
- **WHEN** history spans both periods but tracking may have gaps
- **THEN** wording describes recorded activity and retains the incomplete-tracking caveat instead of asserting complete real-world play coverage

### Requirement: Personal momentum is a coherent offline read
Momentum SHALL be derived from a coherent snapshot of account, comparison dates/zone, eligible visibility, and local recorded evidence. Queries SHALL be limited to the two comparison periods plus the minimal history-boundary metadata. Visibility, session corrections/finalization, midnight, zone, and account changes SHALL invalidate affected results. Same-account recomputation SHALL retain a dated prior snapshot with updating context; account replacement SHALL clear old results. Viewing momentum SHALL cause no activity writes, external request, periodic job, or cloud poller expansion.

#### Scenario: Hidden game or re-filed session
- **WHEN** a title is hidden or its recorded sessions are corrected across comparison dates
- **THEN** the result is recomputed without stale visibility or double-counted minutes

#### Scenario: Offline use
- **WHEN** the player inspects momentum without connectivity
- **THEN** available local results remain usable without a remote request

#### Scenario: Account replaced
- **WHEN** an account changes while calculation is in flight
- **THEN** its old results are cleared and late calculations cannot publish into the replacement account

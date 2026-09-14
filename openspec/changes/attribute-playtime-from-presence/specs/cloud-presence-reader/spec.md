## MODIFIED Requirements

### Requirement: A cloud read writes nothing derived

The system SHALL NOT write a session, daily progress record, derived gamification value, or
progress event as a result of a cloud read, except:

- through the presence session mechanism acting on games whose sessions that mechanism already
  owns; and
- by placing, in time, minutes that playtime diffing has already counted.

A read is an acquisition of recorded fact. Deriving from it is a separate decision with its own
provenance and its own author, and must be introduced deliberately rather than arriving as a side
effect of fetching. Neither permitted path makes the cloud an author: the first supplies
observations to a mechanism that already exists, and the second supplies only *when* to a total
that Steam alone determines.

#### Scenario: A read of shared-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from observed presence
- **THEN** sessions may be written for them through that mechanism and its writer

#### Scenario: A read of owned-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from playtime diffing
- **THEN** they may inform where already-counted minutes are placed
- **AND** they SHALL NOT cause any minute to be added, removed, or created

#### Scenario: A read with no diffed playtime to place

- **WHEN** a cloud read returns intervals for an owned game and no playtime increase has been
  observed for it
- **THEN** no session is written for that game

#### Scenario: The reconstruction remains inert

- **WHEN** intervals are reconstructed
- **THEN** the reconstruction itself writes nothing, and any write is performed by the path that
  consumes it

## ADDED Requirements

### Requirement: Observed intervals place diffed minutes without changing their total

Where a playtime increase is observed for a game and a record of observed presence covers the
period it was earned in, the system SHALL distribute those minutes across the observed intervals
rather than attributing them all to a single estimated start. The sum of the minutes placed SHALL
equal the observed increase exactly.

The system SHALL NOT place minutes into a period no interval covers, and SHALL NOT place more
minutes than were observed as played.

Steam's cumulative total is the only authority on how much was played; the presence record is the
only authority on when. Keeping those roles separate is what makes this safe — a wrong or
incomplete presence record can misplace minutes but can never invent them, because the total it is
distributing was not derived from it.

#### Scenario: One increase across several observed intervals

- **WHEN** a playtime increase is observed and the presence record shows several separate intervals
  of that game within the period
- **THEN** a session is written for each interval
- **AND** the minutes across them sum to the observed increase

#### Scenario: The total is never changed

- **WHEN** minutes are placed across intervals whose spans total more or less than the observed
  increase
- **THEN** the minutes written still sum to the observed increase

#### Scenario: Placement respects the attribution rule

- **WHEN** placed sessions begin on different calendar dates
- **THEN** each session's minutes are credited to the date that session began
- **AND** no session's minutes are divided across dates

#### Scenario: No interval covers the period

- **WHEN** a playtime increase is observed and the presence record shows no interval for that game
- **THEN** the minutes are attributed as they would be without any presence record

#### Scenario: A game still in progress

- **WHEN** the final interval is still ongoing at the time of the observation
- **THEN** the session placed for it remains open and is extended by later observations

#### Scenario: Unconfirmed time receives no minutes

- **WHEN** an interval was confirmed only until a time before it ended
- **THEN** minutes are placed only against the confirmed portion

#### Scenario: An interior gap receives no minutes

- **WHEN** an interval carries an interior-gap pair alongside a fresh tail (for example a v3
  transition carrying `prevLastObservedAt=t10` with `prevCoverageLapseFrom=t1` /
  `prevCoverageLapseRecoveredAt=t10`)
- **THEN** minutes are placed only against the confirmed portions excluding the `t1..t10` span
- **AND** the interval's fresh tail does not cause minutes to be placed into the interior span

### Requirement: Placement never degrades the estimate it replaces

The system SHALL fall back to attributing minutes as it would without any presence record whenever
the record cannot improve on it — including when no record covers the period, when the record
is unavailable, when coverage is unknown, and when the period is short enough that the existing
estimate is already within the record's own resolution.

No configuration of the presence record SHALL cause a game's minutes to go uncredited.

#### Scenario: Record unavailable

- **WHEN** the presence record cannot be read during a sync
- **THEN** the sync attributes minutes exactly as it does without the feature, and completes

#### Scenario: Feature not configured

- **WHEN** no cloud endpoint is configured
- **THEN** session synthesis behaves exactly as it did before this capability existed

#### Scenario: A short period needs no correction

- **WHEN** the period since the previous observation is short enough that the existing estimate is
  already as precise as the record
- **THEN** the record is not consulted for that period

#### Scenario: Minutes are never lost

- **WHEN** placement is attempted and cannot be completed for any reason
- **THEN** the observed increase is still credited in full by the unaided attribution

### Requirement: History can be re-filed once, and reversed

The system SHALL provide a user-initiated action that re-files already-recorded sessions against
the presence record, SHALL treat it as a one-time event that repeated invocation does not
compound, and SHALL let the user reverse it, returning attribution to what it was.

The action SHALL disclose that it changes which dates play is credited to — and therefore quests
and streaks — and that it does not change experience, levels, or any game's total playtime.

#### Scenario: Re-filing history

- **WHEN** the user invokes the action
- **THEN** already-recorded sessions covered by the presence record are re-filed onto the dates
  they were played
- **AND** each game's total recorded minutes are unchanged

#### Scenario: Repeated invocation does nothing

- **WHEN** the action is invoked again after completing
- **THEN** no further change results

#### Scenario: Reversing it

- **WHEN** the user reverses a completed re-filing
- **THEN** attribution returns to what it was beforehand, and the action is offered again

#### Scenario: Sessions the record cannot speak to

- **WHEN** recorded sessions fall outside what the presence record covers
- **THEN** they are left exactly as they are rather than rewritten on an assumption

#### Scenario: The disclosure is accurate

- **WHEN** the action is presented
- **THEN** it states that dates, quests and streaks may change and that experience, levels and
  totals will not

#### Scenario: Corrections are silent

- **WHEN** re-filing changes a past date's quest outcome or a streak
- **THEN** the change is reflected without a progress event, under the provenance for play observed
  retroactively

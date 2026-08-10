## MODIFIED Requirements

### Requirement: Analytics screen
The system SHALL provide an Analytics screen, reachable as a top-level destination, that
summarizes the player's tracked play over a user-selected window using a daily
playtime bar chart, a streak summary, a session-insights summary, a time-of-day pattern, an
achievement-rarity breakdown, and a most-played-games list.

The window SHALL be selected at the screen level and SHALL consist of a length and an anchor
period. The offered lengths SHALL include at least two weeks, 30 days, one month, 90 days, and one
year, and SHALL always include two weeks so that a figure comparable to Steam's own two-week
playtime is always available. Calendar lengths — one month and one year — SHALL denote calendar
periods, and rolling lengths — two weeks, 30 days, and 90 days — SHALL denote durations counted back
from the anchor's end. The length selection SHALL make clear which lengths are calendar periods and
which are rolling durations, since 30 days and one month otherwise appear interchangeable while
stepping differently.

The anchor SHALL be movable to earlier periods so previous months and years are reachable, and SHALL
default to the period ending today. Moving the anchor SHALL step by one calendar period for a
calendar length and by the selected length for a rolling length. The anchor SHALL NOT be movable
earlier than the period containing the earliest tracked session, so periods that no data could ever
populate are unreachable.

The window SHALL apply to every figure derived from tracked sessions: the daily playtime chart, the
most-played-games list, the session-insights summary, the count of quest-met days, and the
time-of-day pattern. The current streak, the longest streak, and the achievement-rarity breakdown
SHALL NOT follow the window — they are player-level and all-time figures respectively — and the
screen SHALL make that distinction evident rather than presenting them as describing the selected
period. Every windowed figure SHALL derive from the same resolved window bounds.

The chart SHALL offer omitting zero-minute dates as a display option, independent of the selected
window length. The screen SHALL render purely
from locally stored state so it is usable offline, and SHALL present an empty state when no tracked
sessions exist in the window. The daily playtime chart SHALL draw one bar per local day and SHALL
mark the configured daily-quest threshold as a reference line, so met and unmet days are legible at
a glance. The streak summary SHALL show the current streak, the longest streak, and the count of
quest-met days within the window. The session-insights summary SHALL show the session count, the
average session length, and the longest session within the window. The time-of-day pattern SHALL
bucket tracked minutes into morning, afternoon, evening, and night and SHALL highlight the peak
bucket. The achievement-rarity breakdown SHALL show the count of unlocked achievements per rarity
tier as a stacked bar with a per-tier legend. The most-played-games list SHALL rank games by
tracked minutes within the window, distinct from the Library's lifetime playtime ordering, and
SHALL show at most five entries.

#### Scenario: Viewing analytics with data
- **WHEN** the Analytics screen is shown and tracked sessions exist within the selected window
- **THEN** the screen presents a daily playtime bar chart, a streak summary, and a most-played-games
  list, each derived from locally stored state

#### Scenario: Daily playtime chart
- **WHEN** the Analytics screen is shown with tracked minutes on one or more days in the window
- **THEN** the chart draws one bar per local day in the window, with the bar height proportional to
  that day's tracked minutes, and a horizontal reference line at the configured daily-quest
  threshold. The chart includes readable max, midpoint, and baseline labels, sparse date labels for
  the window endpoints, and a legend identifying the quest threshold

#### Scenario: Quest threshold reference line
- **WHEN** the daily-quest threshold is greater than zero
- **THEN** the chart draws a reference line at that threshold value, so days whose bar reaches or
  exceeds the line are legible as quest-met days

#### Scenario: Readable chart scale
- **WHEN** the daily playtime window contains a high-minute outlier
- **THEN** the chart uses a rounded ceiling with visible max, midpoint, and baseline labels so the
  remaining bars can be compared without an arbitrary peak value, while preserving proportional bar
  heights

#### Scenario: Inspecting a chart day
- **WHEN** the user taps a day in the daily playtime chart
- **THEN** that bar is visually selected and the chart presents the day's date, tracked minutes, and
  whether the configured daily goal was met

#### Scenario: Inspected day broken down by game
- **WHEN** the user taps a day in the daily playtime chart that has tracked minutes
- **THEN** the screen additionally lists the games played on that day and each game's tracked
  minutes on that day, ordered by minutes descending

#### Scenario: Inspecting a day with no tracked minutes
- **WHEN** the user taps a day in the daily playtime chart that has no tracked minutes
- **THEN** the day's date and zero total are presented without a game breakdown, rather than an
  empty list

#### Scenario: Distinguishing the chart baseline
- **WHEN** the daily playtime chart is shown
- **THEN** the zero-minute baseline is rendered as a visible solid axis beneath the bars and remains
  visually distinct from the dashed daily-goal reference line

#### Scenario: Selecting a window length
- **WHEN** the user selects a window length
- **THEN** the daily chart, most-played games, session insights, quest-met day count, and
  time-of-day pattern all update to describe that length, without requiring a network call

#### Scenario: Two-week length always offered
- **WHEN** the window length options are presented
- **THEN** a two-week option is among them, whichever anchor period is selected

#### Scenario: Moving the anchor to an earlier period
- **WHEN** the user moves the window anchor to an earlier period
- **THEN** every windowed figure describes that earlier period, derived from locally stored
  sessions, without requiring a network call

#### Scenario: Calendar length steps by calendar period
- **WHEN** a calendar length is selected and the user moves the anchor one period earlier
- **THEN** the window describes the immediately preceding calendar period in full, whatever its day
  count, rather than a fixed number of days back

#### Scenario: Rolling length steps by its own duration
- **WHEN** a rolling length is selected and the user moves the anchor one period earlier
- **THEN** the window describes the duration of that length immediately preceding the previous
  window, with no gap and no overlap

#### Scenario: Similar lengths step differently
- **WHEN** the user steps a 30-day window and a one-month window back from the same anchor
- **THEN** the 30-day window moves back exactly 30 days and the one-month window moves to the
  previous calendar month, and the length selection distinguishes the two

#### Scenario: Calendar periods of differing lengths
- **WHEN** the user steps a one-month window across months with different day counts
- **THEN** each window covers its whole calendar month, and every windowed figure describes exactly
  that month

#### Scenario: Anchor bounded by available history
- **WHEN** the earliest tracked session is more recent than an earlier period the user attempts to
  reach
- **THEN** the anchor cannot be moved to that period, rather than presenting a period that no data
  could populate

#### Scenario: Anchor with no sessions inside available history
- **WHEN** the selected anchor period lies within available history but contains no tracked sessions
- **THEN** the screen presents its empty state for that period, and the anchor remains movable back
  to a period that has data

#### Scenario: Omitting zero-minute dates
- **WHEN** the user enables omitting zero-minute dates
- **THEN** the chart omits dates with no tracked minutes while the selected window length is
  unchanged

#### Scenario: Streak summary
- **WHEN** the Analytics screen is shown
- **THEN** the streak summary shows the current streak, the longest streak, and the number of
  quest-met days within the selected window

#### Scenario: Streaks do not follow the window
- **WHEN** the user moves the anchor to an earlier period
- **THEN** the current and longest streak continue to report the player's present counters, and are
  presented so they are not read as describing the selected period, while the quest-met day count
  describes that period

#### Scenario: Most-played games
- **WHEN** the Analytics screen is shown and one or more games have tracked minutes in the window
- **THEN** up to five games are listed, ordered by tracked minutes in the window descending, each
  with its name, icon, and tracked minutes in the window

#### Scenario: Most-played games distinct from lifetime playtime
- **WHEN** a game's lifetime Steam playtime greatly exceeds its tracked minutes in the window
- **THEN** it is ranked by its tracked minutes in the window, not by its lifetime playtime

#### Scenario: Session insights
- **WHEN** the Analytics screen is shown and one or more sessions exist in the window
- **THEN** the session-insights summary shows the number of sessions, the average session length,
  and the longest session within the window

#### Scenario: Time-of-day pattern
- **WHEN** the Analytics screen is shown and one or more sessions exist in the window
- **THEN** the time-of-day pattern buckets tracked minutes into morning, afternoon, evening, and
  night, and highlights the bucket with the most minutes as the peak time

#### Scenario: Achievement rarity breakdown
- **WHEN** the Analytics screen is shown and one or more achievements are unlocked
- **THEN** the rarity breakdown shows each rarity tier's unlocked count as a segment of a stacked
  bar with a per-tier legend, using the same tier colors as the game-detail screen

#### Scenario: Rarity breakdown does not follow the window
- **WHEN** the user selects any window length or anchor
- **THEN** the achievement-rarity breakdown continues to describe all unlocked achievements, and is
  presented so it is not read as describing the selected period

#### Scenario: No tracked sessions in the window
- **WHEN** the Analytics screen is shown and no tracked sessions exist within the selected window
- **THEN** the screen presents an empty state explaining that analytics appear after playing and
  syncing, rather than showing empty charts

#### Scenario: Offline rendering
- **WHEN** the Analytics screen is shown without network
- **THEN** it renders from the last stored state without blocking

#### Scenario: Not configured
- **WHEN** Steam credentials are not configured
- **THEN** the Analytics screen presents a not-configured state rather than empty charts

## ADDED Requirements

### Requirement: Achievement rarity drill-down
The achievement-rarity breakdown SHALL expand to list the twenty rarest unlocked achievements,
ordered from rarest to least rare, each identified by its game and achievement name with the
rarity figure that ordered it. The list SHALL order by the same percent that determined each
achievement's rarity tier, so an achievement's position and its displayed tier cannot disagree.
Fewer than twenty unlocked achievements SHALL list all of them rather than padding the list.

#### Scenario: Expanding the rarity breakdown
- **WHEN** the user selects the achievement-rarity breakdown
- **THEN** the twenty rarest unlocked achievements are listed, rarest first

#### Scenario: Rarity figure consistent with tier
- **WHEN** a listed achievement shows a rarity tier
- **THEN** the rarity figure shown beside it is the one that determined that tier, so the ordering
  and the tier agree

#### Scenario: Fewer than twenty unlocked achievements
- **WHEN** the player has fewer than twenty unlocked achievements
- **THEN** all of them are listed, rarest first, with no placeholder entries

#### Scenario: No unlocked achievements
- **WHEN** the player has no unlocked achievements
- **THEN** the breakdown does not offer an expansion, rather than expanding to an empty list

#### Scenario: Achievement identified by game
- **WHEN** the rarest achievements span more than one game
- **THEN** each listed achievement names the game it belongs to, so identically-named achievements
  from different games are distinguishable

#### Scenario: Collapsing the drill-down
- **WHEN** the user collapses the expanded list
- **THEN** the stacked bar and its per-tier legend are shown as before, unchanged

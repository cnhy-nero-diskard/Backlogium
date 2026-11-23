# App UI Delta

## ADDED Requirements

### Requirement: Analytics labels its denominators and date gaps
Analytics SHALL identify average playtime as an average per active day and label quest consistency with its evidenced denominator. Filtered chart days SHALL retain actual dates and disclose omitted dates.

#### Scenario: Current year or recent tracking start
- **WHEN** the selected calendar period contains future dates or dates before tracking began
- **THEN** windowed metrics use represented dates only and do not imply zero play or a failed quest on unsupported dates

#### Scenario: Active days only
- **WHEN** zero-minute dates are filtered between consecutive visible bars
- **THEN** the chart and accessible day inspection expose real dates and make the omitted interval apparent

### Requirement: Lifetime figures are visually separate in Analytics
The Analytics screen SHALL group current and longest streaks and the all-time rarity breakdown separately from selected-period figures.

#### Scenario: Period changes while data loads
- **WHEN** the selected range changes before its data arrives
- **THEN** retained content identifies the range it describes and lifetime figures do not acquire the selected date label

### Requirement: Session start buckets disclose their attribution
The time-of-day view SHALL label its buckets as session-start attribution. It SHALL disclose that recorded session minutes are grouped by the local start hour and that minutes with no session start are absent from the clock breakdown.

#### Scenario: Overnight or timing-free play
- **WHEN** a recorded session crosses a clock bucket boundary or another date carries only recovered day-level minutes
- **THEN** the session stays grouped by its start hour and recovered minutes without a session stay outside the clock breakdown

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

The anchor SHALL be movable to earlier periods so previous months and years are reachable, and
SHALL default to the period ending today. Moving the anchor SHALL step by one calendar period for a
calendar length and by the selected length for a rolling length. The anchor SHALL NOT be movable
earlier than the period containing the earliest eligible canonical dated activity, including
credited dated allocations that precede the first timestamped session, so periods that no data could
ever populate are unreachable.

The window SHALL apply to every selected-period figure. The daily playtime chart, most-played-games
list, and count of quest-met days SHALL derive from eligible canonical dated activity, including
credited dated allocations. The session-insights summary and time-of-day pattern SHALL derive only
from recorded sessions in the window; when eligible dated activity exists but no sessions do, those
session-only insights SHALL be marked unavailable rather than presented as zero-valued activity.
The current streak, the longest streak, and the achievement-rarity breakdown SHALL NOT follow the
window — they are player-level and all-time figures respectively — and the screen SHALL make that
distinction evident rather than presenting them as describing the selected period. Every
selected-period figure SHALL derive from the same represented activity bounds, clipped to today and
the earliest eligible canonical dated activity where applicable.

The chart SHALL offer omitting zero-minute dates as a display option, independent of the selected
window length. The screen SHALL render purely
from locally stored state so it is usable offline, and SHALL present the window's empty state only
when no eligible canonical dated activity exists in the window. Credited dated allocations count as
activity even when no timestamped session exists. The daily playtime chart SHALL draw one bar per
represented local day unless the active-days-only option is selected and SHALL mark the configured
daily-quest threshold as a reference line. Quest-met labels SHALL use stored
daily outcomes because the bar total and the eligible quest minutes can differ. The streak summary
SHALL show the current streak, the longest streak, and the count of
quest-met days within the window. The session-insights summary SHALL show the session count, the
average session length, and the longest session within the window. The time-of-day pattern SHALL
group recorded session minutes by local start hour into morning, afternoon, evening, and night and SHALL highlight the peak
bucket. The achievement-rarity breakdown SHALL show the count of unlocked achievements per rarity
tier as a stacked bar with a per-tier legend. The most-played-games list SHALL rank games by
tracked minutes within the window, distinct from the Library's lifetime playtime ordering, and
SHALL show at most five entries.

#### Scenario: Viewing analytics with data
- **WHEN** the Analytics screen is shown and eligible canonical dated activity exists within the
  selected window
- **THEN** the screen presents a daily playtime bar chart, a streak summary, and a most-played-games
  list, each derived from locally stored state

#### Scenario: Dated allocations without sessions
- **WHEN** the selected window contains credited dated allocations but no recorded sessions
- **THEN** the chart and most-played-games list include those dated minutes, play mix uses visible
  dated minutes where shown, and the session-only insights identify timing as unavailable instead of
  presenting the window as empty

#### Scenario: Daily playtime chart
- **WHEN** the Analytics screen is shown with tracked minutes on one or more days in the window
- **THEN** the chart draws one bar per represented local day in the window, subject to the active-days-only display option, with the bar height proportional to
  that day's tracked minutes, and a horizontal reference line at the configured daily-quest
  threshold. The chart includes readable max, midpoint, and baseline labels, sparse date labels for
  the window endpoints, and a legend identifying the quest threshold

#### Scenario: Quest threshold reference line
- **WHEN** the daily-quest threshold is greater than zero
- **THEN** the chart draws a reference line at that threshold value and uses stored outcomes to
  identify quest-met days, including when Focus-only credit or missing evidence differs from bar totals

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
- **WHEN** an earlier period precedes the earliest eligible canonical dated activity
- **THEN** the anchor cannot be moved to that period, rather than presenting a period that no data
  could populate

#### Scenario: Allocation precedes the first session
- **WHEN** an eligible credited dated allocation precedes the earliest timestamped session
- **THEN** the anchor can reach the period containing that allocation and cannot move earlier than it

#### Scenario: Anchor with no sessions inside available history
- **WHEN** the selected anchor period lies within available history but contains no sessions or
  other eligible canonical dated activity
- **THEN** the screen presents its empty state for that period, and the anchor remains movable back
  to a period that has eligible activity

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
- **THEN** the time-of-day pattern groups recorded minutes by local session start into morning, afternoon, evening, and
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
- **WHEN** the Analytics screen is shown and no eligible canonical dated activity exists within the
  selected window
- **THEN** the screen presents an empty state explaining that analytics appear after playing and
  syncing, rather than showing empty charts or session-only distributions

#### Scenario: Offline rendering
- **WHEN** the Analytics screen is shown without network
- **THEN** it renders from the last stored state without blocking

#### Scenario: Not configured
- **WHEN** Steam credentials are not configured
- **THEN** the Analytics screen presents a not-configured state rather than empty charts

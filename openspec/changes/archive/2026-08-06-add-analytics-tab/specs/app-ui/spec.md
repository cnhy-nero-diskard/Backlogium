## MODIFIED Requirements

### Requirement: App shell and navigation
The system SHALL present a Compose UI with navigation between Home, Library, History, Analytics,
and Settings screens, and all screens SHALL render from locally stored state so the app
is fully usable offline.

#### Scenario: Offline launch
- **WHEN** the app is opened without network
- **THEN** all screens display the last synced state and never block on a network call

#### Scenario: Navigating between screens
- **WHEN** the user selects a destination from the app's navigation
- **THEN** the corresponding screen (Home, Library, History, Analytics, or Settings) is shown

## ADDED Requirements

### Requirement: Analytics screen
The system SHALL provide an Analytics screen, reachable as a top-level destination, that
summarizes the player's tracked play over a recent fixed window (the last 30 days) using a daily
playtime bar chart, a streak summary, a session-insights summary, a time-of-day pattern, an
achievement-rarity breakdown, and a most-played-games list. The chart SHALL provide
selectable Active days, 7 days, and 30 days ranges, defaulting to Active days and omitting
zero-minute dates in that default view. The screen SHALL render purely
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
- **WHEN** the Analytics screen is shown and tracked sessions exist within the last 30 days
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

#### Scenario: Distinguishing the chart baseline
- **WHEN** the daily playtime chart is shown
- **THEN** the zero-minute baseline is rendered as a visible solid axis beneath the bars and remains
  visually distinct from the dashed daily-goal reference line

#### Scenario: Selecting a chart range
- **WHEN** the user selects Active days, 7 days, or 30 days
- **THEN** the chart updates to show the corresponding local-date range without requiring a network
  call, and Active days omits dates with zero tracked minutes

#### Scenario: Streak summary
- **WHEN** the Analytics screen is shown
- **THEN** the streak summary shows the current streak, the longest streak, and the number of
  quest-met days within the 30-day window

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

#### Scenario: No tracked sessions in the window
- **WHEN** the Analytics screen is shown and no tracked sessions exist within the last 30 days
- **THEN** the screen presents an empty state explaining that analytics appear after playing and
  syncing, rather than showing empty charts

#### Scenario: Offline rendering
- **WHEN** the Analytics screen is shown without network
- **THEN** it renders from the last stored state without blocking

#### Scenario: Not configured
- **WHEN** Steam credentials are not configured
- **THEN** the Analytics screen presents a not-configured state rather than empty charts

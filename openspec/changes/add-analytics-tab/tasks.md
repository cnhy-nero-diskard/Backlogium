# Tasks — Analytics tab

## 1. Data layer: per-day and per-game range projections

- [x] 1.1 Add `SessionDao.observeDailyMinutesSince(cutoff)` returning `List<DailyMinutes>` (a
      `date` ISO string + `minutes` int), grouping sessions by the local date of `startAt`
      — implemented as `SessionDao.observeSince(cutoff)` + in-memory grouping in the ViewModel
- [x] 1.2 Add `SessionDao.observeMinutesByGameSince(cutoff)` returning `List<GameTrackedMinutes>`
      (reuse the existing projection), scoped to `startAt >= :cutoff`
- [x] 1.3 Add `SessionRepository.dailyMinutesSince(cutoff)` and
      `SessionRepository.minutesByGameSince(cutoff)` flows wrapping the new DAO queries
      — `minutesByGameSince` exists; daily minutes are derived from `sessionsSince` in the ViewModel

## 2. Navigation: fifth top-level destination

- [x] 2.1 Add `ANALYTICS` to the `Destination` enum with a `ChartBar` Tabler icon and the label
      "Analytics"
- [x] 2.2 Register the `AnalyticsScreen` composable in `BacklogiumAppRoot`'s `NavHost`
- [x] 2.3 Confirm the bottom bar's `destinations.forEach` picks up the new entry with no extra
      wiring (it iterates `Destination.entries`)

## 3. Analytics ViewModel and UI state

- [x] 3.1 Create `AnalyticsUiState` carrying `loading`, `configured`, the 30-day
      `dailyMinutes` list, `questThreshold`, `currentStreak`, `longestStreak`,
      `questMetDaysCount`, and `topGames` (top 5 by tracked minutes)
- [x] 3.2 Create `AnalyticsViewModel` combining `SessionRepository.dailyMinutesSince`,
      `SessionRepository.minutesByGameSince`, `ProfileRepository.dailyProgress`,
      `ProfileRepository.profile`, `SettingsRepository.ruleConfig`, and
      `CredentialsRepository.credentialsStateFlow` over a fixed 30-day window
- [x] 3.3 Derive `questMetDaysCount` from `dailyProgress` filtered to the window; derive
      `topGames` by joining per-game minutes to `GameRepository.library` for names/icons

## 4. Analytics screen UI

- [x] 4.1 Create `AnalyticsScreen` with a `LazyColumn` (or scrollable `Column`) and the
      not-configured / no-data empty states, matching History's empty-state posture
- [x] 4.2 Add the daily playtime bar chart composable drawn on `Canvas`: one bar per day, a
      horizontal quest-threshold reference line, and a max-value axis label
- [x] 4.3 Add the streak summary card (current, longest, quest-met-days-in-window)
- [x] 4.4 Add the most-played games card (top 5 by tracked minutes, with icon + name + minutes)
- [x] 4.5 Refine the daily playtime chart with a readable rounded scale, visible axis labels,
      sparse date labels, rounded bars, and a labeled quest-threshold legend
- [x] 4.6 Make chart days tappable with selected-bar feedback and a selected-day detail row, and add
      an at-a-glance play snapshot card to improve the screen's visual hierarchy
- [x] 4.7 Clarify the chart's zero baseline with a visible foreground axis and reduce the goal-line
      dominance so the two references are not confused
- [x] 4.8 Add an Active days / 7 days / 30 days chart range selector, defaulting to Active days so
      zero-minute dates are omitted unless the user chooses a fixed window

## 5. Insights cards (session shape, time of day, rarity)

- [x] 5.1 Add a session insights card (session count, average session length, longest session)
      over the 30-day window, fed by the ViewModel's already-computed `sessionInsights`
- [x] 5.2 Add a time-of-day card bucketing tracked minutes into morning/afternoon/evening/night
      with the peak bucket highlighted, fed by the ViewModel's already-computed `timeOfDayPattern`
- [x] 5.3 Add an achievement-rarity card showing the all-time tier breakdown as a stacked bar
      with a per-tier legend, reusing the game-detail `rarityHalo` palette, fed by the ViewModel's
      already-computed `rarityBreakdown`

## 6. Spec

- [x] 6.1 Add the `app-ui` delta: a MODIFIED "App shell and navigation" requirement expanding the
      four-tab contract to five, and an ADDED "Analytics screen" requirement covering the chart,
      streak summary, most-played games, session insights, time-of-day pattern, and rarity breakdown

## 7. Build and verify

- [x] 7.1 `./gradlew :app:assembleDebug` builds clean
- [ ] 7.2 Confirm the Analytics tab appears in the bottom bar and the screen renders without
      crashing on an empty database (cold install) and on a populated database

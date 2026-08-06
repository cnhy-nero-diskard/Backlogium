# Tasks — Analytics tab

## 1. Data layer: per-day and per-game range projections

- [ ] 1.1 Add `SessionDao.observeDailyMinutesSince(cutoff)` returning `List<DailyMinutes>` (a
      `date` ISO string + `minutes` int), grouping sessions by the local date of `startAt`
- [ ] 1.2 Add `SessionDao.observeMinutesByGameSince(cutoff)` returning `List<GameTrackedMinutes>`
      (reuse the existing projection), scoped to `startAt >= :cutoff`
- [ ] 1.3 Add `SessionRepository.dailyMinutesSince(cutoff)` and
      `SessionRepository.minutesByGameSince(cutoff)` flows wrapping the new DAO queries

## 2. Navigation: fifth top-level destination

- [ ] 2.1 Add `ANALYTICS` to the `Destination` enum with a `ChartBar` Tabler icon and the label
      "Analytics"
- [ ] 2.2 Register the `AnalyticsScreen` composable in `BacklogiumAppRoot`'s `NavHost`
- [ ] 2.3 Confirm the bottom bar's `destinations.forEach` picks up the new entry with no extra
      wiring (it iterates `Destination.entries`)

## 3. Analytics ViewModel and UI state

- [ ] 3.1 Create `AnalyticsUiState` carrying `loading`, `configured`, the 30-day
      `dailyMinutes` list, `questThreshold`, `currentStreak`, `longestStreak`,
      `questMetDaysCount`, and `topGames` (top 5 by tracked minutes)
- [ ] 3.2 Create `AnalyticsViewModel` combining `SessionRepository.dailyMinutesSince`,
      `SessionRepository.minutesByGameSince`, `ProfileRepository.dailyProgress`,
      `ProfileRepository.profile`, `SettingsRepository.ruleConfig`, and
      `CredentialsRepository.credentialsStateFlow` over a fixed 30-day window
- [ ] 3.3 Derive `questMetDaysCount` from `dailyProgress` filtered to the window; derive
      `topGames` by joining per-game minutes to `GameRepository.library` for names/icons

## 4. Analytics screen UI

- [ ] 4.1 Create `AnalyticsScreen` with a `LazyColumn` (or scrollable `Column`) and the
      not-configured / no-data empty states, matching History's empty-state posture
- [ ] 4.2 Add the daily playtime bar chart composable drawn on `Canvas`: one bar per day, a
      horizontal quest-threshold reference line, and a max-value axis label
- [ ] 4.3 Add the streak summary card (current, longest, quest-met-days-in-window)
- [ ] 4.4 Add the most-played games card (top 5 by tracked minutes, with icon + name + minutes)
- [x] 4.5 Refine the daily playtime chart with a readable rounded scale, visible axis labels,
      sparse date labels, rounded bars, and a labeled quest-threshold legend
- [x] 4.6 Make chart days tappable with selected-bar feedback and a selected-day detail row, and add
      an at-a-glance play snapshot card to improve the screen's visual hierarchy
- [x] 4.7 Clarify the chart's zero baseline with a visible foreground axis and reduce the goal-line
      dominance so the two references are not confused
- [x] 4.8 Add an Active days / 7 days / 30 days chart range selector, defaulting to Active days so
      zero-minute dates are omitted unless the user chooses a fixed window

## 5. Spec

- [ ] 5.1 Add the `app-ui` delta: a MODIFIED "App shell and navigation" requirement expanding the
      four-tab contract to five, and an ADDED "Analytics screen" requirement covering the chart,
      streak summary, and most-played games

## 6. Build and verify

- [ ] 6.1 `./gradlew :app:assembleDebug` builds clean
- [ ] 6.2 Confirm the Analytics tab appears in the bottom bar and the screen renders without
      crashing on an empty database (cold install) and on a populated database

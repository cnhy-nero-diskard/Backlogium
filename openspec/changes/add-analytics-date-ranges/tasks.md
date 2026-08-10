# Tasks

## 1. Window model

- [x] 1.1 Define the window as an anchor period plus a length, with lengths covering at least
      two weeks, 30 days, one month, 90 days, and one year, each classified as calendar (month,
      year) or rolling (two weeks, 30 days, 90 days). Default to 30 days, preserving the screen's
      present data scope.
- [x] 1.2 Implement anchor stepping per classification: calendar lengths step one calendar period,
      rolling lengths step their own duration with no gap and no overlap.
- [x] 1.3 Add a pure resolver turning a window into explicit inclusive local-date bounds, and unit
      test it per length and across a stepped anchor. The resolver must not assume a fixed day count
      — calendar lengths vary (28–31 days, 365–366 days).
- [x] 1.4 Unit-test calendar stepping across a month-length boundary in both directions, including
      February and a leap year, and confirm each window covers its whole calendar month.
- [x] 1.5 Unit-test rolling stepping for contiguity: consecutive windows abut exactly, leaving no
      unreachable day and no day counted twice.
- [x] 1.6 Extend the day-boundary cutoff helper shared with History to produce both bounds, so the
      newest day in a window is whole in the same way the oldest already is. Do not write a second
      calculation.
- [x] 1.7 Unit-test boundary attribution in a fixed non-UTC zone: sessions starting just before and
      just after each bound land in exactly one window, and a midnight-crossing session stays whole
      on its start date.

## 2. Data layer

- [x] 2.1 Add upper-bounded session queries to `SessionDao` alongside the existing cutoff-only ones.
      Do not filter a wider fetch in memory — sessions are never pruned, so the bound belongs in SQL.
- [x] 2.2 Add a query for the earliest session's timestamp, for bounding the anchor. Do not derive it
      from `getAll()`.
- [x] 2.3 Add bounded forms to `SessionRepository` mirroring `sessionsSince` / `minutesByGameSince`.
- [x] 2.4 Expose the earliest tracked session through the repository as a flow, so the anchor bound
      updates if the first session changes (import, restore, or first ever session).
- [x] 2.5 Verify existing History behavior is unchanged — it keeps using the cutoff-only queries.

## 3. ViewModel

- [x] 3.1 Replace `WINDOW_DAYS` with window state on `AnalyticsViewModel`.
- [x] 3.2 Make the input flows re-subscribe when the window changes rather than binding once at
      construction.
- [x] 3.3 Resolve the window to bounds once per emission and derive every windowed figure from that
      single resolution: daily chart, most-played games, session insights, quest-met day count, and
      time-of-day pattern.
- [x] 3.4 Leave `currentStreak`, `longestStreak`, and `rarityBreakdown` outside the window.
- [x] 3.5 Expose the anchor's movability bounds so the control can disable stepping past available
      history.
- [x] 3.6 Expose the per-game breakdown for a selected day, reusing the existing library join that
      already produces the most-played list rather than adding a second join.
- [x] 3.7 Test that a fixed anchor with sessions on both boundaries yields consistent figures across
      all five windowed sections.

## 4. Screen — window control

- [x] 4.1 Remove `ChartRange` and its screen-local post-filtering.
- [x] 4.2 Add the screen-level window control: length selection plus anchor stepping. Distinguish
      calendar lengths from rolling ones in the control, so `30 days` and `1 month` are not read as
      the same option behaving inconsistently.
- [x] 4.3 Disable stepping earlier than the period containing the earliest tracked session.
- [x] 4.4 Keep omitting zero-minute dates as a chart display toggle, independent of length, defaulting
      so the chart's present appearance is unchanged on first open.
- [x] 4.5 Present the streak counters and rarity breakdown so they are not read as describing the
      selected period. Settle the quest-met-days placement question from design.md's open questions.
- [x] 4.6 Confirm the empty state appears for a period inside available history that has no sessions,
      and that the anchor remains movable back from there.

## 5. Day breakdown

- [x] 5.1 Extend chart-day inspection to list the games played on the selected day with each game's
      minutes, ordered by minutes descending.
- [x] 5.2 Show no breakdown for a day with no tracked minutes, rather than an empty list.
- [x] 5.3 Confirm the breakdown's per-day totals agree with the bar's height and with what History
      reports for the same date.

## 6. Rarity drill-down

- [x] 6.1 Widen `AchievementRepository.unlockedRarityByGame` (or add a projection beside it) to carry
      achievement identity and game, not percents alone.
- [x] 6.2 Order by the same percent that determined each achievement's tier, per the standing rarity
      drift policy, so position and displayed tier cannot disagree.
- [x] 6.3 Expand the breakdown to the twenty rarest unlocked achievements, rarest first, each naming
      its game.
- [x] 6.4 List all of them when fewer than twenty are unlocked; offer no expansion when none are.
- [x] 6.5 Collapse restores the stacked bar and legend unchanged.
- [x] 6.6 Confirm the drill-down does not follow the window.

## 7. Validation

- [x] 7.1 Walk every scenario in `specs/app-ui/spec.md` for both requirements.
- [x] 7.2 Verify the two-week length is offered at every anchor.
- [x] 7.3 Verify a moved anchor updates all five windowed sections together and none of the three
      non-windowed figures.
- [x] 7.4 Verify offline rendering and the not-configured state still hold with the window control
      present.
- [x] 7.5 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [x] 7.6 `openspec validate add-analytics-date-ranges --strict`.

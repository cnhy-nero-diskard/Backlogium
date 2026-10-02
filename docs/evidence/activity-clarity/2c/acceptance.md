# History clarity acceptance

Baseline: [baseline.md](baseline.md). Revised captures: [revised](revised/), same emulator/API 35, UTC fixtures, light 100% and dark 150%. The isolated debug test host renders production HistoryContent without credentials or external reads.

## Result

- Visible recorded amounts remain the sum of displayed sessions. Stored quest credit and outcome stay separate: 90 visible / 110 credited, 15 visible / 5 credited, and a progress-only 0 visible / 60 credited day retain their original met quests. Missing progress shows unavailable outcome.
- Explicit approximate-start, recorded-amount, and live/open labels avoid exact duration claims. The caption identifies amount-based game order, within-game start order, and non-proportional spacing. Overnight records remain once on their start date. Partial recovery/full timing cloud facts keep their explanation and exact reveal.
- One bounded Room transaction reads sessions, library visibility, progress, unlocks and the older-content floor. Invalidation is coalesced; projection runs off the main thread. Previous content remains marked updating with its original dates. Account replacement cancels old observation and clears content, expansion and reveal.
- Saved expansion/list state is keyed to the account. Named details actions call the shell route after current-account, local visibility and current-row checks. Inspection introduces no activity/quest/XP mutations, network clients, scheduler calls or cloud reads.

## Validation

Gradle: compileDebugKotlin, lintDebug, focused testDebugUnitTest filters for History and DailyActivity, assembleDebug and assembleDebugAndroidTest, using the isolated application-id init script.

37 focused JVM tests cover attribution, grouping/sort, nullable outcomes, signed differences, progress-only rows, wide totals, bounded/hidden-aware reads, coherent transaction refresh, cloud facts, midnight expansion and paging floor. Inspection leaves stored sessions/progress intact.

ADB instrumentation: HistoryClarityDeviceTest (4) and HistoryScreenTest (11), covering themes/enlarged text, named details/navigation return, refresh/older loading, midnight/account reset, loading/configuration, cloud status, exact distant-session reveal, disappearing-target fallback, contribution explanations and expansion semantics.

OpenSpec strict validation and git diff whitespace validation pass. Lint uses the existing repository baseline, with no changes or new suppressions.

## Coordination and limits

Draft [#176](https://github.com/cnhy-nero-diskard/Backlogium/pull/176) owns shared figure-specific provenance and Observed/Tracked/Inferred vocabulary. These conservative amount/start labels and existing contribution evidence can feed that disclosure without another source taxonomy.

Draft [#177](https://github.com/cnhy-nero-diskard/Backlogium/pull/177) owns unlock-only dates, the extended evidence floor and marginal daily XP. The new 2c requirement names are distinct. HistoryDayGroup.activity carries optional credit/outcome; later evidence-only models must preserve unknown amounts rather than reuse legacy Int/Boolean defaults. The current session/progress evidence floor is preserved; unlock-only dates and XP remain unapplied.

Controlled emulator evidence does not cover a physical device or the complete authenticated shell. Compose semantics test labels, action names, state descriptions and touch targets; manual TalkBack traversal and live-account mixed-source reconciliation were not performed. Explicit labels increase scrolling at 150% font; text remains readable and actions scroll into view.

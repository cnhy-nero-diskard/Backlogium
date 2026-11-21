# Analytics overview scope acceptance

Compare [baseline.md](baseline.md) and the labelled [revised captures](revised/). Same Android 15/API 35 emulator, controlled October 3, 2026 fixtures, light 100% and dark 150%, isolated production AnalyticsContent test host.

The featured panel visibly names its most-played role and this game's recorded amount. A divider separates all eligible visible games' total, active dates and active-day average. Exact represented dates appear on the overview, including elapsed-to-date context for current month/year. Long titles and scope text wrap in the enlarged dark fixture. Artwork failure retains the existing placeholder and all textual meaning.

LeadingGame now carries one AnalyticsGame object, so app ID, name, amount and artwork cannot be selected independently in the Composable. AnalyticsOverviewSnapshot derives aggregate amounts, represented dates and headline from one displayed UiState. Updating retains the old snapshot dates and identity. Comparison and empty headlines remove the featured panel. Existing name-based tie ranking, arithmetic, hidden filtering, attribution, comparable-window eligibility and external request paths are unchanged.

## Checks

- 32 JVM tests: headline priority, chart-day selection, rolling/calendar bounds and comparison eligibility, plus featured identity with duplicate names/reordered lists, unchanged tie ranking, aggregate average, headline transitions and updating dates.
- Eight new emulator checks: seven captured states plus rapid leading/comparison/empty changes and old dates during recomputation. Four existing AnalyticsScreenTest checks verify chart selection, headline/chart controls, initial sentinel loading and period navigation. A duplicate-title matcher now explicitly selects the inspected-day list before the period list.
- compileDebugKotlin, lintDebug, assembleDebug and assembleDebugAndroidTest pass. Lint retains the existing baseline (52 warnings; two existing errors filtered). OpenSpec strict validation and git diff whitespace validation pass.

The modified insight requirement retains both original activity/empty scenarios and adds scope/loading scenarios. Draft [#176](https://github.com/cnhy-nero-diskard/Backlogium/pull/176) owns figure-specific provenance markers: future markers belong beside the featured amount or aggregate figure without replacing inline role/date/scope. No classification system is introduced. 4c can reuse represented-date formatting, while explicitly identifying its independent completed-week comparison.

No live account, physical device or manual TalkBack traversal was exercised. Screen-reader facts are verified through Compose content descriptions and visible text; the enlarged fixture demonstrates wrapping, with additional scrolling. Tests use missing/local artwork rather than live CDN acceptance. Existing all-time streak/rarity notes remain unchanged and appear in the empty-window captures.

## Why

History has a strong day-to-game timeline, while Analytics makes users configure ranges before receiving an insight and relies on a touch-only Canvas for its primary interaction. Treating both screens as one “understand my play” journey will improve comprehension, locale correctness, and accessibility without changing the underlying activity data.

## What Changes

- Clarify History’s current-day and earlier-history hierarchy and give expandable day/game rows explicit accessible state and actions.
- Add concise explanation where presented session totals can legitimately differ from Steam’s lifetime counters.
- Lead Analytics with a plain-language insight derived from the selected window before exposing secondary configuration.
- Keep the existing active-days default and solid chart baseline, while making window and chart-display controls progressively disclosed and understandable.
- Make every chart day inspectable without precise touch through semantics and explicit previous/next day controls.
- Add earlier, later, and return-to-current window navigation with correct bounds.
- Use device locale for dates, casing, and user-visible formatting, and move History/Analytics copy into Android string resources.
- Render explicit loading, empty, and all-time-versus-windowed states without blank or misleading cards.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Refine History comprehension and Analytics hierarchy, accessible chart inspection, period navigation, locale behavior, and state presentation.

## Impact

- Affects `ui/history/HistoryScreen.kt`, `ui/analytics/AnalyticsScreen.kt`, their presentation/ViewModel models, string resources, and focused unit/instrumentation tests.
- Preserves Room/session attribution, window-bound calculations, active-day defaults, the solid zero baseline, and all existing analytics metrics.
- Does not change sync, session storage, XP, streak, or achievement calculations.

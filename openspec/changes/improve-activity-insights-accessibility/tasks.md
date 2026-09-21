## 1. History clarity and semantics

- [x] 1.1 Replace the generic History divider with explicit current/earlier grouping while preserving today auto-expansion and day ordering, and verify presentation tests cover today present, today absent, and progress-without-sessions states.
- [x] 1.2 Merge day and game row semantics with expanded state and named expand/collapse actions, and verify Compose tests announce date/name, tracked time, quest state where applicable, and action state.
- [x] 1.3 Add the session-measurement explanation surface using the existing approximate-start/tracked-minutes contract, and verify it is reachable without expanding a particular session.

## 2. Analytics insight hierarchy

- [x] 2.1 Add the deterministic Analytics headline model for no data, comparable-window change, leading game, and active-day fallback, and verify unit tests cover priority and omission when comparison inputs are unavailable.
- [x] 2.2 Query or derive the immediately preceding comparable window without changing current-window bounds, and verify repository/ViewModel tests cover rolling and calendar windows plus earliest-history limits.
- [x] 2.3 Place the insight and selected-period context before chart options, move secondary controls behind an accessible expandable section, and verify active-days-only remains the default.

## 3. Accessible chart and reversible periods

- [x] 3.1 Centralize selected-day state so Canvas taps and explicit previous/next controls share one update path, and verify unit tests cover zero-day, first-day, middle-day, and last-day selection.
- [x] 3.2 Add 48dp previous/next day controls, merged chart summary semantics, selected-day announcements, and text details, and verify TalkBack-facing Compose semantics require no precise chart tap.
- [x] 3.3 Add `canStepLater` and current-window state with earlier/later/current actions, and verify rolling and calendar navigation is reversible and bounded by today and earliest tracked history.
- [x] 3.4 Preserve the selected period and prior local figures while recomputing, and verify loading tests never substitute all-time figures for an empty or updating selected window.

## 4. Locale and copy

- [x] 4.1 Replace `Locale.US`, fixed English casing, and History/Analytics literal labels with default Android resources and locale-aware formatters (no locale-qualified resources or translations), and verify non-US date-order tests assert fallback default English copy with locale-aware formatting while preserving the same local-date attribution and window bounds.
- [x] 4.2 Verify windowed versus all-time labels remain explicit in populated, empty, and loading states under both default and non-default locales, with non-default-locale checks asserting fallback English text.

## 5. Verification

- [ ] 5.1 Run `./gradlew.bat :app:testDebugUnitTest --offline --no-daemon` and verify History, Analytics, DAO-fake, and window-bound regressions pass.
- [ ] 5.2 Run `./gradlew.bat :app:compileDebugKotlin --offline --no-daemon` and focused History/Analytics connected Compose tests when a device is available; do not claim unavailable checks.
- [ ] 5.3 Run `openspec validate improve-activity-insights-accessibility --strict` and `git diff --check`, and verify session attribution, solid chart baseline, and active-days default remain unchanged.

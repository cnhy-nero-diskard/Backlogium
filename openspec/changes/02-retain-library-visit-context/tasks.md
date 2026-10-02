## 1. Visit State

- [x] 1.1 Add a process-local, activity-retained Library visit state holder for filters, scroll anchor, absence start, and visit generation; verify its clock decisions at 299,999 ms and 300,000 ms with deterministic checks.
- [x] 1.2 Move `LibraryViewModel` discovery filters into the visit state holder and remove filter clearing from screen disposal; verify query, genre, coverage, and Family Shared filters survive a pushed detail/review return while explicit Clear controls still work.

## 2. Departure and Return Events

- [x] 2.1 Wire the app shell to distinguish Library and its pushed routes from another top-level tab, including detail opened from Home; verify each route transition starts or resumes only the intended Library visit.
- [x] 2.2 Observe process background/foreground transitions without replacing an earlier tab-departure timestamp; verify backgrounding from Library, from its detail/review child, and after a tab switch all use the correct start time.
- [x] 2.3 Evaluate expiry before Library context is shown, reset filters and visit generation at or after five minutes, and start a new interval after each timely return; verify repeated short departures, exact-threshold expiry, configuration recreation, and a new process.

## 3. Scroll Context

- [ ] 3.1 Capture and restore a useful visible-item anchor and offset for Library list and both grid densities; verify return from detail/review and a short tab/background departure keeps the player near the same game.
- [ ] 3.2 Restore against current filtered content with a nearest-valid fallback when games or grid grouping change, and reset to the top on an expired visit; verify changed-result and missing-anchor cases in all densities.

## 4. Integration Review

- [ ] 4.1 Replace the old disposal-reset contract checks with visit-lifetime checks and verify sort/density preferences, selection cleanup, explicit Clear behavior, and cold-start defaults remain correct.
- [ ] 4.2 Exercise Library → detail/review → Back, tab switch, and app background return on a phone or emulator at both sides of the five-minute boundary; verify the visible query, filters, and scroll position match the spec.

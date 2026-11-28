# Tasks

## 1. Keep price observations sortable

- [ ] 1.1 Expose the final minor units and currency in the wishlist domain model. Decode missing final amounts as missing and add provenance for explicitly reported zero. Cover the Room upgrade, positive legacy amounts, missing amounts with formatted prices, and explicit zero with focused tests.
- [ ] 1.2 Implement both price comparators over the dated cached observations. Test discounted and regular amounts, mixed currencies, current and retained groups, both missing-price states, stable ties in both directions, and the six-hour freshness transition.

## 2. Integrate the wishlist sort

- [ ] 2.1 Add the section-local sort control and group labels in list, grid, and compact grid. Verify the selected mode is visible and accessible, retained observations keep their dates, and switching order makes no extra request.
- [ ] 2.2 Verify integration with owned-game sorting, cached search relevance, and a partial refresh while a price sort is selected. Re-record affected Library goldens and run `:app:verifyRoborazziDebug`.

## 3. Evaluate the countdown source independently

- [ ] 3.1 Document a bounded source check with supported access evidence, representative same-offer matches in two storefronts, ambiguous bundle and missing-region cases, UTC expiry semantics, and request and freshness bounds. Record the outcome in the implementation PR for #171. Sorting is ready to ship even if a source fails this check.

## Workflow follow-up

- Open a separate countdown proposal if the source satisfies the design's acceptance criteria. Carry forward the evidence, expiry fallback, and accessibility rules.

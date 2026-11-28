# Tasks

## 1. Keep price observations sortable

- [x] 1.1 Separate Steam-formatted display data from optional sort evidence in the decoder and data source. Normalize and validate currency and record explicit-zero provenance. Run JSON fixtures through the production decoder and data source for absent, null, wrong-type, negative, fractional, and out-of-range finals, malformed and blank currencies, explicit zero with and without formatted evidence, and definitive `data: []`. Include a single response with a wrong-type final and usable formatted price alongside a valid priced app. Assert that both are returned and only the second has a rankable amount.
- [x] 1.2 Add the Room provenance migration and carry the latest observation through the domain model. Test that the migration preserves history, valid legacy positive prices remain rankable, and legacy zeros retain their display and date without becoming rankable. Use a JSON fixture through the production decoder, data source, and repository with in-memory Room to verify that a partial refresh appends both formatted-only and valid prices, leaves unresolved entries untouched, and records `data: []` as dated unavailable. Assert that the formatted-only and unavailable entries supersede older prices without reusing their amounts.
- [x] 1.3 Implement both price comparators over latest dated observations. Test discounted and regular prices, explicit versus legacy zero, invalid amounts and currencies, both missing-price states, current and retained groups, fixed currency order, stable ties in both directions, and the six-hour freshness transition.

## 2. Integrate the wishlist sort

- [ ] 2.1 Add the section-local sort control and defined group labels in list, grid, and compact grid, including a single-currency case. Verify that retained observations keep their dates, accessible selection survives collapse/reopen and density changes, the selected mode resumes after search, recreated presentation state starts at Deals first, and switching order makes no extra request.
- [ ] 2.2 Verify integration with owned-game sorting, cached search relevance, and a partial refresh while a price sort is selected. Re-record affected Library goldens and run `:app:verifyRoborazziDebug`.

## 3. Evaluate the countdown source independently

- [ ] 3.1 Maintain the [redacted, reproducible StoreBrowse source-check record](./design.md#storebrowse-source-check-record-2026-11-28) with exact inputs, response excerpts, two storefronts, bundle and omitted-country cases, timestamp interpretation, and support/freshness limits. Keep any countdown decision linked to [issue #171](https://github.com/cnhy-nero-diskard/Backlogium/issues/171); a price match alone SHALL NOT verify an offer or authorize countdown behavior. Sorting is ready independently of the source result.

## Workflow follow-up

- Open a separate countdown proposal only if the source satisfies the design's acceptance criteria, and link it to #171 and the source-check record. Carry forward the evidence, expiry fallback, and accessibility rules; otherwise leave the countdown gap documented in #171.

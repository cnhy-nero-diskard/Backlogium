## 1. Shared models and cell rendering

- [ ] 1.1 Define one cached/visible/hidden ordinary-library count summary behind the repository boundary; verify distinct IDs, shared→owned conversion, removed-shared exclusion, hidden intersection, and separate wishlist scope.
- [ ] 1.2 Render trophy bars through the existing achievement-count field; verify LIST/GRID/collection members keep their fields and COMPACT_GRID remains a strict subset.
- [ ] 1.3 Preserve missing/zero/no-achievements/completed distinctions; verify all four states without false 0/0 or redundant full bars.
- [ ] 1.4 Add semantic theme/shape/accessibility separation from HLTB progress; verify normal/overrun HLTB, recency, and artwork-fallback combinations.

## 2. Count presentation

- [ ] 2.1 Add actual displayed/baseline section counts without duplicating Library filters; verify independent sorts, active search/filter, omitted empty sections, and recovery controls.
- [ ] 2.2 Add scoped all-time summary in Analytics and Data & privacy; verify the same snapshot/count definition and clear distinction from selected-period activity.
- [ ] 2.3 Verify no Home count or new wishlist/search behavior is introduced and no cached total is derived solely from already-hidden/filtered lists.

## 3. Acceptance

- [ ] 3.1 Verify count/density invariants with focused domain/UI checks; run applicable tests and Android lint.
- [ ] 3.2 Inspect device states at narrow width and large font with TalkBack/color-vision/reduced-motion considerations; verify labels and bars remain distinguishable.
- [ ] 3.3 Record/verify affected visual baselines and run strict OpenSpec validation; document coordination with separate #167 search/recency work.

## 1. Selection and state

- [ ] 1.1 Implement distinct persistent eligibility and per-spin pools; verify hidden/unavailable/done exclusions and that achievement completion/recency/length do not weight eligibility.
- [ ] 1.2 Add an injected uniform-index selector; verify each candidate index, two-member alternation, three-member return, previous pick removal, and empty/one-member cases.
- [ ] 1.3 Keep current-visit state ephemeral and revision-aware; verify leaving/restarting forgets picks and membership changes invalidate stale reveal/action state.

## 2. Overview and explicit actions

- [ ] 2.1 Offer roulette only on operable custom overviews with at least two eligible members; verify no Home/form/derived/inoperable entry.
- [ ] 2.2 Show the actual sampled count and separate persistent/no-repeat exclusions without hidden titles; verify first spin and every re-spin disclosure matches the selector's P.
- [ ] 2.3 Add accessible detail/re-spin/dismiss actions with no writes; verify shrinking to one disables re-spin and invalid picks cannot navigate/commit stale data.
- [ ] 2.4 Implement explicit ordered-queue Move next through current mutation; verify done positions, other-member order, concurrent changes, and failure without false success feedback.

## 3. Acceptance

- [ ] 3.1 Verify direct reduced-motion reveal and silent spin/navigation; verify only a successful queue commit uses the existing success haptic.
- [ ] 3.2 Run applicable selector/collection/UI tests and lint; verify no pick persistence, network work, permission, goal mutation, or desktop-launch coupling.
- [ ] 3.3 Inspect device accessibility/large fonts, record/verify affected visual baselines, and run strict OpenSpec validation.

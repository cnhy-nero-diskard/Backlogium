## Why

The Analytics overview can visually associate whole-period statistics with its featured game's artwork and minutes. Its full leading-game explanation currently reaches assistive technology more clearly than sighted readers, so explicit visible scope is needed before adding personal momentum.

## What Changes

- Investigate the reported misleading banner on representative device states without assuming the selection algorithm is incorrect.
- Visibly label the featured game as the most-played visible game in the displayed period and separate its minutes from all-games period statistics.
- Keep precise displayed period context beside the overview, including elapsed-to-date framing when relevant.
- Preserve deterministic headline priority, active-day averages, comparable-window eligibility, explicit lifetime scope, and coherent period updates already present on master.
- Make empty, changed-window, missing-artwork, tied-game, and large-font states understandable with matching textual/accessibility semantics.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: visible Analytics headline, featured-game, period, and aggregate scope clarity.

## Impact

Planning order **3c** addresses [#169](https://github.com/cnhy-nero-diskard/Backlogium/issues/169), coordinated with [#147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147) through [#174](https://github.com/cnhy-nero-diskard/Backlogium/issues/174). It can be planned/applied independently of `1c`/`2c`; implement it before `4c-add-personal-play-momentum` so the additional card has a consistent visible scope convention.

Implementation affects Analytics overview/UI models, localized resources, and presentation checks. It changes no data arithmetic, session attribution, external requests, or ranking policy. Existing #147 foundations remain in place, and its broader heatmaps/genre/achievement/records roadmap remains separately scoped. Coordinate with draft PR #176's provenance disclosures without recreating that capability. Ordered change names retain the constant `c` suffix; standard artifact filenames stay unchanged.

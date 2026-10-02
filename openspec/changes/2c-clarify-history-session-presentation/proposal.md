## Why

History already groups days, games, and sessions, but its timeline-style gutter does not establish a chronological scale and the reported time breakdown remains confusing. Make the meaning of each amount, approximate start, live state, and missing record visible while retaining the existing bounded navigation.

## What Changes

- Evaluate representative multi-game, multi-session, overnight, sparse, and cloud-assisted days on a device/emulator before claiming the reported confusion is reproduced.
- Strengthen the existing day/game/session presentation with explicit recorded-minute, approximate-start, and live-state labels; keep the gutter non-proportional and clearly grouped by game.
- Reuse the daily-activity-breakdown contract to distinguish visible session totals from authoritative quest credit and unavailable allocation.
- Preserve bounded older loading, fully-loaded state, expansion, detail navigation, and cloud-session reveal behavior.
- Improve contextual measurement and missing-record wording using available source evidence.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: clearer History session facts, daily total/quest-credit reconciliation, and truthful temporal framing.

## Impact

Planning order **2c**, following `1c-add-daily-game-breakdown`, addresses [#169](https://github.com/cnhy-nero-diskard/Backlogium/issues/169) under [#174](https://github.com/cnhy-nero-diskard/Backlogium/issues/174), coordinated with [#147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147). Prefixes identify ordered change bundles; schema artifact filenames remain standard.

Implementation affects History grouping/UI models, read composition, localized copy, and accessibility. It adds no alternate-view switch, proportional time axis, session splitting, or new collection/query of Steam activity. Draft PR #176 remains the source for broad provenance vocabulary; draft PR #177 owns unlock-only History and daily XP. Coordinate their shared files and eventual evidence-only states without duplicating those features or requiring their merge first.

## Why

Today's quest shows a credited total without explaining which games contributed. A per-game breakdown must also explain why visible session totals can differ from stored quest credit after hiding games, incomplete records, or corrections.

## What Changes

- Add an expandable, initially collapsed game/minute breakdown to Today's quest with game-detail navigation.
- Project one local day's visible session evidence and authoritative stored quest credit together, retaining session-start attribution across midnight.
- Show an explicit reconciliation when recorded visible minutes and credited minutes differ; preserve unknown credit and unavailable identity instead of inventing game allocations.
- Keep historical imports and undated manual estimates out of dated game rows unless existing dated sessions support them.
- Preserve compact Home presentation, offline reads, hidden-game privacy, quest outcomes, and progress delivery.

## Capabilities

### New Capabilities

- `daily-activity-breakdown`: bounded, evidence-aware daily game totals and reconciliation with stored daily credit.

### Modified Capabilities

- `app-ui`: an accessible expandable Today's quest breakdown and navigation to its visible games.

## Impact

Planning order **1c** in the activity clarity/trends series, addressing [#169](https://github.com/cnhy-nero-diskard/Backlogium/issues/169) through [#174](https://github.com/cnhy-nero-diskard/Backlogium/issues/174), coordinated with Analytics umbrella [#147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147). Use the constant `c` suffix on the series' ordered change names; retain standard schema artifact filenames.

Implementation affects Home UI/state, a repository/domain read projection, session/date queries, and localized resources. No schema migration, new external request, or change to quest arithmetic is planned. `2c-clarify-history-session-presentation` can reuse the projection after this change. Reuse provenance vocabulary from draft PR #176 and coordinate Home placement with draft PR #177's XP work; neither draft is a prerequisite and their proposed features remain separate.

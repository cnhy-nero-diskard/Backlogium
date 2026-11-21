## Why

The player wants to find library games they are increasingly playing, but a most-played list alone cannot distinguish growth from volume. Existing local session history can support a small explainable personal momentum surface while community momentum still lacks comparable persisted samples and a verified cost model.

## What Changes

- Add a dated personal momentum card in Analytics comparing the last seven completed local days with the preceding seven.
- Use finalized visible library sessions, minimum activity/baseline thresholds, absolute and relative growth, and deterministic ordering; identify newly recorded activity separately from percentage growth.
- Preserve unknown/incomplete tracking caveats, hidden exclusions, start-date attribution, offline reads, and existing Analytics window behavior.
- Show at most five eligible titles with current/previous recorded minutes and game-detail navigation.
- Evaluate community sampling feasibility as a documented research task with request/storage/retention limits and a go/no-go outcome; actual sampling remains a separate future change.

## Capabilities

### New Capabilities

- `personal-play-momentum`: bounded personal activity comparisons, eligibility, ranking, evidence limits, and coherent offline derivation.

### Modified Capabilities

- `app-ui`: an accessible personal momentum card with explicit fixed-date scope and detail actions.

## Impact

Planning order **4c**, after `3c-clarify-analytics-featured-game-scope`, addresses the personal-first portion and community feasibility criteria of [#173](https://github.com/cnhy-nero-diskard/Backlogium/issues/173), coordinated with [#147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147) via [#174](https://github.com/cnhy-nero-diskard/Backlogium/issues/174). It does not depend on `1c`/`2c` or drafts #176/#177; reuse their explanations where available. The recent-play collection in #168 remains a membership view, separate from comparative ranking.

Implementation affects domain derivation, bounded session summaries, Analytics UI/state, and resources. No migration, new external request, worker, or cloud poller expansion is planned. The seven-day comparison and documented thresholds are deliberate defaults for this first version, not user-configurable settings. If community feasibility succeeds and that feature is subsequently requested, reserve `5c-add-community-play-momentum` as the next ordered change; no fifth proposal is created here.

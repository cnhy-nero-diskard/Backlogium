## Why

[Issue #181](https://github.com/cnhy-nero-diskard/Backlogium/issues/181) is a P0 reliability gap: when presence or sync misses gameplay, Steam can later report the correct cumulative increase while Backlogium invents a session boundary and credits an old date. Recover those authoritative minutes with honest day-level attribution, independently of cloud availability.

## What Changes

- Persist account/game-scoped last-good observations and daily cumulative snapshots through normal successful Steam reads, without another poller.
- Keep Steam diffing as the only quantity detector; allocate its minutes through one transaction-safe plan shared by periodic and targeted sync.
- Preserve usable timing evidence, then credit unmatched minutes to the recovery date for a one-day gap or evenly across missing dates for a multi-day gap, with deterministic exact rounding.
- Represent unknown-time play as dated allocations without fabricated start/end timestamps; expose persistent, accessible `!` explanations for multi-day estimates.
- Include these allocations in daily totals, game totals, quests, streaks, XP, History and Analytics, while excluding them from session-shape/time-of-day metrics. Exclude multi-day estimates from Personal Pace training and confidence, as selected by the user.
- Reconcile delayed evidence by replacing placement rather than adding minutes; preserve manual/imported state and existing progression-event delivery.
- Preserve observations, contribution identities and uncertainty in a new backup version, with compatible legacy imports and migration tests.

## Capabilities

### New Capabilities

- `playtime-recovery`: trustworthy durable observations, unknown-time allocations, exact residual distribution, evidence precedence and idempotent correction.

### Modified Capabilities

- `steam-sync`: replace the no-evidence synthetic-session fallback and generalize attribution and atomic persistence to day allocations.
- `cloud-presence-reader`: partial evidence places a bounded share; residual and unavailable evidence use local recovery instead of forcing the whole delta into sessions.
- `app-ui`: History/Analytics include unknown-time activity and accessible uncertainty without invented clock times.
- `backup-restore`: versioned recovery state round-trip, contribution-aware merge and account-safe observation handling.
- `personal-pace-forecasting`: train on eligible dated activity while excluding multi-day estimated windows from habits and confidence.
- `live-status`: distinguish transient timers from all durable credited activity, including unknown-time allocations.
- `progress-events`: distinguish fresh earned credit from recovered/reattributed play and preserve owed events during corrections.

## Impact

Planning targets the on-device committer/differ, Room schema and DAOs, session/activity repository, gamification/correction inputs, presence placement and re-filing integration, History/Analytics, backup codecs/merge and Personal Pace. No Steam API expansion, cloud deployment or always-on monitoring requirement. Implementation must ship updated screenshot baselines with affected UI.

This change supersedes the fallback contract in `attribute-playtime-from-presence`; its existing evidence validation and historical-import protections remain relevant. Coordinate read models with `disclose-data-provenance` and `extend-history-before-tracking`, without depending on their completion. Pre-baseline lifetime import and wholesale reconstruction of unprovable legacy history are outside scope.

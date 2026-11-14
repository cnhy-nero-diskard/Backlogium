## 1. Storage and repository boundary

- [x] 1.1 Add the account-scoped unlock outbox, uniqueness, recompute marker, and migration; verify upgrade creates no historic events and multiple groups persist across restart.
- [x] 1.2 Add prepared watch observations through existing keyed fetch/write coordination; verify missing globals defer without writes/baseline advancement and null snapshots can recover.
- [x] 1.3 Commit achievement merge and outbox group atomically with timestamp/account guards; verify rollback, concurrent normal refresh, retired rows, and stable snapshot behavior.
- [x] 1.4 Exclude pending/terminal alert records from backup export and clear them on account reset; verify old backups restore without replay.
- [x] 1.5 Add separate catalogue entries/cache metadata and domain progress-availability states; verify positive/empty catalogue persistence, upgrade preservation of player rows/snapshots, old backup restore, cache exclusion from export, and reset cleanup.
- [ ] 1.6 Extend repository hydration/coalescing for independent schema/player/global outcomes and the zero-play detail exception; verify same-game overlap across detail/watch/manual/sync/reconciliation/post-play, missing-catalogue completion, stale timestamp/account/hidden/removed guards, and cancellation ownership.
- [ ] 1.7 Keep catalogue-only entries out of XP, completion, rarity standing, smart collections, and watch baselines; verify private/no-stats and contradictory empty player responses preserve catalogues/last-good progress, while later authoritative player states merge and recompute recoverably without historical alerts.

## 2. Lifecycle and cadence

- [ ] 2.1 Connect one coordinator to the successful-presence seam while preserving session-end and shared-game hooks; verify the ordered sequence of state commit → old-watch cancellation → session-end publication → shared observation → current-watch activation for same-game, failed fetch, stop, A→B, hidden-game, and account-switch cases.
- [ ] 2.2 Implement 60/30-second scheduling, offline pause, cancellation, and failure backoff without overlaps; verify fake-clock cadence and stale in-flight result rejection.
- [ ] 2.3 Seed only successfully committed session baselines and recover recompute-needed work; verify first-read suppression, locked→unlocked/globals failure/retry, crash after baseline/repair/event Room commits, and no recompute for unchanged XP inputs.

## 3. Delivery and settings

- [ ] 3.1 Extend event vocabulary/priority without changing existing transition WAL recovery; verify all pending groups remain individually consumable.
- [ ] 3.2 Implement durable surface claims and idempotent notification IDs; verify crash windows, onlyAlertOnce replacement, denied permission, preference changes, and account/hidden suppression.
- [ ] 3.3 Present grouped accessible in-app unlock feedback with one central haptic and game-detail actions; verify single/group copy, TalkBack, reduced motion, and notification destination.
- [ ] 3.4 Add independent watch, interval, and alert controls with honest live-monitor prerequisite; verify opt-out stops traffic while alert opt-out still stores achievements.
- [ ] 3.5 Add bounded diagnostics with no credentials; verify success, unchanged, deferred rarity, offline, cancellation, and throttling records.

## 4. Integration acceptance

- [ ] 4.1 Run relevant repository/unit/migration tests and Android lint; verify existing playtime/session/cloud tests still pass and no new playtime polling path exists.
- [ ] 4.2 Verify on device: active game refresh at selected cadence, switch/exit cleanup, process restart, denied notification permission, and independent alert disabling.
- [ ] 4.3 Record/verify affected visual baselines and run strict OpenSpec validation; document best-effort cadence and the foreground claim/render crash trade-off.

## 5. Achievement browsing before play

- [ ] 5.1 Trigger one on-demand hydration from either game-detail entry point; render cache immediately, reuse the 30-day schema window including confirmed empty catalogues, and verify recomposition/reopening do not repeatedly request unknown player progress or start recurring/background library work.
- [ ] 5.2 Add accessible loading, cached/stale, unavailable-with-retry, confirmed-empty, and personal-progress-unavailable states; verify offline first opening, offline cached browsing, schema success/player failure, hidden objectives, and no fabricated locked progress/completion/XP.
- [ ] 5.3 Implement connectivity-aware bounded retry and shared cancellation for detail hydration; verify 60-second initial/15-minute capped automatic backoff, longer Retry-After, explicit retry, close/reopen, and continued work for another caller sharing a fetch.
- [ ] 5.4 Explain full achievement refresh scope in Settings and add bounded detail-hydration diagnostics; verify catalogue/player outcomes remain distinct and browsing works with monitor/watch/alerts disabled.
- [ ] 5.5 Verify the reported zero-play owned games (1462040, 2909400, 552520) load their catalogue on opening, without hardcoded achievement counts or requests for the rest of a hundreds-game unplayed backlog; cover both game-detail presentations, negative cache, process restart, and later confirmed player progress. Keep captured account data and credentials out of fixtures.
- [ ] 5.6 Run relevant repository, migration, domain/projection, ViewModel/UI checks and affected visual baselines; run strict OpenSpec validation. Record that the unrelated app 553850 playtime-recovery conservation failures observed during investigation are outside this change rather than disabling that guard to test browsing.

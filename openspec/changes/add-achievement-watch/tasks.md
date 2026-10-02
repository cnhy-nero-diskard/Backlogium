## 1. Storage and repository boundary

- [ ] 1.1 Add the account-scoped unlock outbox, uniqueness, recompute marker, and migration; verify upgrade creates no historic events and multiple groups persist across restart.
- [ ] 1.2 Add prepared watch observations through existing keyed fetch/write coordination; verify missing globals defer without writes/baseline advancement and null snapshots can recover.
- [ ] 1.3 Commit achievement merge and outbox group atomically with timestamp/account guards; verify rollback, concurrent normal refresh, retired rows, and stable snapshot behavior.
- [ ] 1.4 Exclude pending/terminal alert records from backup export and clear them on account reset; verify old backups restore without replay.

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

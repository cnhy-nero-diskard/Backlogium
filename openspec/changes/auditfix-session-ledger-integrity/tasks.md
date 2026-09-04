## 1. Prerequisites

- [x] 1.1 Confirm `auditfix-spec-truth` has landed and its v13-to-current chain test is green. **This change adds a schema migration and must not go in ahead of it** — the same gate the archived `auditfix-sync-write-integrity` set, for the same reason: this app's data cannot be re-derived. Verified by the chain test existing and passing on master
- [x] 1.2 Confirm `auditfix-background-work-contracts` has **not** landed yet. That change narrows `SteamSyncCoordinator`; this one must establish session-write safety first (design.md Decision 1, reason 2). Verified by `SteamSyncWorker.kt:207` and `ReconciliationWorker.kt:44` still holding the lock for their whole run
- [x] 1.3 Read `Session.kt`'s KDoc on the deliberately non-unique `(appId, startAt, endAt)` index and `WriteIntegrityDaoTest.kt:570`'s comment before touching the schema. Both encode constraints this change must not break. Verified by the plan in task 2.1 leaving the natural key non-unique

## 2. One open session per game (#116)

- [x] 2.1 Decide between a partial unique index on `appId WHERE open = 1` and a guarded `INSERT … WHERE NOT EXISTS` in `SessionDao`, based on the pinned Room version's partial-index support (design.md Decision 1). **The `(appId, startAt, endAt)` natural key stays non-unique either way.** Verified by the decision recorded and the natural key unchanged
- [x] 2.2 Implement the chosen guarantee in `SessionActionWriter` (`:37`, `:74`), whose KDoc already claims to be the one path session actions take into storage. Verified by the guarantee holding regardless of which caller reaches it
- [x] 2.3 Make an `Open` action for a game that already has an open session extend that session instead of inserting, since that is what the second observation meant. Verified by a test asserting one session with the combined minutes rather than a rejection that loses them
- [x] 2.4 Test: two overlapping `checkNow()` calls through the full read-derive-write boundary of `PresenceSessionRecorder` (`:69`, `:87`) produce exactly one open session. Extend `WriteIntegrityDaoTest` following the shape at `:570`. This is the regression test for #116 and the most important test in this change
- [x] 2.5 Test: the same property holds **with any process-scoped sync coordination disabled**, proving correctness does not rest on the lock — the spec scenario "Correctness does not rest on a process lock", and the same proof style the coordinator's KDoc asks for
- [x] 2.6 Test: two concurrent observations committing in either order leave identical stored state
- [x] 2.7 Test: two different games may each hold an open session simultaneously — the constraint is per game, not global
- [x] 2.8 Test: the backup/restore merge engine's natural-key lookup still tolerates a duplicate among closed sessions without failing an import. Verified by an import fixture containing such a collision succeeding

## 3. Non-inverted session intervals (#115)

- [x] 3.1 Add the out-of-order guard to `SessionDiffer`'s `Extend` emission (`:115-119`), clamping the boundary so the interval cannot invert (design.md Decision 2). Verified by the rollback test in 3.4
- [x] 3.2 Add the same guard to the `Open` emission in the same loop — `startAt = previousPollAt, endAt = now` inverts under the same rollback, which the audit did not name. Verified by a test opening a session across a backwards clock movement
- [x] 3.3 Record the clamp through `app-diagnostics` rather than swallowing it, so a real clock event is diagnosable afterwards (design.md Decision 2). Verified by a clamped action appearing in the diagnostics surface
- [x] 3.4 Test: an open session at `startAt = 1000, endAt = 2000` receiving a playtime increase at `now = 500` stores no interval with `endAt < startAt`. `SessionDifferTest` currently has only increasing timestamps
- [x] 3.5 Test: a later no-delta poll closing a session whose boundary was clamped still yields a non-inverted interval — the audit's specific observation that the bad boundary survives the close
- [x] 3.6 Test: the Steam-reported playtime delta is still credited when an action is clamped, since minutes do not depend on the device clock
- [x] 3.7 Test: a forward clock jump still extends sessions normally, so the guard has not made ordinary operation conservative
- [x] 3.8 Cross-check the guard against `PresenceSessionDeriver`'s existing handling (`:117`) and keep the two paths' behaviour consistent rather than merely both non-crashing

## 4. Removal provenance (#104)

- [ ] 4.1 Add a `RecomputeSource` for administrative removal, named for the event rather than as a generic non-earned catch-all (design.md Decision 4). Verified by the enum naming what happened, consistent with `SYNC`/`RULE_CHANGE`/`BACKFILL`/`RESTORE`
- [ ] 4.2 Switch `FamilySharedGameRepository.kt:258-267` from `RecomputeSource.SYNC` to it. Verified by a test asserting a removal that changes level or streak produces no progress event
- [ ] 4.3 Verify `ProgressEventDetector.kt:40+` keys on earned provenance rather than enumerating non-earned sources, so the new source is non-event-producing by construction. **Check this rather than assuming it** — if it enumerates, it needs updating too
- [ ] 4.4 Test: a removal recompute reseeds the delivery baseline to the values it wrote, including when they are lower than the baseline replaced
- [ ] 4.5 Test: a removal that lowers derived values below a previously acknowledged threshold moves no acknowledgement baseline backwards
- [ ] 4.6 Test: reversing a removal is equally non-earned and produces no events, per the spec scenario
- [ ] 4.7 Test: an ordinary sync after a removal still declares earned provenance and produces events against the reseeded baseline
- [ ] 4.8 Leave a note for `add-hidden-games` (0/55 tasks) that hide/unhide should add its own source alongside this one rather than widening it. Verified by the note being reachable from that change's proposal

## 5. XP overflow (#114)

- [ ] 5.1 Add a `maximum` to `RuleField` with an inline rejection matching the existing below-minimum treatment, and extend `RuleDraft.errorFor` (`:80-81`) which currently checks only the floor. Verified by a test that `xpPerMinute = 2147483647` is refused and `toConfig()` returns null
- [ ] 5.2 Choose the ceiling with headroom so no accepted configuration can approach the widened bound across a maximal library — not at the arithmetic limit (design.md Decision 3). Verified by the chosen value documented with the library size it assumes
- [ ] 5.3 Widen XP accumulation in `Gamification` (`:105`, `:160`, `:173`) so `gameXp`'s product, `games.sumOf`, and `achievementXp` cannot wrap, and derive levels from the widened total. Verified by a test at the accepted ceiling producing the mathematically correct value
- [ ] 5.4 Migrate `PlayerProfile.totalXp` (and `XpState.totalXp`) to the wider type. **A pure widening — do not attempt to reconstruct a real value for a device storing `0` because of this bug**; the next recompute produces it (design.md Decision 3). Verified by a populated-profile migration test asserting the stored total survives
- [ ] 5.5 Make the corrective recompute that replaces a wrapped `totalXp = 0` with the real total **reseed the delivery baseline rather than emit events**. A large upward correction must not fire a cascade of level-up celebrations for progress earned long ago — committing that while fixing #104 would be the same defect. Verified by a test that a device with a wrapped stored total recomputes to the correct value and produces no progress events
- [ ] 5.6 Test: the level derived from a large valid total is not 1 — the specific wrong result the audit reported
- [ ] 5.7 Test: `levelState`'s clamp no longer masks a wrapped value, because no wrapped value reaches it. Verified by asserting on the pre-clamp total, not just the clamped output
- [ ] 5.8 Test: a large library sums past the range of a single game's XP without overflow, and many unlocked achievements at the maximum per-tier award do the same — the two cases needing no absurd setting at all

## 6. Close out

- [ ] 6.1 `openspec validate --strict auditfix-session-ledger-integrity` passes
- [ ] 6.2 `./gradlew :gamification:test :app:testDebugUnitTest` passes
- [ ] 6.3 `./gradlew :app:connectedDebugAndroidTest --tests '*MigrationTest*'` passes, including the new `totalXp` case and the v13-to-current chain
- [ ] 6.4 Confirm on a device that a Family Shared game observed by two overlapping presence checks yields one session in History, not two
- [ ] 6.5 Sync the deltas into `openspec/specs/` via the archive workflow, not by hand
- [ ] 6.6 Close #104, #114, #115, #116

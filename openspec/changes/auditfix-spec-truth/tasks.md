## 1. Prerequisites

- [x] 1.1 Re-verify each of the six "code is correct" judgements against the cited lines before reviewing the deltas — `PresenceService.kt:143-155` (#105), `PresenceSessionRecorder.kt:150` and `LiveStatusRepository.kt:217-220` (#106), `RecentPlaytimeRepository` count=20 and `PostPlaySyncWorker` selection (#113), `FamilySharedGameRepository.kt:278-289` (#103), the Settings account section (#102), `BackupFile.kt:13-15` and `BackupRepository.kt:118-119` (#100). Verified when each citation is confirmed present and behaving as the delta now asserts
- [x] 1.2 Confirm no in-flight change under `openspec/changes/` already edits these six capabilities — `add-hidden-games`, `add-remote-launch`, and `add-desktop-agent` are all unstarted; verified by `grep -rl` for each capability name across their `specs/` directories returning nothing

## 2. live-status

- [x] 2.1 Apply the "Background presence tracking" delta so "Not observing while idle" is conditioned on Live monitor being disabled, and the enabled case is stated as its own scenario (#105). Verified by reading the two requirements together and finding no pair of scenarios that cannot both hold
- [x] 2.2 Apply the "Live session start time" delta removing "solely from playtime-delta-synthesized sessions" and adding the presence-derived scenario (#106). Verified by checking the new text against `game-sources/spec.md:89-104` and finding them consistent
- [x] 2.3 Confirm the narrowing did not weaken the user-visible-start constraint — the service must still begin only from a user-visible app interaction or a subsequent foreground. Verified by that clause still being present and unqualified in "Opt-in idle presence monitoring"

## 3. steam-sync, game-sources, onboarding-credentials

- [x] 3.1 Apply the "Play-triggered targeted playtime fetch" delta restoring the bounded-window wording (#113). Verified against `2026-08-27-add-post-play-sync/design.md` Decision 1, which the restored clause must match
- [x] 3.1a Reword the existing "Response for an unexpected game" scenario so extra games in the response read as the expected consequence of a bounded window rather than as an anomaly — the discard-and-do-not-attribute obligation is unchanged. Verified by the scenario describing normal operation while still forbidding attribution of anything but the stopped game
- [x] 3.2 Apply the "A shared game can be removed and stays removed" delta making reversal immediate (#103). Verified by checking it against `app-settings/spec.md:449-452`, which it must now agree with rather than contradict
- [ ] 3.3 Apply the `onboarding-credentials` REMOVED + ADDED pair moving repeatable credential editing to Settings (#102). Verified by `grep -n "Home"` over the resulting capability spec returning only the new scenario that requires Home to carry *no* account administration
- [x] 3.4 Confirm the REMOVED block carries both a Reason and a Migration line — `openspec validate` rejects a REMOVED requirement without them

## 4. schema-migration text

- [x] 4.1 Apply the "Upgrading an existing installation preserves its data" delta introducing the declared-purpose-plus-test rule and its two worked scenarios (#101). Verified by confirming both `MIGRATION_13_14` (`BacklogiumDatabase.kt:343-374`) and `MIGRATION_17_18` (`:432-458`) now satisfy the requirement as written
- [x] 4.2 Apply the "Migration correctness is mechanically verified" delta requiring the composed chain to the current version and a version-following target (#121). Verified by the requirement stating both the chain obligation and the no-hard-coded-target obligation

## 5. Migration chain fixture (the only code in this change)

- [x] 5.1 Extend the populated v13 fixture in `MigrationTest.kt:37-54` to run all registered migrations through to the current database version instead of stopping after `MIGRATION_13_14`. Verified by the test opening the database with the real current version rather than a literal
- [x] 5.2 Assert the representative seeded rows and values survive at the current version — cover at minimum the app-owned columns the spec names (backfilled minutes, focus flags, accumulated XP, longest-streak high-water mark) and an achievement rarity snapshot. Verified by each assertion failing if the corresponding value is dropped
- [x] 5.3 Keep every existing per-hop test unchanged — they are the precise regression checks and the chain test does not replace them. Verified by `:app:connectedDebugAndroidTest` still running the same per-hop cases
- [x] 5.4 Run the extended fixture. **If the composed v13-to-current chain fails, do not lower the target version to make it pass** — file the failing hop as its own issue, land the fixture with the failure documented, and treat it as a migration defect for a separate change. Verified either by a green run or by a filed issue naming the failing hop
- [x] 5.5 Confirm the chain test is reached by the automated suite that already runs migration tests on every change, so it cannot rot the way the v14 target did. Verified by the test executing in the same task as the existing `MigrationTest` cases

## 6. Close out

- [x] 6.1 `openspec validate --strict auditfix-spec-truth` passes
- [x] 6.2 `./gradlew :app:connectedDebugAndroidTest --tests '*MigrationTest*'` passes, or task 5.4's issue is filed
- [ ] 6.3 Sync the deltas into `openspec/specs/` via the archive workflow, not by hand
- [ ] 6.4 **After the sync**, edit two Purposes by hand, since a delta spec cannot change a Purpose (design.md Decision 4): `openspec/specs/backup-restore/spec.md`'s `## Purpose`, to describe rule configuration as export-only metadata rather than restored data; and `openspec/specs/onboarding-credentials/spec.md`'s `## Purpose`, to say "repeatable editing from Settings" rather than "repeatable editing from Home". Verified by the backup Purpose no longer implying rules are restored, and by `grep -n "Home"` over the synced onboarding-credentials spec returning only the scenario that requires Home to carry no account administration (which also discharges task 3.3's verification)
- [ ] 6.5 Close #100, #101, #102, #103, #105, #106, #113, #121 with a reference to the synced spec text. Leave #98 (the audit umbrella) open until the remaining six audit-fix changes land

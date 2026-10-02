# Implementation overlap map (task 1.3)

Comparison of the still-active `attribute-playtime-from-presence` change (created 2026-09-13) and
the current landed cloud imported-play transfer/reset/presence/session protocols against this
change (`stabilize-first-run-setup`, created 2026-10-02). Main specs and current source are
authoritative; the presence deltas' placement, imported-play transfer, reversal, and post-play
protocols have already landed in main specs and current code and must be preserved, not re-planned.

## 1. Shared spec files (delta specs)

Only two capability files carry delta requirements in **both** changes:

| Shared delta spec | `attribute-playtime-from-presence` | `stabilize-first-run-setup` |
|---|---|---|
| `specs/steam-sync/spec.md` | MODIFIED: `Session synthesis by playtime diffing`, `Playtime is attributed to a session's start date` | MODIFIED: `Sync failure surfacing`; ADDED: `Library polls expose attributable operation results`, `Confirmed library baseline readiness is durable and account-scoped` |
| `specs/app-settings/spec.md` | ADDED: `Re-file history action` | MODIFIED: `Run setup section`, `Data section` |

`first-run-setup`, `onboarding-credentials`, and `playtime-backfill` deltas in this change have no
presence counterpart; `cloud-presence-reader` deltas in the presence change have no counterpart
here.

## 2. Requirement names: no conflicts

Within each shared file the requirement names are disjoint — no delta requirement in this change
names or restates any delta requirement of the presence change:

- `steam-sync`: presence touches diffing/attribution only; this change touches failure surfacing,
  attributable results, and baseline readiness. Neither modifies the other's requirements.
- `app-settings`: presence adds the cloud `Re-file history action`; this change modifies the
  `Run setup section` and `Data section` requirements. Disjoint.

No change to `Sync failure surfacing` in the presence change, and no placement/attribution name in
this change. At main-spec level this change's `steam-sync` and `app-settings` deltas restate only
their own named main requirements. **No conflicting requirement names.**

## 3. Current landed protocols to preserve (cloud imported-play / presence / session)

From main specs and current source (branch `fix/stabilize-first-run-setup`):

- **Import** (`domain/PlaytimeBackfillUseCase.kt`, `data/repo/ProfileRepository.kt#importSteamHistory()`):
  freezes `backfillMinutes = max(0, playtimeForever − trackedMinutes)` per game, sets the one-time
  `playtimeBackfilled` flag, and recomputes under `RecomputeSource.BACKFILL`. Idempotent — repeat
  invocations are no-ops; new tracked play accrues on top; growing Steam totals are not re-imported.
- **Reset** (`PlaytimeBackfillUseCase.reset()`): clears offsets and flag, recomputes; returns
  `BLOCKED_BY_CLOUD_TRANSFER` when a cloud pre-data transfer is applied, preserving the
  reversal-before-reset guard from the archived `choose-cloud-presence-refile-range`.
- **Imported-play transfer / re-file** (`domain/CloudPresencePlaytimeRefilingUseCase.kt`,
  `CloudPresencePreDataAllocationRule.kt`, main `cloud-presence-reader` "History can be re-filed once,
  and reversed"): one-time, reversible; moves already-imported minutes into dated sessions in the
  same Room transaction with an equal `backfillMinutes` reduction, journaled for exact reversal,
  applied-range/cutoff receipt; blocked while applied.
- **Presence/session ownership** (`SessionActionWriter` = the one path session actions take into
  storage; `PlaytimeObservationCommitter` = the one path from an observed playtime reading to stored
  sessions): the cloud read writes nothing derived; a diffed increase is the sole quantity authority
  and observed presence only informs *when*; presence-derived shared-game sessions use the same
  writer; one game at most one open session; sessions non-inverted; start-date attribution; exactly
  once across concurrent polls and targeted fetches.
- **Post-play protocol** (`work/PostPlaySyncWorker.kt`, `PostPlayWorkEnqueuer.kt`,
  `PostPlayGenerationCoordinator.kt`): targeted fetch after a session ends, bounded retries,
   generation-owned schedules, commits through the ordinary poll path — setup must leave these
   unrelated schedules intact, without introducing a second playtime writer.
- **Derived recompute** (`RecomputeSource`, `GamificationUpdater`, `DerivedStateWriteCoordinator`):
  only `SYNC` announces earned progress; `BACKFILL`, `RESTORE`, `RETROACTIVE_PLAY`, etc. are
  administrative/non-earned. History import recovery must use `BACKFILL` provenance — never
  `RESTORE` (backup) or `SYNC`.
- **Pending recompute recovery** (`domain/PendingImportRecomputeUseCase.kt`): backup's
  `pendingImportRecompute` marker with `RecomputeSource.RESTORE`; the tightened import must extend
  this pattern with source/request provenance rather than consume the backup marker.

## 4. Sole session author to preserve

No duplicate session author is introduced.

- `PlaytimeObservationCommitter` is documented as "the one path from an observed playtime reading to
  stored sessions"; `SessionActionWriter` as "the one path session actions take into storage", and
  both the diffed and presence mechanisms route through it (`CLAUDE.md`: the on-device engine is the
  sole author of derived values).
- This change's import keeps `ProfileRepository.importSteamHistory()` as the *shared domain entry*
   and writes only offsets + the flag + recompute (it never writes sessions). Presence placement and
  re-file keep authoring through the existing committer/writer. Both changes deliberately do not
  touch `SessionDiffer`.

## 5. Shared implementation files to coordinate

| Area | Files | Both changes touch? |
|---|---|---|
| Sync poll & concurrency | `work/SteamSyncWorker.kt`, `work/SteamSyncCoordinator.kt`, `work/SyncScheduler.kt` | Shared sync boundary: presence adds attribution/placement reads; this change adds attributable results, baseline evidence, admission seam. Preserve separate manual/periodic unique names, `KEEP` reuse, and exactly-once ledger. |
| Session authorship | `data/repo/SessionActionWriter.kt`, `domain/PlaytimeObservationCommitter.kt`, `domain/SessionDiffer.kt`, `data/repo/PresenceSessionRecorder.kt` | Presence acts through them; this change must preserve them untouched. |
| Import / reset | `data/repo/ProfileRepository.kt`, `domain/PlaytimeBackfillUseCase.kt`, `domain/PendingImportRecomputeUseCase.kt` | Import/reset is the shared seam for the cloud transfer budget and the tightened import. |
| Derived recompute | `domain/GamificationUpdater.kt`, `domain/DerivedStateWriteCoordinator.kt`, `domain/RecomputeSource.kt`, `domain/VersionedDerivedPersistence.kt` | Presence re-uses; this change adds `BACKFILL` provenance handling. |
| Cloud re-file | `domain/CloudPresencePlaytimeRefilingUseCase.kt`, `domain/CloudPresencePreDataAllocationRule.kt`, `domain/CloudPresencePlaytimePlacement.kt`, `data/repo/CloudPresence…` | Presence owns; this change only guards reset/import around the transfer. |
| Setup | `data/setup/SetupStateStore.kt`, `work/setup/SetupCoordinator.kt`, `work/setup/SetupStageRegistry.kt`, `ui/setup/*.kt`, `ui/onboarding/*.kt` | This change only (recovery/foreground settlement/history step). |
| Settings UI | `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `SettingsOverviewScreen.kt`, `SettingsPresentation.kt`, `CloudHistoricalRangeControls.kt` | Yes — presence's `Re-file history action` and this change's `Run setup`/`Data` sections share the Settings destination; edit by section, keep current cloud placement/reversal copy. |
| Persistence & account | `data/local/entity/PlayerProfile.kt`, `Game.kt`, `Session.kt`, `data/local/SettingsDataStore.kt`, `data/repo/AccountRoomReset.kt` | Yes — baseline confirmation, first-run phase, import provenance, and cloud transfer receipt all live here; account reset must fence all of them. |

## 6. Conclusion

- Shared spec files: `steam-sync`, `app-settings` only.
- Requirement names: disjoint in each shared file — no conflicts.
- Landed protocols to preserve: imported-play freeze/reset (incl. `BLOCKED_BY_CLOUD_TRANSFER`),
  one-time reversible cloud re-file/transfer with journal and receipt, presence session ownership via
  the single committer/writer, start-date attribution, post-play targeted-fetch schedule, and
  non-earned `BACKFILL` recompute provenance.
- Sole session author: the existing `PlaytimeObservationCommitter`/`SessionActionWriter` chain —
  this change must not add a session-writing path.

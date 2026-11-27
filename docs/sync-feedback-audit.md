# Sync and action feedback audit

Change: `issue161-enhancement-sync-progress-and-offline-feedback-minor` (#161).

The audit follows each action to its owning surface. A shell activity cue is not an
additional action-result message. Offline status comes only from Android's validated
network capability; it neither gates local writes nor interprets a Steam error.

| Surface | Existing acknowledgement and recovery | Gap and resolution |
| --- | --- | --- |
| Library | Favorite state, collection picker membership/result, HLTB batch progress and match outcome stay on their affected controls. Picker failures retain the picker and retry path. | Kept these owning-surface results; no second shell toast was added. Offline status stays above cached Library content. |
| Game detail | Favorite pending/committed state and retry, achievement refresh outcomes, cover save/reset feedback and cached content. | Kept these controls usable offline. Shell status also remains above pushed detail. |
| Collections | Save completion navigates back to the updated collection list; membership removal identifies the affected game and collection; editor failures retain the draft and Refresh editor action. | Delete could throw without feedback. It now prevents concurrent writes, retains the surface on failure and offers Retry deletion in overview. Editor save/conflict/delete errors resolve from string resources. |
| HLTB review | Applying or clearing a match updates the affected game/queue; manual-link validation, no-result and broader-search failures expose recovery controls. | Broader-search failure exposed an internal failure enum. It now uses a localized readable message and retains Retry, Dismiss and manual-link recovery. |
| Onboarding / Setup | Credentials have inline verification errors; Setup retains stage-specific cancellation, retry and recovery. History import has Retry import and pending-import Continue/Resume. | History import could display an exception containing endpoint details. It now retains a safe error and resolves visible copy from resources. Setup observes its recorded job ID, including inline achievements and available terminal counts, without starting reconciliation. |
| Backup / restore | Validation rejections, cross-account confirmation and explicit acknowledgement of import/export/restore results are retained. | Backup exceptions could include raw implementation details, and cancellation was treated as failure. Failure copy is now resource-based with file/storage recovery guidance; cancellation propagates; repeated taps are guarded before launching work. |
| Settings | Steam and full achievement refresh retain independent actions; history readiness and reset restrictions remain visible. | Each operation now has its own queued/running/retry/terminal summary and measured counts. History failures now stay inline beside the import/resume action instead of relying on a transient toast. |

## Delivery and recovery

- Settings operation results are rendered from WorkManager state, so reattaching does
  not replay a transient message. `ManualSyncFeedbackTracker` remains responsible for
  the existing one-time manual failure haptic; the shell adds no result delivery.
- Setup results are filtered by the stage's durable work ID and the active account
  generation. Settings operations are independent; sleeping periodic jobs stay idle.
- Unknown constraint reasons remain queued. Counts cover completed attempts against
  a fixed selected set, including failed or skipped games. Changed-content counts
  exclude fetch timestamps. Unknown-duration phases show no synthetic percentage.
- Existing undo/reset actions remain unchanged. No undo is invented for collection
  deletion or for network operations that have no reversible contract.
- Newly added messages use resources. This change does not translate the application's
  existing English-only UI; unsupported locales use the default resource fallback.

## Verification

Host checks cover operation identity/state mapping, throttled progress, mixed fetch
results, unchanged refreshes, Setup recovery and stage independence, overlapping
Settings operations, connectivity snapshots/callback cleanup, collection deletion
failure/retry, history failure redaction, localization fallback, and duplicate manual
feedback suppression. Existing favorite, HLTB, membership and backup checks cover
preserved action contracts.

Roborazzi fixtures under `sync-feedback/` cover Setup fetching/achievement phases,
Settings overlap/partial results/history failure, and offline Library/detail at
320dp and 412dp in both themes. The existing Setup and main-screen baselines are
verified with them. See [visual regression screenshots](visual-regression-screenshots.md).

Targeted instrumentation uses the GPU-verified launcher and `ANDROID_SERIAL` plus
the runner serial argument. `SyncFeedbackDeviceTest` exercises the actual progress
and banner composables, recovery controls, and shell presentation with local
interaction. `SetupConcurrentWorkDeviceTest` exercises real WorkManager concurrency
and interrupted history recovery with fixture responses. Neither suite contacts
Steam. Live Steam request timing and a physical-device network handover are outside
these deterministic checks.

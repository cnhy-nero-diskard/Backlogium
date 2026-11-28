# Tasks

## 1. Operation reporting

- [x] 1.1 Extend Steam sync and achievement refresh progress and outcome payloads. Verify determinate and indeterminate phases, partial success, retry, cancellation and stale operation rejection.
- [x] 1.2 Connect progress to Setup's existing library stage and the two Settings operation rows. Verify inline achievement progress, queued work, stage independence, recreation, upgrade while work is pending and independent overlapping operations, including a live periodic retry overlapping a completed manual run.

## 2. Connectivity and action feedback

- [x] 2.1 Add validated connectivity to the shell presentation and verify cold launch, pushed screens, reconnection, offline edits and a Steam failure on a validated connection.
- [x] 2.2 Audit the requested surfaces and document feedback gaps and fixes. Verify action acknowledgement, retry or undo where available, localized inline messages and duplicate suppression.

## 3. Integration

- [x] 3.1 Verify representative setup and Settings progress and shell states on a targeted device or emulator, then re-record and verify changed screen baselines together with the UI.

## 4. PR review follow-up

- [x] 4.1 Keep execution IDs distinct from WorkManager request IDs; correlate Setup and periodic reports to the correct request, with overlap regressions.
- [x] 4.2 Preserve committed partial Setup outcomes through recovery and expose retry; cover persistence and recovery behavior.
- [x] 4.3 Add a delete-specific retry action for failed collection deletion in editor mode and cover it with a UI regression.
- [x] 4.4 Extend migration chains through v54 and cover the v53-to-v54 nullable-evidence migration.
- [x] 4.5 Re-run affected JVM, instrumentation, and screenshot checks; inspect repeated Library gradient diffs without re-recording baselines.
- [x] 4.6 Keep periodic and one-time execution reports independent through overlap; verify live-work precedence, retry identity and counter reset, removed/replaced WorkInfo, cancellation, and known connectivity stops, including exact-ID Setup recovery after interruption/recreation with retained or cleared WorkManager progress.

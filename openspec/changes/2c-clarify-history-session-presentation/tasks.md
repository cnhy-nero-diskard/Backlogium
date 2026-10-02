## 1. Baseline and integration

- [x] 1.1 Confirm `1c` is implemented and its daily evidence contract is available; verify the projection and its reconciliation scenarios before wiring History.
- [x] 1.2 Capture representative multi-game/interleaved, overnight, open, sparse, progress-only, and cloud-assisted History states on device/emulator; deliver labelled baseline evidence and record whether the reported confusion is reproduced.
- [x] 1.3 Integrate the daily evidence contract into the loaded History window without per-day query fan-out; verify progress-only totals, unavailable outcomes, both credit differences, hidden exclusions, and snapshot coherence.

## 2. Presentation clarity

- [x] 2.1 Label approximate starts, recorded amounts, and live state visibly and in semantics; verify overnight attribution and no exact end-time/proportional-axis implication in focused presentation checks.
- [x] 2.2 Clarify game grouping/order and missing-record wording with localized copy; verify an interleaved multi-game day does not imply a global chronological sequence or assert that a gap proves no play.
- [x] 2.3 Preserve expansion, scroll, older/fully-loaded state, detail navigation, account reset, and cloud reveal; verify exact-session reveal, disappearing targets, midnight transitions, and earlier-window loading in regression checks.

## 3. Acceptance

- [x] 3.1 Run relevant History/domain/repository tests and `./gradlew.bat :app:compileDebugKotlin :app:lintDebug`; verify inspection changes no stored activity, quest outcome, XP, or external request count.
- [x] 3.2 Compare the revised device/emulator states with the baseline in both themes and larger font scale; verify named expand/collapse and navigation actions are understandable through assistive technology and record remaining limitations.
- [x] 3.3 Reconcile shared UI models and requirement names with drafts #176/#177, then run `openspec.cmd validate 2c-clarify-history-session-presentation --strict` and `git diff --check`; verify unlock-only History and XP remain owned by their existing proposal.

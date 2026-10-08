# Verification

## Baseline

`HltbMatchCenterSelectionTest.navigatingPastUnresolvedGames_thenMatchingLastGame_returnsToPassedGame`
passed against the unchanged selector on 2026-10-03.

| Action | Display queue (app IDs) | Selected ID | Outcome |
| --- | --- | --- | --- |
| Open | [1 ambiguous, 2 unmatched, 3 unmatched] | 1 | Unresolved |
| Next | [1, 2, 3] | 2 | 1 remains unresolved |
| Next | [1, 2, 3] | 3 | 2 remains unresolved |
| Match 3 | [1, 2] | 2 | 3 resolved; selection returns to passed game 2 |

The jump is reproducible without an identity mismatch. Next/Previous record no deferral;
removal of the last selected game clamps to a previously passed unresolved neighbor.
The existing unmatched-to-ambiguous test also passes, confirming app-ID tracking survives
partition changes. The fix needs session deferral rather than replacing identity tracking.

Check: `:app:testDebugUnitTest --tests com.example.backlogium.ui.review.HltbMatchCenterSelectionTest --offline`
(13 tests, passed). SDK discovery used process-local `ANDROID_HOME`.

## Session progression and lifetime

- `HltbReviewSessionTest` covers explicit Skip, Next/Previous deferral, latest queue ordering,
  partition movement, removal with forward/earlier fallback, late queue emissions, exhaustion,
  Review skipped, scoped deferral, and new-session reset.
- `HltbRepositoryTest.reviewSessionDeferralDoesNotChangeStoredRowsOrRepositoryQueue` checks that
  deferred ambiguous and unmatched rows retain their original stored values and repository queue IDs.
- Reviewer unit tests and `HltbRepositoryTest` passed; debug and test APKs assembled successfully.
- `HltbReviewNavigationTest`: three presentation/navigation cases passed on the connected API 36
  phone. Four cases (including tab dismissal) passed on the API 35 emulator. They use the actual
  reviewer presentation and session reducer under a route-scoped ViewModel with a controlled queue.
- Process restart was checked in two separate emulator instrumentation processes: save a deferred
  scoped route's navigation bundle, `adb shell am force-stop com.example.backlogium.debug`, then
  restore that bundle. The route restores scoped to app 1 with no deferred IDs. Both phases passed.
  Session state is neither written to the bundle nor to repository storage.
- Captures are in the test host's external-files `hltb-review/` folder: general exhaustion,
  scoped retention, selection retention, and process restart.

The updated test APK initially hit `INSTALL_FAILED_USER_RESTRICTED` on the phone; this was an
installation failure before tests ran. The restart and expanded navigation checks used the emulator.

## Entry points and integrated review

`HltbReviewEntryTest` passed on the API 35 emulator with the rebuilt debug APKs:

| Entry | Verified behavior |
| --- | --- |
| Library list | Per-game dialog opens the targeted reviewer for app 2; resolving it returns to Library while app 1 remains unresolved |
| Library grid | Labeled completion-time button opens the same dialog and scoped route |
| Library compact grid | Same target and distinct game-detail click behavior |
| Per-game lookup, all densities | A no-match lookup for app 3 consumes its one-shot attention request and opens only app 3 |
| Settings Gameplay, queue populated | Match Center opens the general route; skipping both partitions exhausts the pass |
| Settings Gameplay, queue empty | Match Center remains available and shows the empty state; Done returns to Gameplay |

The final combined emulator run covered entry points, reviewer navigation, and the existing Library
selection accessibility contract: 10 passed, with the process-phase-only case skipped in that run.
The process save and restore phases each passed separately after a force-stop, using the final APKs.
Queue fixtures deliberately cover ambiguous-plus-unmatched ordering, unmatched-to-ambiguous movement,
selected removal, last-game deferral, another pass, scoped completion, and unrelated remaining games.

Skip and Next/Previous callbacks carry the displayed app ID. A regression test verifies that a tap
from a stale frame cannot defer or navigate the replacement selected after a repository emission.

Final focused JVM checks passed: 196 tests across reviewer, Library, Settings, and HLTB repository
coverage. Debug and instrumentation APK assembly passed; gamification tests were up to date.
Final `:app:lintDebug` passed with the repository baseline: 0 unfiltered errors and 51 warnings.

The full JVM suite initially ran 1,813 tests with one Windows `AccessDeniedException` in
`CloudRoutineSettingsTest`. Its DataStore instance pointed at a previous test's temporary directory
(`BackupTransactionalIntegrityTest`). Running the cloud class together reproduced cross-test
temporary-directory reuse. All five cloud cases passed when each ran in a separate test process;
no cloud settings or backup implementation was changed. The ordinary full-suite command therefore
has a known Windows test-isolation limitation; the 196 focused checks are green.

Captured exhaustion, both grids, scoped entry, and Gameplay shortcut screens were visually inspected.
Local captures are under `app/build/outputs/hltb-review/hltb-review/` (ignored build output).
OpenSpec strict validation passed. Planning artifacts remain active for a separate archive decision.

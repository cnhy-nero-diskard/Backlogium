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

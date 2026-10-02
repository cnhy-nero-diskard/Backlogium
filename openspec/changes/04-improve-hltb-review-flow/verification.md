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

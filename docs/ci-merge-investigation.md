# Green pull request, failing master: CI investigation

Investigated on 2026-11-18 against master `6e874d14`.

## Findings

Three merged PRs show green PR Android unit-test runs and failing master unit-test
runs. In every case, the PR's synthetic merge commit and the actual master merge
had identical Git trees. This rules out newly merged source differences for these
incidents. Both events use the same `./gradlew test` job, and the logs show
`:app:testDebugUnitTest` executing rather than restoring a cached test result.

| PR | Green PR run | Failing master run | Failure | Existing fix on master |
| --- | --- | --- | --- | --- |
| [#148](https://github.com/cnhy-nero-diskard/Backlogium/pull/148) | [35694795159](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/35694795159) | [35694799050](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/35694799050) | `WriteIntegrityDaoTest.presenceSessionGuardHoldsWhileSyncCoordinatorIsHeldElsewhere`: assertion failure. The supposed overlapping observations could read in sequence, letting the older observation close the newer session as a backwards-clock observation. | `e76b3281` coordinates both reads before either writes. |
| [#184](https://github.com/cnhy-nero-diskard/Backlogium/pull/184) | [39804787930](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/39804787930) | [39806079692](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/39806079692) | `RecoveryFinalizationCrashReplayTest.resumeAfterDateAndZoneChangeUsesCapturedDateAndKeepsHistoricalCorrectionSilent`: assertion failure. Independent Room fixtures shared a settings file containing durable recovery marks. | `d8722157` gives each fixture its own settings directory while preserving that directory across crash/reopen. |
| [#191](https://github.com/cnhy-nero-diskard/Backlogium/pull/191) | [44048167604](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/44048167604) | [44049183667](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/44049183667) | `GameGenreRepositoryTest.storeOrderSurvivesTheRoundTripThroughTheCache`: `UncaughtExceptionsBeforeTest`. The targeted follow-up isolates the test from production startup and its background DataStore readers. | [#193](https://github.com/cnhy-nero-diskard/Backlogium/pull/193), `0eef0013`, uses a plain `Application`. |

The same #148 test failed again on the subsequent documentation-only master push
[35696200798](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/35696200798).
The latest master CI run,
[44325150889](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/44325150889),
is green. These are multiple test nondeterminism problems, not one recurring
compile failure or evidence that GitHub changes application code when merging.

The matching tree IDs are:

| PR | Tested synthetic merge | Master merge | Shared tree |
| --- | --- | --- | --- |
| #148 | `70aa7ae` | `705f4d77` | `0b2f8816db363a7ac94da48e634ce2448253f4ff` |
| #184 | `7d4f7bc` | `4ac868b5` | `43e35ce8b09e9f6a42b8a190ab4307cb04c95ad9` |
| #191 | `33f7fdf` | `ea4489bd` | `34b0f26a85d06ab110fece414e683075c958fdd9` |

## Prevention

`app/src/test/resources/robolectric.properties` now selects
`android.app.Application` for local JVM tests. Previously most Robolectric tests
used the manifest's `BacklogiumApp`, which starts application-scope coroutines,
DataStore readers, recovery, and schedulers. Component tests construct their own
dependencies and must not accidentally start these unrelated jobs. The regression
test checks the shared default both with and without a screen-specific `@Config`.
An intentional app-startup integration test can explicitly override the application
and must own teardown of its background work. This configuration mechanism and
annotation precedence are documented by
[Robolectric](https://robolectric.org/configuring/).

The unit-test failure artifact now includes JUnit XML (`build/test-results`) as
well as HTML. This preserves stack traces and suppressed coroutine exceptions for
the next investigation. Fix shared files, lifecycle leaks, and uncontrolled
interleavings at their source; retrying until green does not establish stability.

## Separate merge-policy gap

The active master ruleset `20130588` requires seven check contexts, but
`strict_required_status_checks_policy` is `false`, and the authenticated repository
administrator and several integrations can bypass it. Consequently, required
checks do not guarantee that a branch was tested against the latest master, nor
that a privileged merge waited for CI. For #148, the final PR CI run was created
in the same second as the merge and completed afterwards.

Consider requiring branches to be up to date before merging and using the normal
required-check path for administrator/integration merges. Those settings would
reduce untested integration risk, but would not cure the identical-tree flaky
tests above. This investigation does not alter repository rules or bypass actors.

## Local validation

- Added the application-isolation regression tests first: both failed, reporting
  `BacklogiumApp` instead of `android.app.Application`. Both are included in the
  passing suite after applying the shared setting.
- `./gradlew test`: passed; the app executed 2,382 tests with no failures or skips.
  The gamification task was up to date, with 53 passing tests in its existing report.
- Parsed the changed CI YAML successfully and ran `git diff --check`.
- `./gradlew :app:lintDebug :app:compileReleaseKotlin`: passed. Lint reported
  60 warnings, with existing baseline entries filtering two errors.
- The CI screenshot command executed 78 tests with isolation: 73 passed and five
  library image comparisons failed. The visual differences are in card gradients.
  A control run using the original application setting failed 77 of 78 tests with
  leaked startup exceptions, including a suppressed `AndroidKeyStore not found`
  error from background credential-store work. All five image comparisons also
  failed when run individually with the original application setting. For the
  four subsequent controls, the actual PNG hashes matched the isolated captures
  byte for byte. These image mismatches reproduce independently of the setting;
  the full visual gate remains unverified on the CI host. No baselines were
  re-recorded.

These checks ran on the local Mac with JDK 25. Hosted CI uses Ubuntu with JDK 17;
the pull request checks provide validation in that environment.

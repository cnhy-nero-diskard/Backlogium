# Personal play momentum acceptance

The new card compares September 26–October 2, 2026 with September 19–25 in controlled October 3 fixtures. It follows the existing selected-period overview and retains those independent completed-week dates when the main selector changes. Growth and newly recorded activity have separate text groups; current/baseline amounts and the qualifying reason are readable without color. The updating status precedes dated results so it stays visible with larger text.

## Evidence and verification

- [Captures](captures/) cover candidates, newly recorded rows, learning, no qualifying increase, empty eligibility, and updating in light 100% and dark 150% fonts. Additional narrower captures use a temporary 840×2400 viewport at 420 dpi (320 dp); the emulator's original 1080×2400 viewport is restored afterward. Long titles wrap, buttons remain at least 48 dp high, and lower rows/caveats are reachable by scrolling. The named actions use a small corner radius so a multiline title is not clipped by a capsule-shaped button at larger text sizes.
- Twelve PersonalMomentumDeviceTest checks exercise the production card, exact dated scope, named detail actions, unavailable feedback and the reachable criteria dialog. The full AnalyticsContent host selects the main year period, opens a named detail action, returns through a controlled route and verifies the main selection and momentum dates are preserved. This host tests the presentation contract; production navigation uses the existing game-detail route and its back-stack ViewModel.
- Eleven focused JVM/Room checks cover threshold edges, exact unrounded 25% qualification, tiny/zero baselines, distinct active dates, deterministic growth/new ordering and the combined five-title limit, wide sums, insufficient history, whole overnight attribution, exact edges, DST, date/zone/account replacement and action-time visibility. Room tests correct/finalize/refile existing rows and hide a title without inserting a replacement ID. A transaction changes current and baseline together and every displayed complete result preserves the expected delta.
- The final broader app suite contains 120 passing tests spanning momentum, daily activity, History, Analytics headline/window/day selection, Home day fields and gamification updates/events. The gamification module contains 53 passing tests covering quest/streak and achievement XP behavior. No mutation, engine or attribution policy is introduced by momentum.

The repository reads visible library identities, only app ID/start/minutes/open fields from positive finalized sessions in the bounded 14 local dates, and a scalar earliest eligible finalized timestamp, within one Room transaction. Day summaries are mapped off the main thread. It excludes hidden/non-library sessions, today, open sessions, Steam lifetime/backfill totals and undated manual credit. Tests compare session data and backfill before/after the read. There is no remote dependency, network lookup, schema migration, worker or persistent sampling in this read path.

Room invalidation includes sessions, games and hidden games. Coalesced latest reads reject obsolete key completions. Account replacement clears prior content; same-account recomputation retains the prior snapshot's dates with an updating label. Active-screen clock/date/zone broadcasts restart the local-midnight date flow; no periodic alarm or wake lock is added. Detail actions check the current credential account before and after local visibility lookup and confirm that the candidate remains displayed.

## Build and coordination

Validation uses the ignored `.gradle/activity-validation.gradle` init script to isolate the debug application as `com.example.backlogium.activityclarity`, protecting the existing app/database from the parent checkout's newer schema. It changes only the validation application ID. Android 15/API 35 emulator `emulator-5554` hosts the fixtures; the separate offline emulator is untouched.

Commands:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA/Android/Sdk"
./gradlew.bat -I .gradle/activity-validation.gradle :app:compileDebugKotlin :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew.bat -I .gradle/activity-validation.gradle :app:testDebugUnitTest --tests '*PersonalMomentum*Test' --tests '*MomentumNavigationTest' --tests '*Analytics*Test' --tests '*DailyActivity*Test' --tests '*History*Test' --tests '*HomeDayFieldsTest' --tests '*GamificationUpdaterTest' --tests '*GamificationProgressEventsTest' :gamification:test
openspec.cmd validate 4c-add-personal-play-momentum --strict
git diff --check
```

Compile, APK assembly and lint pass with the existing baseline unchanged (52 warnings; two existing errors filtered). The final instrumentation regression passes all 43 checks: momentum 12, Analytics scope 8, existing Analytics 4, History clarity 4, existing History 11, Home daily activity 4. Two further candidate checks pass at the narrower viewport. OpenSpec strict validation and diff checks pass.

The [sourced community feasibility and cost worksheet](../../../../openspec/changes/4c-add-personal-play-momentum/design.md) records a production no-go pending comparable samples, measured budgets and a separate privacy/operating design. One public research probe is documented there; it adds no runtime request behavior and used no private key. Existing detail player-count polling is unchanged. [#173](https://github.com/cnhy-nero-diskard/Backlogium/issues/173) remains open for community implementation. [#147](https://github.com/cnhy-nero-diskard/Backlogium/issues/147) remains the broader Analytics roadmap; [#168](https://github.com/cnhy-nero-diskard/Backlogium/issues/168) owns recent-play collection membership rather than comparative ranking. No issue or PR is closed, commented on or merged by this work.

No live account, physical device, manual TalkBack traversal, background scheduling pilot or live CDN acceptance was exercised. Compose semantics verify the scope, amounts, reasons and named buttons; screenshots verify representative wrapping in both themes. The finalized-record history gate does not prove continuous tracking coverage, and the explanation says so explicitly. This change is ready for separate archive/sync review; it does not create a `5c` proposal or complete #173's community portion.

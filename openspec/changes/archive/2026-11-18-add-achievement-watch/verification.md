# Verification checkpoint — 2026-11-14

All integration regression, watch device, and catalogue acceptance checks passed.

Completed checks:

- Achievement repository, shared-fetch ownership, missing-catalogue completion, catalogue storage, watch commits, recompute recovery, retry policy, progress events/transition recovery, settings persistence, and game-detail mapping/UI tests.
- All 33 migration tests on the launcher-verified `emulator-5554` (Goldfish GFXStream / Apple M5). Versions 44 and 45 add empty outbox/catalogue tables and preserve existing achievement rows and rarity snapshots.
- Foreground/background claim, uncertain-post identity reuse, notification permission suppression, preference/account/hidden-game suppression, and exact notification destination tests.
- Fake-clock serial 60/30-second cadence, failure backoff/reset, and cancellation checks. Watch diagnostics cover success, unchanged, deferred rarity, offline, cancellation, and throttling; detail diagnostics distinguish catalogue and player outcomes.
- New unlock-dialog and watch-settings Roborazzi baselines recorded, visually reviewed, and verified. Existing unrelated main/alternative captures are retained.
- Strict OpenSpec validation.

## Delivery and cadence limits

Monitoring is best effort while the opted-in Live monitor can run. Only its confirmed active game is watched. Offline work pauses and failed/throttled reads may back off longer than the selected 60/30-second cadence. Process death does not preserve a recurring timer; ordinary sync remains the catch-up path.

A foreground claim is terminal before rendering. Process death between that durable claim and the visible dialog can suppress that presentation. Background retries retain the committed background choice and reuse the full event-derived notification tag with `onlyAlertOnce`. These boundaries guarantee one logical consumption; Room and Android notification sound/vibration cannot share an atomic transaction.

## Repeating the live catalogue check

The live catalogue test in `AchievementDeviceTest` uses a temporary database and runtime debug credentials to verify hydration for 1462040, 2909400, and 552520 without fixed achievement counts or captured account fixtures. Its explicit opt-in keeps live account and network work out of CI.

Run the live catalogue check after the GPU-verified launcher reports the serial:

```sh
scripts/test-emulator.sh start
export ANDROID_SERIAL=emulator-5554 # use the launcher's reported serial
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.serial="$ANDROID_SERIAL" \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.example.backlogium.data.achievement.AchievementDeviceTest#reportedUnplayedGamesLoadOnlyTheirCataloguesAndCacheSurvivesReopen' \
  -Pandroid.testInstrumentationRunnerArguments.verifyAchievementDevice=true
```

If the emulator cannot resolve `api.steampowered.com`, resolve its IPv4 address from the host and pass it as `-Pandroid.testInstrumentationRunnerArguments.achievementSteamIpv4=<address>`. Only the opt-in test client uses the address. It still validates Steam's HTTPS hostname and certificate.

The proposal investigation reported unrelated app 553850 playtime-recovery conservation failures. Those remain outside this change; no playtime-recovery conservation guard has been disabled or changed to exercise achievement browsing.

## Integration regression checks — 2026-11-18

- `./gradlew :gamification:test :app:testDebugUnitTest :app:lintDebug` passed on `6c6ff8a2`. All 2,376 app tests and 53 gamification tests passed, including the achievement repository, playtime, session, and cloud suites. Lint reported zero errors with the existing baseline and 60 warnings.
- All 33 `MigrationTest` cases passed on `emulator-5554` after the launcher verified the Apple M5 GFXStream renderer. Gradle targeted that serial explicitly and ran only the migration class.
- Traced the watch tick through `AchievementRepository.prepareWatch` to the Steam achievement endpoints. The existing presence loop supplies the target and the existing gamification updater recomputes derived values from local data. The branch adds no playtime polling path.

## Watch device acceptance — 2026-11-18

- Three instrumentation checks passed on GPU-verified `emulator-5554`. Controlled Steam responses exercised the real selected 30-second interval, the switch from game A to B, and watch epoch closure on exit. Turning off alerts still persisted the unlock while suppressing its notification. Android's denied notification permission independently suppressed posting.
- The cadence test left its temporary Room database with one pending group and one suppressed group. After an explicit app force-stop, a second instrumentation invocation verified a different Android PID, the same group states, and the retained recompute markers. Only the pending group was eligible for delivery. The second invocation removed the fixture database.
- To repeat the restart check, pass the same unique `achievementRestartDatabase` argument to `selectedCadenceSwitchExitRestartAndAlertOptOutWorkOnDevice` and `pendingAndSuppressedGroupsSurviveProcessRestart` in separate targeted instrumentation invocations, and force-stop the debug app between them. Both invocations require `verifyAchievementDevice=true`.

## Catalogue acceptance

- The full destination and collection overlay each passed the Compose check for catalogue availability and retry. Both production entry points use the same detail screen and start hydration through its shared lifecycle effect.
- `syntheticCatalogueCacheAndLaterProgressAcrossProcessRestart` passed in two Android processes on `emulator-5554`, with an explicit force-stop between runs. A 300-game unplayed fixture library only requested the two opened catalogues. After restart, the positive and confirmed-empty cache entries hydrated without Steam requests. A subsequent player refresh added an authoritative unlock and rarity snapshot without a historical alert. Repository tests also passed for the negative cache and later confirmed progress.
- The live emulator check passed for 1462040, 2909400, and 552520 after Steam credentials were configured. All three opened games loaded nonempty catalogues in a 300-game unplayed fixture library. Every achievement request targeted one of the three opened games, and reopening the fresh catalogues made no further achievement requests. The emulator could not resolve Steam's hostname, so the test used a host-resolved IPv4 address with normal HTTPS validation.

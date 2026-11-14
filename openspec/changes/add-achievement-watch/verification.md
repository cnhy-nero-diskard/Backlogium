# Verification checkpoint — 2026-11-14

Implementation is paused at the user's requested checkpoint. Device acceptance and the final broad regression/lint pass remain pending; the change is not archived.

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

## Pending acceptance

Tasks 4.1, 4.2, and 5.5 remain unchecked. `AchievementDeviceTest` prepares temporary-database checks for selected cadence, switching/stopping, restart, alert opt-out, denied notification permission, and live catalogue hydration for 1462040, 2909400, and 552520. It uses runtime debug credentials and checks for objectives without fixed achievement counts or captured account fixtures. Its explicit opt-in keeps live account/network work out of CI.

Run after the GPU-verified launcher reports the serial, with notification permission revoked on that test installation:

```sh
scripts/test-emulator.sh start
export ANDROID_SERIAL=emulator-5554 # use the launcher's reported serial
adb -s "$ANDROID_SERIAL" shell pm revoke com.example.backlogium.debug android.permission.POST_NOTIFICATIONS
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.serial="$ANDROID_SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.backlogium.data.achievement.AchievementDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.verifyAchievementDevice=true
```

The proposal investigation reported unrelated app 553850 playtime-recovery conservation failures. Those remain outside this change; no playtime-recovery conservation guard has been disabled or changed to exercise achievement browsing.

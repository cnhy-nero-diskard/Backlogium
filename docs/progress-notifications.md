# Progress notifications

Open **Settings → Gameplay → Progress notifications**. Owned-game additions,
broken streaks, daily quota, confirmed completion, and yesterday’s recap start
off. Family-shared admission alerts retain their existing enabled default and
`shared_games` Android channel. Achievement-watch controls remain independent.

Each category has its own toggle and Android sound/vibration settings link.
Android permission and channel settings still govern delivery. Turning a
category on does not replay suppressed events. Disabling notifications leaves
in-app earned progress and the shared-game fallback announcement available.

Receipts commit with owned/shared admissions and confirmed achievement merges;
earned quest/streak finalization saves a separate durable handoff alongside its
in-app marks. Owned alerts reuse the account/generation/game identity of the
achievement admission queue. Initial library and completion baselines, imports,
and administrative writes do not create earned notifications. Dispatch state is
not exported in backups and account reset clears it.

Delivery claims a receipt before calling Android. A process death after that
claim can lose the system alert; recovery deliberately never retries an ambiguous
post, including after the player dismissed it. The independent in-app claim
survives.

The best-effort recap labels the previous local date in the current time zone.
It uses the same canonical dated play projection as History, counts visible
games and unlocks with supported dates, and discloses partial coverage and timing
estimates. Undated unlock observations and lifetime/backfill totals are not
assigned to yesterday. Deferred work skips older dates.

## Validation

Host tests cover transaction rollback, producer interruption before scheduling,
Room reopen, foreground acknowledgement, terminal suppression, account changes,
concurrent claims and ambiguous posting recovery. Recap tests cover midnight,
23/25-hour daylight-saving days, time-zone changes, delayed execution, canonical
activity, hidden games and unsupported unlock dates. The progress Settings card
has a reviewed Roborazzi baseline; the existing main-screen and achievement
Settings baselines are verified separately. Five pre-existing Library card-gradient
baselines were refreshed after reproducing their mismatch on the unchanged base;
Library rendering code is unchanged.

Device acceptance uses temporary local fixture databases and preferences. Start
the GPU-verified launcher first, and substitute its reported serial:

```sh
scripts/test-emulator.sh start
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.serial=emulator-5554 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.backlogium.ui.settings.ProgressAlertDeviceTest,com.example.backlogium.ProgressAlertTapDeviceTest,com.example.backlogium.data.local.MigrationTest
```

`ProgressAlertDeviceTest` starts with `POST_NOTIFICATIONS` denied, exercises the
Settings controls and local earned claim, then grants permission and verifies
fresh delivery and dismissal without replay. Run it on a fresh test install;
when reusing an installed app, revoke that permission on the explicitly targeted
test emulator before starting instrumentation. `ProgressAlertTapDeviceTest`
checks cold launch and asynchronous warm PendingIntent delivery with stale-account
fixtures through the existing validated game-notification entry point. Existing
host navigation tests cover eligible, hidden, missing and switched-account targets.

> The asset stage wraps `add-offline-steam-assets`. If that change has not landed, register the stage
> as unavailable (section 3.6) and leave sections 4.3 and 8.4 for when it does — everything else is
> independent of it.

## 1. Credential verification

- [x] 1.1 Add a verification call using `GetPlayerSummaries` for the entered SteamID with the entered key — the cheapest request that exercises both values at once
- [x] 1.2 Map the outcomes distinctly: HTTP 403 to "key rejected", 200 with an empty players list to "no profile found", 200 with a player to success, and a transport failure to "could not reach Steam"
- [x] 1.3 Return the user to the key entry on a rejected key and to the SteamID entry on a missing profile
- [x] 1.4 Present a network failure with a retry action, never as a validation error, and preserve both entered values across the retry
- [x] 1.5 Persist credentials only after verification succeeds
- [x] 1.6 Keep the API key out of every log and error message, including the verification failure paths
- [x] 1.7 Do not verify credentials that are already stored
- [x] 1.8 Show verification's pending state inline in the credential flow, reusing the existing vanity-resolution pending treatment rather than adding a second progress mechanism
- [x] 1.9 Confirm no code path persists credentials without a successful verification

## 2. Onboarding flow shape

- [x] 2.1 Replace `OnboardingStep`'s two-value enum with a representation that supports the credential steps plus verification and setup
- [x] 2.2 Derive `"Step N of M"` from the flow rather than from a hardcoded `2`
- [x] 2.3 Present setup after credentials are verified and persisted for the first time
- [x] 2.4 Do not present setup when an already-configured user reopens the flow to edit credentials

## 3. Stage registry

- [x] 3.1 Add a `SetupStage` declaration carrying a stable id, title, detail, default opt-in, execution mode (`IN_SCREEN` / `DETACHED`), and a run entry point
- [x] 3.2 Document that stage ids are persisted and that renaming one orphans stored opt-ins and outcomes, matching the warning the app's other persisted-by-name enums carry
- [x] 3.3 Add an ordered registry, and derive the checklist, run order, progress display, and completion summary from it
- [x] 3.4 Add a `SetupOutcome` per stage: never run, succeeded, failed with a reason, skipped
- [x] 3.5 Persist per-stage opt-in and outcome in DataStore keyed by stage id, ignoring unrecognized ids rather than failing to render
- [x] 3.6 Support a stage being registered but unavailable, with a stated reason, presented disabled and never selectable

## 4. The three stages

> Verification is **not** a stage — it is a precondition of persisting credentials, handled in
> section 1. Registering it would make it simultaneously mandatory (setup requires it) and skippable
> ("Skip setup" skips every stage), which cannot both hold.

- [x] 4.1 Register the library-sync stage: `IN_SCREEN`, selected by default, wrapping `SteamSyncWorker`
- [x] 4.2 Register the asset stage: `DETACHED`, unselected by default, wrapping the offline-assets worker in its missing-only mode
- [x] 4.3 Register the completion-times stage: `DETACHED`, unselected by default, wrapping `HltbRefreshWorker` in its non-forcing whole-library mode
- [x] 4.4 Ensure every stage only enqueues and observes — no fetching, no persistence, no derivation in the stage itself
- [x] 4.5 Confirm the library-sync stage is the baseline poll, creating no historical sessions, by being that poll rather than reimplementing it
- [x] 4.6 Assert in a test that every registered stage is deselectable, so a future non-optional stage cannot be added without the contradiction resurfacing

## 5. Coordinator

- [x] 5.1 Add a coordinator that runs selected stages in registered order, observing each one's `WorkInfo`
- [x] 5.2 Record each stage's terminal outcome as it finishes
- [x] 5.3 Continue to the next stage on failure; never cancel a sibling and never discard its results
- [x] 5.4 Complete setup once every selected stage has reached a terminal outcome, reporting the per-stage summary
- [x] 5.5 Complete immediately with everything skipped when nothing is selected or setup is declined
- [x] 5.6 Rely on each wrapped worker's existing unique work name and policy for concurrency; add no second layer
- [x] 5.7 Implement retry as re-running the stage's work, not as resuming a partial attempt

## 6. Setup surface

- [x] 6.1 Build the checklist from the registry, applying each stage's default selection
- [x] 6.2 Show the running stage's title and its progress — determinate where the work reports a total, indeterminate where it does not
- [x] 6.3 Keep finished stages and their outcomes visible as later stages run
- [x] 6.4 Restore the current stage and its progress when the surface is recreated, without restarting
- [x] 6.5 Offer entry to the app once every `IN_SCREEN` stage has finished, while `DETACHED` stages continue
- [x] 6.6 Offer "Skip setup" throughout, recording every stage as skipped
- [x] 6.7 Present the completion summary naming what succeeded, what failed and why, and what was skipped

## 7. Notifications

- [x] 7.1 Request the notification permission through the existing in-app request before starting the first `DETACHED` stage, proceeding whichever way the user answers
- [x] 7.2 Give each detached stage its own ongoing progress notification, separate from any other stage's
- [x] 7.3 Follow `HltbRefreshWorker`'s pattern of skipping silently when `POST_NOTIFICATIONS` is not granted, without treating that as a stage failure
- [x] 7.4 Ensure a detached stage continues and stays observable when the app's process ends

## 8. Settings entry

- [x] 8.1 Add a "Run setup" entry opening the same checklist, built from the same registry
- [x] 8.2 Show each stage's last recorded outcome
- [x] 8.3 Default every stage to unselected
- [x] 8.4 Offer per-stage retry, replacing that stage's recorded outcome
- [x] 8.5 Explain that credentials are required, rather than starting stages, when none are configured
- [x] 8.6 Confirm the re-run checklist contains only the three stages; verification is not among them because it is not a stage

## 9. Tests

- [x] 9.1 Unit-test the verification outcome mapping across all four cases, asserting a network failure is not reported as invalid credentials
- [x] 9.2 Unit-test that credentials are not persisted unless verification succeeded
- [x] 9.3 Unit-test that a retry after a network failure persists without re-entry
- [x] 9.4 Unit-test that the checklist, run order, and summary are all derived from the registry, by asserting a test-registered stage appears in each without those surfaces changing
- [x] 9.5 Unit-test that deselecting a stage records it skipped and does not enqueue its work
- [x] 9.6 Unit-test that declining setup records every stage skipped and enqueues nothing, and that credentials remain verified and stored regardless — declining setup must never invalidate them
- [x] 9.7 Unit-test failure isolation: a failing stage leaves later stages running and earlier results intact, and setup completes rather than failing
- [x] 9.8 Unit-test that an unavailable stage cannot be selected and does not block the others
- [x] 9.9 Unit-test that an unrecognized persisted stage id is ignored rather than failing to render setup
- [x] 9.10 Unit-test that retry re-enqueues rather than duplicating already-running work

## 10. Verification

- [x] 10.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [x] 10.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [x] 10.3 On device: onboard with a deliberately wrong API key and confirm the message names the key, not the SteamID
- [ ] 10.4 On device: onboard with a valid key and a nonexistent SteamID and confirm the message names the profile
- [x] 10.5 On device in airplane mode: confirm the failure offers retry and does not present the credentials as invalid
- [ ] 10.6 On device: complete onboarding with all stages selected and confirm sync progress shows in-screen and the two detached stages appear as separate notifications
- [ ] 10.7 Leave the setup screen mid-way and confirm the detached stages continue and remain observable
- [ ] 10.8 Background the app during a detached stage and kill its process with `adb shell am kill com.example.backlogium`, confirming the stage continues — **not** `am force-stop`, which puts the package in Android's stopped state where WorkManager is suspended until the user relaunches, and therefore tests nothing about ordinary process death
- [ ] 10.9 Decline setup and confirm the app is fully usable, then run setup from Settings and confirm the same checklist appears with nothing selected
- [ ] 10.10 Force one stage to fail and confirm the others complete and the summary attributes the failure

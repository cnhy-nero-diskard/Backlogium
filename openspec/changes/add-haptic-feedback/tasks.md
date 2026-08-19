## 1. The haptic authority

- [x] 1.1 Add `ui/util/Haptics.kt` with a `HapticIntent` sealed vocabulary: `LevelUp`, `QuestMet`, `StreakMilestone`, `Confirm`, `Reject`, `Toggle`, `Silent`
- [x] 1.2 Write the file's KDoc contract in the register of `ReducedMotion.kt`: one authority for the app, callers name intents, feedback accompanies a presented moment and never fires alone, degrade to a supported effect rather than to nothing
- [x] 1.3 Add a narrow `HapticPlayer` interface with a `play(intent)` method, and a `LocalView`-backed implementation using `View.performHapticFeedback`
- [x] 1.4 Implement the intent-to-constant mapping as an exhaustive `when`, guarding the API-34 constants (`TOGGLE_ON`, `TOGGLE_OFF`) on `Build.VERSION.SDK_INT` with a documented supported fallback for the `minSdk 33` floor
- [x] 1.5 Make `HapticIntent.Silent` deliver nothing, without reaching the player at all
- [x] 1.6 Expose a `rememberHaptics()` composable accessor so call sites take no dependency on `LocalView`

## 2. Committed-action tier

- [x] 2.1 Deliver `Confirm` where a rule change is persisted in `ui/settings/`
- [x] 2.2 Deliver `Confirm` where a restore completes and where a snapshot is deleted, in the Data & Backup surface
- [x] 2.3 Deliver `Toggle` where the live monitor setting is switched
- [x] 2.4 Deliver `Toggle` where Library selection mode is entered and left
- [x] 2.5 Deliver `Reject` where a player-initiated sync fails, alongside the existing error presentation
- [x] 2.6 Audit the touched surfaces to confirm that opening or cancelling a confirmation delivers nothing, and that only the committing path fires
- [x] 2.7 Confirm no other call site was changed — navigation, filtering, sorting, density, and game-detail entry stay silent

## 3. Earned tier

- [x] 3.1 Add `ui/util/ProgressEventHaptics.kt` mapping `ProgressEvent` to `HapticIntent` in one exhaustive `when`
- [x] 3.2 Map `LevelUp`, `QuestMet`, and `StreakMilestone` to their earned intents; map `StreakBroken` to `Silent`, with a comment recording that losing progress is acknowledged visually and not punished haptically
- [x] 3.3 Play the mapped intent at the point a progress event is presented on Home, immediately before it is acknowledged, so the haptic and the visible moment coincide
- [x] 3.4 Verify exactly one haptic is delivered when several events are pending and one is presented

## 4. Tests

- [x] 4.1 Add a recording `HapticPlayer` fake for tests
- [x] 4.2 Unit-test the `ProgressEvent` to `HapticIntent` mapping over every event, asserting `StreakBroken` produces nothing
- [x] 4.3 Unit-test that each committed-action outcome emits its intent exactly once, and that opening or cancelling a confirmation emits nothing
- [x] 4.4 Unit-test the API-level fallback: below API 34, `Toggle` resolves to a supported constant rather than to no effect

## 5. Enforcement and documentation

- [x] 5.1 Add the single-authority invariant to `CLAUDE.md` under "Invariants worth not breaking", with its grep: `grep -rn "performHapticFeedback\|LocalHapticFeedback\|VibrationEffect" app/src/main/java --exclude-dir=util`
- [x] 5.2 Document in `CLAUDE.md` that silence is the default and that no per-site declaration is expected, so the invariant is not later misread as a coverage requirement
- [x] 5.3 Run the invariant and confirm it reports only `ui/util/Haptics.kt`

## 6. Verification

- [x] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest`
- [x] 6.2 Run `./gradlew assembleDebug`
- [x] 6.3 Confirm no manifest change: the app still holds no `VIBRATE` permission
- [x] 6.4 Manually verify on device: save a rule change, toggle the live monitor, enter selection mode — each is felt once, distinctly from the others where the platform allows
- [x] 6.5 Manually verify on device with system touch feedback disabled: nothing buzzes and every affected moment is still fully legible
- [x] 6.6 Manually verify a level-up produced by a background sync is felt at the moment it is presented, not on app launch before it is visible

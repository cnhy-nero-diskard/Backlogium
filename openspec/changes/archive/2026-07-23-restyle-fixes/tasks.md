## 1. Body typography (Space Grotesk)

- [x] 1.1 Add Space Grotesk font files to `app/src/main/res/font/` (Regular, Medium, Bold) and add the SIL OFL license at `docs/licenses/SpaceGrotesk-OFL.txt`
- [x] 1.2 In `ui/theme/Type.kt`, define `SpaceGroteskFontFamily` mapping weights to the bundled fonts
- [x] 1.3 Rebuild the `Typography` object by copying every style from a base `Typography()` with `fontFamily = SpaceGroteskFontFamily`, then re-override the numeral headline styles (`headlineMedium`, `headlineSmall`) back to `DisplayFontFamily`; update the file's explanatory comment
- [x] 1.4 Launch the app and confirm Home, Library, History, dialogs, and the bottom nav render in Space Grotesk with no stock-Roboto text remaining, and that Level/streak numerals stay on Orbitron

## 2. Remove manual goal target

- [x] 2.1 In `ui/library/LibraryScreen.kt`, replace `GoalDialog`'s target `OutlinedTextField` with a mark/unmark affordance (no typed target); update its title/actions accordingly
- [x] 2.2 Update the goal row rendering to drop the `/ target (percent)` text and the target-based progress bar, showing name + icon + playtime
- [x] 2.3 Adjust `LibraryViewModel.tagGoal` / `GameRepository.tagGoal` so marking a goal sets `isGoal = true` without requiring a user-entered target (leave `targetMinutes` dormant)
- [x] 2.4 Verify a game can be marked and unmarked as a goal, moves between sections, and no target prompt or progress percentage appears

## 3. "Sync now" in-flight feedback

- [x] 3.1 Expose a `Flow<Boolean>` sync-in-progress signal derived from WorkManager `getWorkInfosForUniqueWorkFlow(SteamSyncWorker.ONE_TIME_NAME)` (ENQUEUED/RUNNING → true), surfaced via `ProfileRepository`
- [x] 3.2 Add `isSyncing: Boolean` to `HomeUiState` and combine the new flow in `HomeViewModel.uiState`
- [x] 3.3 In `ui/home/HomeScreen.kt`, show a `CircularProgressIndicator` in place of the "Sync now" label and set `enabled = !isSyncing` while syncing; reflect the syncing state on the "Last sync" line
- [x] 3.4 Trigger "Sync now" and confirm the button shows progress + disables during the poll and returns to idle on completion (success and error paths)

## 4. Verification

- [x] 4.1 Run `./gradlew assembleDebug` (and existing unit tests) to confirm the build is green
- [x] 4.2 Run `openspec validate restyle-fixes` and confirm the change is apply-ready

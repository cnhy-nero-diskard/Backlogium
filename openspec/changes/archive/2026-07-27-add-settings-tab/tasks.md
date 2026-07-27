# Tasks — Settings tab

> No database migration. Every rule already has a `SettingsDataStore` key with a `RuleConfig`
> default, and `longestStreak` is an existing `PlayerProfile` column.
>
> Land this before `enhance-library`: both touch `SettingsDataStore`, `SyncScheduler`, and
> `GamificationUpdater`, and this change edits them structurally.

## 1. Split compute from persist

- [x] 1.1 `GamificationUpdater`: extract a `GamificationResult` holding the `XpState`, the
  per-day `QuestResult` list, and the current/longest streaks
- [x] 1.2 Add `suspend fun compute(today: LocalDate, config: RuleConfig): GamificationResult` —
  all the DAO reads and engine calls from today's `recompute()`, writing nothing
- [x] 1.3 Add `suspend fun persist(result: GamificationResult)` — the `dailyProgressDao.upsert`
  of changed `questMet` values and the `playerProfileDao.upsert` of aggregates
- [x] 1.4 Redefine `recompute(today, config)` as `persist(compute(today, config))`; confirm
  `SteamSyncWorker` and `PlaytimeBackfillUseCase` call sites are unchanged
- [x] 1.5 `GamificationUpdaterTest`: `compute()` writes nothing (assert DAOs untouched);
  `recompute()` still produces the results the existing tests assert

## 2. Longest streak becomes a high-water mark

- [x] 2.1 In `persist()`, write `longestStreak = maxOf(stored.longestStreak, result.longestStreak)`
  (replacing the current unconditional overwrite at `GamificationUpdater.kt:99`)
- [x] 2.2 Leave `currentStreak` written as computed — only the record is protected
- [x] 2.3 Test: a recompute under a stricter `questThresholdMin` leaves `longestStreak` intact
  while `currentStreak` drops
- [x] 2.4 Test: a recompute producing a longer streak still raises `longestStreak`
- [x] 2.5 Confirm the `gamification` module is untouched by this task — the engine's `streak()`
  keeps returning the longest for the days supplied

## 3. Rule-change use case

- [x] 3.1 New `domain/UpdateRuleConfigUseCase`, mirroring `PlaytimeBackfillUseCase`: injects
  `SettingsRepository`, `GamificationUpdater`, `TimeProvider`
- [x] 3.2 `preview(config: RuleConfig): GamificationResult` — `compute()` under the candidate
  config, with `longestStreak` floored at the stored value so the preview matches what
  `persist()` will actually write
- [x] 3.3 `apply(config: RuleConfig)` — persist the config, then `recompute()` under it
- [x] 3.4 `SettingsRepository` stays storage-only; do not inject `GamificationUpdater` into it
- [x] 3.5 Test: `apply()` leaves stored XP/level/streaks consistent with the new config
- [x] 3.6 Test: `preview()` mutates nothing — config and profile unchanged afterward
- [x] 3.7 Test: `preview()` reports the protected longest streak, not the raw computed drop

## 4. Sync indicator plumbing

- [x] 4.1 `SyncScheduler.syncInProgress`: combine the one-time and periodic unique-work flows —
  one-time matches `ENQUEUED || RUNNING`, periodic matches **`RUNNING` only**
- [x] 4.2 Test: periodic work in `ENQUEUED` between runs yields `false` (this is the
  spins-forever bug; assert it directly)
- [x] 4.3 Test: periodic work in `RUNNING` yields `true`; manual work in `ENQUEUED` yields `true`
- [x] 4.4 Add a minimum-visible latch operator holding `true` ~700ms past a falling edge
- [x] 4.5 Test the latch with a test dispatcher: a 50ms true-pulse stays true for the full
  minimum, and a long sync is not extended beyond its actual end plus the latch

## 5. Profile header indicator

- [x] 5.1 `ProfileHeaderViewModel`: add the latched sync flow to the `combine` (3 → 4 flows) and
  expose `syncing: Boolean` on `ProfileHeaderUiState`
- [x] 5.2 `ProfileHeader`: render the indicator on the trailing edge of the existing `Row`, after
  a `Modifier.weight(1f)` on the identity `Column` so long persona names still ellipsize
- [x] 5.3 Idle state renders nothing and leaves the header's layout unchanged
- [x] 5.4 Reduced-motion: static cue instead of continuous animation. If `enhance-now-playing`
  has already landed its shared helper, use it; otherwise introduce one here for it to adopt
- [x] 5.5 Verify the header still hides entirely while unconfigured

## 6. Settings destination

- [x] 6.1 `Destination`: add `SETTINGS("settings", "Settings", TablerIcons.Settings)`
- [x] 6.2 `BacklogiumAppRoot`: register the `composable(Destination.SETTINGS.route)`; the existing
  `popUpTo(HOME) { saveState = true }` nav pattern needs no change
- [x] 6.3 New `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt`, sectioned per the spec:
  Account, Sync, Daily quest, Data, Advanced
- [x] 6.4 Unconfigured state: the Account section offers a "connect your Steam account" action
  into the onboarding flow rather than rendering a dead end; rule controls remain editable

## 7. Move the controls off Home

- [x] 7.1 Move `SteamAccountCard` into the Settings Account section; its edit action navigates to
  `ROUTE_ONBOARDING` as it does today
- [x] 7.2 Move `HistoryImportCard` into the Settings Data section, unchanged — both confirmation
  dialogs come with it
- [x] 7.3 Move the last-sync text and "Sync now" button into the Settings Sync section, keeping
  the disabled-while-running behavior
- [x] 7.4 Delete those three blocks from `HomeScreen`; drop the now-unused `onEditCredentials`
  parameter from its signature and from the `BacklogiumAppRoot` call site
- [x] 7.5 Prune `HomeViewModel`: `steamId`, `apiKeyMasked`, `lastSyncAt`, `historyImported`,
  `isImportingHistory`, `importSteamHistory()`, `resetHistoryImport()` move to `SettingsViewModel`.
  Keep `lastSyncError`, and keep `syncNow()` for the retry in task 8

## 8. Home error card gains Retry

- [x] 8.1 Add a retry action to the `lastSyncError` card calling `viewModel.syncNow()`
- [x] 8.2 Disable it while a sync is in flight, reusing the same latched flow as the header
- [x] 8.3 Verify the card disappears once a retry succeeds (it is already driven by
  `profile.lastSyncError`, which the worker clears on success)

## 9. Rule controls

- [x] 9.1 Daily quest section: quest goal minutes, quest mode (`ANY` / `GOAL_ONLY`), streak grace
  days
- [x] 9.2 Advanced section, collapsed by default: `xpPerMinute`, `levelBase`, and the five
  per-tier achievement XP awards
- [x] 9.3 Reject non-positive `levelBase` and `xpPerMinute` at input with an inline reason — do
  not rely on the engine's degenerate-input guards
- [x] 9.4 Test: advanced controls are not composed until the section is expanded

## 10. Consequence guardrails

- [x] 10.1 On save, call `preview()` and present a confirmation stating the concrete before/after:
  current and longest streak for quest-rule changes, total XP and level for advanced changes
- [x] 10.2 Show the confirm affordance in a loading state while `preview()` runs rather than
  blocking the tap
- [x] 10.3 Declining persists nothing and runs no recompute
- [x] 10.4 Confirming calls `apply()`, so Home reflects the new values without waiting for a sync
- [x] 10.5 Test: decline leaves both the stored config and the profile untouched
- [x] 10.6 Test: confirm updates config and profile in one operation
- [x] 10.7 Test: the figures shown in the dialog match what `apply()` actually writes

## 11. Verification

- [x] 11.1 `./gradlew test` green
- [x] 11.2 Manual: change the daily quest goal upward, confirm the dialog names the real streak
  change, confirm Home reflects it immediately without a sync
- [x] 11.3 Manual: confirm the header indicator is idle while sitting on any screen with only
  periodic sync scheduled, and animates during a manual sync
- [x] 11.4 `openspec validate add-settings-tab --strict`

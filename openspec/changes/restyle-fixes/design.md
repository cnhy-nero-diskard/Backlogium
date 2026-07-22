## Context

The `restyle-visual-identity` change (archived) introduced Orbitron as a display font
but wired it only into `headlineMedium`/`headlineSmall` in `ui/theme/Type.kt`. Every
other `Typography` style is left at the `Typography()` defaults, which resolve to the
platform default (Roboto) — so most of the app reads as stock Android.

Goal games are modeled on `Game` with `isGoal: Boolean` and `targetMinutes: Int?`. The
Library goal dialog (`LibraryScreen.kt` `GoalDialog`) collects a minutes integer and
calls `viewModel.tagGoal(appId, minutes)` → `GameRepository.tagGoal`; goal rows render
`playtime / target (percent)`.

`syncNow()` is fire-and-forget: `ProfileRepository.syncNow()` → `SyncScheduler.syncNow()`
enqueues a unique one-time `SteamSyncWorker`. No in-flight state is observable by the UI,
so the "Sync now" button has no visible effect until `lastSyncAt` changes on completion.

## Goals / Non-Goals

**Goals:**
- Every visible string renders in a bundled brand font: Space Grotesk for body/UI text,
  Orbitron retained for large numeral moments. Nothing falls back to `FontFamily.Default`.
- "Set as goal" becomes a flag toggle; remove the typed target input and target-based
  progress from the Library UI while keeping goal marking/unmarking.
- "Sync now" shows a progress indicator and disables while a sync is in flight.

**Non-Goals:**
- Goal progress against HowLongToBeat completionist lengths (arrives with HLTB ingestion).
- Live "Now playing" indicator and any sub-15-minute polling (separate change).
- Dropping the `targetMinutes` column or migrating stored data (kept dormant).

## Decisions

- **Space Grotesk bundled as a static font, applied via a full `Typography`.** Add
  `res/font/space_grotesk_*.ttf` (Regular/Medium/Bold) + SIL OFL license under
  `docs/licenses/`, mirroring how `orbitron.ttf` is bundled — preserving offline-first
  (no Downloadable Fonts). In `Type.kt`, build the `Typography` by copying every style
  from a base `Typography()` with `fontFamily = SpaceGroteskFontFamily`, then override
  the numeral headline styles back to `DisplayFontFamily`. Copying *all* styles (rather
  than the current handful) is what closes the fallback gap.

- **Goal dialog → flag toggle.** Replace `GoalDialog`'s `OutlinedTextField` and
  `onSave: (Int)` with a confirm/untag action. Keep `GameRepository.tagGoal` callable
  but supply a neutral stored target (e.g. `tagGoal(appId, targetMinutes = 0)` or a
  repo overload that sets `isGoal = true` without touching `targetMinutes`). Goal rows
  render name + icon + `playtime played` (drop the `/ target (percent)` segment and the
  `LinearProgressIndicator`). Prefer a single-action affordance over a dialog if it's
  trivial, but a simplified dialog is acceptable to minimize churn.

- **`isSyncing` derived from WorkManager `WorkInfo`.** Observe the one-time work by its
  unique name (`SteamSyncWorker.ONE_TIME_NAME`) via
  `WorkManager.getWorkInfosForUniqueWorkFlow(...)`, mapping ENQUEUED/RUNNING → syncing.
  Expose it from `ProfileRepository` as a `Flow<Boolean>`, combine it into
  `HomeUiState.isSyncing`, and in `HomeScreen` swap the button label for a small
  `CircularProgressIndicator` + `enabled = !isSyncing`. This reuses WorkManager as the
  single source of truth rather than adding a separate in-memory flag that could desync.

## Risks / Trade-offs

- **Space Grotesk metrics differ from Roboto** — line heights/letter-spacing tuned for
  the old default may need minor adjustment; verify dense rows (Library, History) don't
  clip. Mitigate by keeping the existing size/spacing values and spot-checking screens.
- **Dormant `targetMinutes`** — leaving the column unused is mild debt, but dropping it
  now would require a migration and be re-added for HLTB; keeping it is the cheaper bet.
- **WorkInfo timing** — a very fast sync may flip `isSyncing` on/off quickly; acceptable,
  and expedited/one-time work still passes through ENQUEUED→RUNNING so the indicator shows.
- **Existing goals with stored targets** — no longer surfaced; acceptable per the
  REMOVED requirement's migration note (no user action needed).

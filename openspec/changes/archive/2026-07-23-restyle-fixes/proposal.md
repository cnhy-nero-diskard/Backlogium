## Why

The visual-identity restyle left the app half-branded: the display font (Orbitron)
is applied only to a couple of numeral headline styles, so the majority of the UI
still renders in the stock Android default font, reading as an un-designed "stock
Android" app. Separately, the manual "Set as goal" target (typed in minutes) is a
temporary stand-in for a goal length that should come from HowLongToBeat, and it
already looks wrong in the UI (e.g. 156h played against a 4h target). Finally,
"Sync now" gives no feedback while a sync is in flight, so the app feels
unresponsive.

## What Changes

- **Body typography rollout**: bundle Space Grotesk as the app's body font and apply
  it across all base type styles so no text falls back to `FontFamily.Default`.
  Orbitron remains reserved for large numeral moments (level number, streak count).
- **Remove the manual goal target** (**BREAKING** to the goal-setting UX): "Set as
  goal" becomes a star/flag toggle with no typed target. The `Target (minutes)` input
  and the goal progress percentage/bar are removed until HowLongToBeat-sourced
  completionist lengths are available. A game can still be marked/unmarked as a goal.
- **"Sync now" visual feedback**: expose an in-flight sync state to the Home screen so
  the button shows a progress indicator and is disabled while syncing, and the "Last
  sync" line reflects that a sync is running.

## Capabilities

### New Capabilities
<!-- None. All changes modify existing app-ui behavior. -->

### Modified Capabilities
- `app-ui`: Body/caption text requirement changes from "system default font" to a
  bundled brand body font (display font requirement unchanged); the goal-setting
  requirement changes from a typed minutes target with progress to a goal flag
  without a manual target; a new requirement covers in-flight sync feedback on the
  Home screen.

## Impact

- **Affected code:**
  - `ui/theme/Type.kt` — define Space Grotesk body family, apply across base styles;
    add bundled `res/font/space_grotesk*.ttf` + license under `docs/licenses/`.
  - `ui/library/LibraryScreen.kt` + `ui/library/LibraryViewModel.kt` — replace the
    goal dialog target field with a flag toggle; remove progress % rendering.
  - `ui/home/HomeScreen.kt` + `ui/home/HomeViewModel.kt` — add `isSyncing` to
    `HomeUiState`, drive it from WorkManager `WorkInfo`; button spinner + disabled.
  - `data/repo/ProfileRepository.kt` — expose sync in-flight state if not already
    observable.
- **Dependencies:** no new libraries (Space Grotesk bundled as a static font,
  preserving offline-first).
- **Deferred / out of scope:** goal progress against HowLongToBeat completionist
  averages (arrives with HLTB ingestion) and the live "Now playing" indicator (its
  own change).

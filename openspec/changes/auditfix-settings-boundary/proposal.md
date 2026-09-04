## Why

**Branch: `fix/auditfix-settings-boundary`**

Four audit findings, three of them in one file. `SettingsViewModel.kt` carries two
repository-boundary breaches and is missing one required haptic; three empty-state screens
still point users at a control that was removed from Home. Low individual severity, high
locality — and two of them are `CLAUDE.md` invariant violations that its own documented grep
reports today.

**#97 — a Room entity crosses into UI state.** `SettingsViewModel.kt:17` imports
`data.local.entity.SteamAssetDownloadState`, `SettingsUiState.lastSteamAssetRun` exposes that
entity directly at `:93`, and the ViewModel reads `SteamAssetDao` (`:173`, observed at
`:218-223`). `CLAUDE.md` states that Room entities stay inside `data/` and nothing under
`ui/` imports `data.local.entity.*`, with exactly two deliberate exceptions —
`HltbCandidate`, and `ui/diagnostics/` reading `DiagnosticsDao` directly — neither of which
covers this.

**#125 — the same ViewModel reaches past the repository layer for a second reason.**
`SettingsViewModel.kt:15` imports `GameDao` (`:175`) and observes
`gameDao.observeAppIds()` at `:299-309` solely to compute HLTB dataset coverage. `CLAUDE.md`
scopes direct DAO access to `ui/diagnostics/`; the README documents the `SteamAssetDao`
dependency as known deferred debt but does not list this one. So this finding *expands* the
outstanding breach rather than living under an accepted exception.

**#109 — the Settings manual-sync failure omits the required Reject haptic.**
`app-ui/spec.md:2383-2385` requires that when a sync the player initiated fails, the refusal
intent is delivered once alongside the existing error presentation, and `haptic-feedback`
says the same. Home implements it correctly, with an attribution guard whose comment states
the reasoning: *"The error card is also used by background syncs. Only a retry initiated from
this visible card arms Reject, so a background failure never produces an unattributable
buzz."* Settings has no equivalent — `SettingsViewModel.kt:371` is
`fun syncNow() = profileRepository.syncNow()` and `SettingsScreen.kt:502`'s button invokes it
directly, with nothing observing the outcome. So the *primary* manual-sync control lacks the
haptic that its secondary, retry-from-Home path has.

**#112 — three screens send users to a Home control that no longer exists.**
`app-ui/spec.md:190-206` makes Home progress-only and explicitly excludes the Steam account
card; `app-settings/spec.md:9-35` makes Settings the account destination. But
`LibraryScreen.kt:271`, `HistoryScreen.kt:61`, and `AnalyticsScreen.kt:71` all say "Connect
your Steam account **from the Home screen**". `BacklogiumAppRoot` keeps bottom navigation
available while unconfigured, so these are reachable instructions to do something impossible.

**A fifth breach the audit did not file.** Running `CLAUDE.md`'s own boundary grep on the
current tree also reports `data.local.entity.HltbMatchStatus` imported into `ui/review/` in
three files — `HltbReviewScreen.kt:38`, `HltbReviewViewModel.kt:6`, `SteamGameHeader.kt:26`.
That is on neither exception list and is not the documented `HomeViewModel` breach. It is the
same invariant and the same kind of type, so it is handled here rather than left to be
rediscovered; `design.md` Decision 3 covers whether it is fixed or documented as a third
exception.

## What Changes

- **Settings depends on repository and domain shapes only.** The Steam asset run state and the
  HLTB coverage input both arrive through a repository boundary that owns the mapping, so
  `SettingsViewModel` stops importing `SteamAssetDao`, `GameDao`, and
  `SteamAssetDownloadState`.
- **Settings' Sync now delivers Reject on failure, attributed correctly.** It tracks the
  player-initiated attempt and emits the intent exactly once if *that* attempt fails, mirroring
  Home's guard so an unrelated background failure never buzzes.
- **The three unconfigured empty states point at Settings.** And `app-ui` gains a requirement
  that such guidance names the surface that actually hosts the action, so the next
  administration move does not leave the same trail of stale copy.
- **`HltbMatchStatus` is resolved rather than left ambiguous** — either mapped out of `ui/` or
  recorded in `CLAUDE.md` as a third deliberate exception with its reasoning.
- **The invariant becomes self-checking for what this change fixes.** After it lands,
  `CLAUDE.md`'s grep reports only the documented `HomeViewModel` breach.

**No behavioural change** beyond the added haptic and the corrected copy. The boundary work is
a refactor: the same values reach the same UI through a different seam.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: unconfigured-state guidance names the surface that hosts the action it asks for

`haptic-feedback` needs no delta — its "Committed actions carry an intent" requirement and
`app-ui`'s "Sync failure" scenario (`:2383-2385`) already state the correct behaviour, and
this change makes Settings meet them.

## Impact

| Path | Change |
|---|---|
| `ui/settings/SettingsViewModel.kt` | drops `SteamAssetDao`, `GameDao`, `SteamAssetDownloadState` (`:15-17`, `:93`, `:173-175`, `:218-223`, `:299-309`); tracks the manual-sync attempt (`:371`) |
| `ui/settings/SettingsScreen.kt` | Sync button's outcome observed (`:502`) |
| `data/repo/` | repository surface for asset run state and HLTB coverage input |
| `ui/library/LibraryScreen.kt` | empty-state copy (`:271`) |
| `ui/history/HistoryScreen.kt` | empty-state copy (`:61`) |
| `ui/analytics/AnalyticsScreen.kt` | empty-state copy (`:71`) |
| `ui/review/` ×3 + `CLAUDE.md` | `HltbMatchStatus` — mapped or documented (Decision 3) |
| `README.md` | known-breach list updated to match reality |

**Depends on `auditfix-spec-truth`.** Its #102 repair moves `onboarding-credentials`'
credential-editing requirement from Home to Settings. #112's copy fix is the same stale-Home
story from the code side, and landing it against already-corrected spec text keeps the two
consistent. No code dependency.

**Independent of everything else.** No file here is touched by any other audit-fix change.
This can run in parallel with `auditfix-ui-async-identity` and `auditfix-collections-editor`;
all three add distinctly-named requirements to `app-ui`, so expect a textual merge at sync
time, not a semantic conflict.

**Explicitly out of scope**: the `HomeViewModel` `Collection`/`CollectionMember` breach.
`CLAUDE.md` records it as a known outstanding breach whose fix is to map at the
`CollectionRepository` boundary, deferred because the collections UI surface is broad. It stays
deferred, and the grep in task 5.3 must still report it — a passing grep would mean the
exclusion was widened rather than the breach fixed.

**Not addressed here**: whether `ui/diagnostics/` should keep its documented exception. It is a
deliberate, reasoned carve-out in `CLAUDE.md` and no finding challenges it.

## 1. Prerequisites

- [ ] 1.1 Confirm `auditfix-spec-truth` has landed, so #112's copy fix lands against the corrected `onboarding-credentials` text (#102) rather than alongside it
- [ ] 1.2 Record the current output of `CLAUDE.md`'s boundary grep as the baseline this change moves, including the three unfiled `ui/review/HltbMatchStatus` lines. Verified by the baseline written down before any edit
- [ ] 1.3 Read Home's attribution guard at `HomeScreen.kt:204-215` and its comment before writing the Settings equivalent — it is the established idiom and the reasoning it encodes is load-bearing (design.md Decision 2)

## 2. Empty-state copy (#112 — smallest, do first)

- [ ] 2.1 Update `LibraryScreen.kt:271`, `HistoryScreen.kt:61`, and `AnalyticsScreen.kt:71` to direct the player to Settings rather than "the Home screen". Verified by `grep -rn "from the Home screen" app/src/main/java --include=*.kt` returning nothing
- [ ] 2.2 Confirm the new copy names the surface that actually hosts account configuration, per `app-settings/spec.md:9-35`, rather than a generic instruction. Verified by reading each string against that spec
- [ ] 2.3 Check no other user-facing string sends the player to a removed Home control. Verified by a grep for "Home" across UI strings, triaged

## 3. Settings sync-failure haptic (#109)

- [ ] 3.1 Track the player-initiated sync attempt in Settings and emit `HapticIntent.Reject` exactly once if **that** attempt settles with `lastSyncError`, mirroring `HomeScreen.kt:204-215` (design.md Decision 2). Verified by the tests in 3.4 and 3.5
- [ ] 3.2 **Do not simply observe `lastSyncError` and buzz.** The worker writes it regardless of what started the run, so an unattributed observer buzzes for a background failure the player did not initiate — the exact hazard Home's comment documents
- [ ] 3.3 Keep the visible error presentation unchanged. `app-ui/spec.md:2357-2358` requires the intent alongside, never instead of, the visible result
- [ ] 3.4 Test: a manual sync started from the Settings button that fails delivers Reject once
- [ ] 3.5 Test: a background sync failing while Settings is open delivers nothing — the attribution guard's regression test
- [ ] 3.6 Test: a successful manual sync from Settings delivers no Reject
- [ ] 3.7 Confirm Home's existing behaviour is unchanged and the two entry points cannot double-buzz, since each arms only on its own visible control

## 4. Repository boundary (#97, #125 — largest, do last)

- [ ] 4.1 Expose the last Steam asset run through a repository as a **domain summary of the run**, not a mapped container of the same entity, and drop `SteamAssetDownloadState` from `SettingsUiState.lastSteamAssetRun` (`:93`) (design.md Decision 1). Verified by no `data.local.entity` import remaining in `SettingsViewModel`
- [ ] 4.2 Move the HLTB coverage calculation behind the repository boundary so Settings receives **the coverage figure**, not the owned-app-id list. `SettingsViewModel.kt:299-309` currently observes `gameDao.observeAppIds()` only to compute a count. **Exposing `observeOwnedAppIds()` on a repository is not the fix** — that is a DAO call wearing a different hat (design.md Decision 1)
- [ ] 4.3 Remove the `SteamAssetDao` (`:16`, `:173`) and `GameDao` (`:15`, `:175`) constructor dependencies from `SettingsViewModel`, and rework the `assetStoredState` combine at `:218-223`. Verified by neither DAO appearing in the file
- [ ] 4.4 Confirm the Settings screen renders identically — this is a refactor, and the same values must reach the same UI through the new seam. Verified on a device by comparing the asset card and HLTB coverage figures before and after
- [ ] 4.5 Decide `HltbMatchStatus`: either map it out of `ui/review/` (`HltbReviewScreen.kt:38`, `HltbReviewViewModel.kt:6`, `SteamGameHeader.kt:26`) or record it in `CLAUDE.md` as a third deliberate exception with its reasoning, alongside `HltbCandidate` — which is already an exception for serving the same review surface (design.md Decision 3). **Leaving it undocumented is not an option.** Verified by `CLAUDE.md` and the tree agreeing

## 5. Make the invariant self-checking

- [ ] 5.1 Add `data\.local\.dao` to the alternation in `CLAUDE.md`'s boundary grep, so the command covers what the invariant says. Both #97 and #125 are DAO dependencies and #125 is invisible to the current pattern — which is likely why the README recorded one and not the other (design.md Decision 4). Verified by the updated grep catching a deliberately-added test import
- [ ] 5.2 Update `README.md`'s known-breach list to match reality after this change, removing the Settings `SteamAssetDao` entry it records
- [ ] 5.3 Run the updated grep and confirm it reports **exactly** the two documented `ui/home/HomeViewModel.kt` lines and nothing else. **A silent grep is a failure, not a pass** — it would mean the exclusions were widened rather than the breaches fixed
- [ ] 5.4 Confirm `CLAUDE.md`'s "Known outstanding breach" paragraph on `HomeViewModel` is still accurate and still describes the `CollectionRepository` mapping as the fix. It stays deferred; `auditfix-collections-editor` adds to that repository without attempting it
- [ ] 5.5 Triage anything else the widened grep newly surfaces: fix it here if it is in this change's files, otherwise file an issue. **Do not narrow the pattern to quiet the output**

## 6. Close out

- [ ] 6.1 `openspec validate --strict auditfix-settings-boundary` passes
- [ ] 6.2 `./gradlew :app:testDebugUnitTest` passes
- [ ] 6.3 Confirm the haptics authority grep still produces no output: `grep -rn "performHapticFeedback\|LocalHapticFeedback\|VibrationEffect" app/src/main/java --exclude-dir=util` — the Settings haptic must go through `ui/util/Haptics.kt`'s vocabulary, not the platform
- [ ] 6.4 On a device: open Settings unconfigured from Library's empty state, then tap Sync now with the network off and confirm a single Reject alongside the error
- [ ] 6.5 Sync the delta into `openspec/specs/` via the archive workflow, not by hand
- [ ] 6.6 Close #97, #109, #112, #125. Then close #98 (the audit umbrella) if this is the last of the seven changes to land

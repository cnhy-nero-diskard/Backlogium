## Why

**Branch: `fix/auditfix-ui-async-identity`**

Three audit findings that are the same bug three times: **a result outlives the input or the
state that spawned it, and gets published without checking whether it is still the answer to
the current question.** Grouping them is not filing convenience — the fix is one idiom
applied consistently, and this codebase already contains a correct instance of it to copy.

**#122 — a stale credential request can persist an account the user is not looking at
(`severity/high`).** `OnboardingViewModel.resolveSteamId()` snapshots
`state.steamIdInput`, launches, and then publishes `result.toResolveState()`
unconditionally. `onSteamIdInputChange()` resets the visible state but neither cancels the
in-flight coroutine nor ties it to the input that spawned it, and the field stays enabled
throughout. So: type A, start resolution, edit to B, resolution A completes and publishes
`Resolved(A)` — the screen now offers **Finish** while displaying **B**, and `finish()`
verifies and persists A. On first configuration there is no prior identity for
`CredentialsRepository.save()` to reject as a change, so A is stored. The same shape exists
during verification: the SteamID field and Back stay usable while `VerifyState.Verifying`,
and the old coroutine can still reach `persist()` with its captured key and SteamID.

This one is the reason the group is worth doing early. Every other consequence in this change
is a wrong pixel; this one writes the wrong Steam account to the encrypted store, and
`steam-sync/spec.md:390` ("A playtime baseline is only diffed against the same account")
exists because a mis-stored identity poisons every subsequent diff.

**#126 — a cancelled picker search overwrites its replacement.**
`LibraryViewModel.changeMatch()` wraps the call in
`runCatching { hltbRepository.searchCandidates(name) }`. `CancellationException` is a
`Throwable`, so `runCatching` swallows it and the cancelled job **continues into the shared
`pickerStates.update` block**. Its only guard is `states[appId] ?: return@update states` —
presence of an entry, not ownership of it. So dismissing the picker and quickly reopening it
for the same game leaves job B running while job A's swallowed cancellation publishes
`loading = false, failed = true` over B's fresh state. The user sees a false failure for a
live request, and dismissing that failure cancels B too.

**#127 — the current day stops auto-expanding after midnight.**
`app-ui/spec.md:1920-1922` requires the current day expanded by default.
`HistoryScreen.kt:74-96` tracks `autoExpandedToday` as a one-time `Boolean`. When
`CurrentDateProvider` advances `state.today` across local midnight the `LaunchedEffect(state.today)`
reruns correctly, but the effect refuses to expand the new day because the flag is already
true — so the new current day renders collapsed. This defeats the midnight hardening that
deliberately routed the expand-today anchor through `CurrentDateProvider` in the first place:
the plumbing was fixed and the consumer's one-shot flag was not.

## What Changes

- **One request-identity idiom, applied to all three.** A result may only be published if it
  still belongs to the state it was launched for — the credential input still shows what was
  submitted, the picker entry is still owned by this job, the auto-expanded date is *which*
  date rather than *whether*.
- **`CancellationException` stops being swallowed.** `runCatching` around a suspending call
  is the specific mechanism in #126; cancellation is rethrown so a cancelled job publishes
  nothing at all.
- **`autoExpandedToday: Boolean` becomes the date that was auto-expanded.** So a new current
  day expands once, while the user's manual collapse of the current day still sticks against
  unrelated emissions.
- **The onboarding flow gains request identity across both stages,** resolution and
  verification, so only a result for the currently displayed credential input can advance the
  flow or reach `persist()`.

**The idiom already exists in this codebase.** `LibraryViewModel.previewPickerManualLink()`
— thirty lines below the broken `changeMatch()` in the same class — does it correctly, and
its comment states the rule outright:

> Resolve into the *latest* entry, never the snapshot taken before launch, and drop a result
> whose submitted input was since edited (clearing loading so the new input can be previewed)
> — a stale preview must never overwrite newer user input.

It compares `current.input.trim() == input` before publishing. That is the pattern; this
change spreads it to the three sites that lack it, rather than inventing anything.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `onboarding-credentials`: only a result for the currently displayed credential input may
  advance or persist the flow
- `hltb-data`: a superseded candidate search cannot publish over its replacement
- `app-ui`: the History current day is expanded by default on the day it becomes current, not
  only on first composition

## Impact

| Path | Change |
|---|---|
| `ui/onboarding/OnboardingViewModel.kt` | request identity for resolve and verify (`:183-230`) |
| `ui/onboarding/OnboardingScreen.kt` | control enablement during an operation (`:195-239`) |
| `ui/library/LibraryViewModel.kt` | `changeMatch` job identity; cancellation rethrown (`:448-466`) |
| `ui/history/HistoryScreen.kt` | auto-expand tracks a date, not a flag (`:74-96`) |

**Depends on `auditfix-spec-truth`** only loosely: that change rewrites
`onboarding-credentials`' credential-editing requirement (#102), and this change adds a
requirement to the same capability. Landing after it avoids two changes editing one spec
file. No code dependency.

**Independent of the correctness core.** Nothing here touches the session ledger, the
gamification engine, or any worker, so this change can proceed in parallel with
`auditfix-session-ledger-integrity` and `auditfix-background-work-contracts` once
`auditfix-spec-truth` is in.

**Shares no file with `auditfix-settings-boundary` or `auditfix-collections-editor`,** the
other two UI changes — those touch `SettingsViewModel`/empty-state copy and the collections
editor respectively. All three add requirements to `app-ui`, so expect a textual merge at
sync time in that one spec file; the requirement names are distinct.

**Not addressed here**: the `refreshSelection`/`selectionLookupJob` path in the same
`LibraryViewModel`, which cancels correctly and writes each result to Room as it arrives, so
a cancelled run leaves consistent data. It was checked and needs no change.

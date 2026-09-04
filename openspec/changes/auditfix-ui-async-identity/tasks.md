## 1. Prerequisites

- [ ] 1.1 Read `LibraryViewModel.previewPickerManualLink()` (`:490-517`) and its comment before writing any of the three fixes. It is the correct instance of this idiom already in the codebase and the pattern the three sites adopt (design.md Decision 1). Verified by the identity comparison in each new site being recognisably the same shape
- [ ] 1.2 Confirm `auditfix-spec-truth` has landed, so this change's `onboarding-credentials` addition is not a second edit to a capability that change is already rewriting (#102)

## 2. Onboarding credential identity (#122, severity/high — do first)

- [ ] 2.1 Tie `resolveSteamId()` (`OnboardingViewModel.kt:190-202`) to the input it was started for, and discard the result if the displayed input has since changed. **Do not rely on `onSteamIdInputChange()`'s visible reset** — it does not stop a coroutine that already captured the earlier value. Verified by the test in 2.4
- [ ] 2.2 Cancel the in-flight resolution on edit as well as guarding the publish, so an abandoned request stops spending a Steam call. The guard, not the cancellation, is what the requirement rests on (design.md Decision 3). Verified by both mechanisms present
- [ ] 2.3 Apply the same identity treatment to verification in `finish()` (`:213-230`) so a verification whose captured SteamID or API key is no longer displayed cannot reach `persist()`. **Keep the controls live** — do not lock the field and Back for the duration of a network round trip during first-run setup (design.md Decision 3)
- [ ] 2.4 Test: resolve input A, edit to B while in flight, let A succeed — assert the flow does not offer to save and that `CredentialsRepository.save()` is never called with A. Use a controllable suspension point rather than timing. **Assert on what reaches the credential store**, which is deterministic even when the interleaving is not
- [ ] 2.5 Test: the same scenario **on first configuration with no stored SteamID**, where no account-change check exists to catch it — the specific path the audit identified as unprotected
- [ ] 2.6 Test: editing the field or navigating back during `VerifyState.Verifying` prevents that verification from persisting
- [ ] 2.7 Test: a resolution or verification for the still-displayed input advances the flow normally, and retry-after-failure still persists on success — the ordinary paths the guard must not break
- [ ] 2.8 Check `OnboardingScreen.kt:195-239` for any affordance that becomes reachable in a state the ViewModel now refuses, so the screen cannot offer a Finish that silently does nothing

## 3. HLTB picker search identity (#126)

- [ ] 3.1 Rethrow `CancellationException` in `changeMatch()` (`:452`) so a cancelled search does no work at all. `runCatching` currently absorbs it because it is a `Throwable`. Verified by a cancelled search reaching no update block
- [ ] 3.2 Add a job-identity check at the publish point — `pickerJobs[appId] === job` — extending the comparison already used in `invokeOnCompletion` (`:464`) two lines below. The existing `states[appId] ?: return@update states` guard checks that the entry exists, not that it is this job's (design.md Decision 2). Verified by the test in 3.5
- [ ] 3.3 Confirm the `invokeOnCompletion` bookkeeping at `:463-465` still behaves once cancellation propagates rather than being swallowed. Verified by the job map not leaking entries after a cancelled search
- [ ] 3.4 Test: the owning search still publishes its candidates, and a genuine failure (unreachable source) still shows as failed — the guard must not hide real errors
- [ ] 3.5 Test: dismiss the picker mid-search then immediately reopen for the same game; assert the second search's loading state survives and no `failed = true` appears from the first — the regression test for #126
- [ ] 3.6 Test: searches in flight for two different games cannot publish into each other's state

## 4. History expand-today anchor (#127)

- [ ] 4.1 Replace `autoExpandedToday: Boolean` (`HistoryScreen.kt:78`) with the date that was auto-expanded, and expand when `state.today` differs from it (design.md Decision 4). Verified by the test in 4.3
- [ ] 4.2 Key on the date **value**, not on the effect firing. `state.today` re-emits on every history data change, and expanding on every emission would reopen a day the player deliberately collapsed. Verified by the test in 4.4
- [ ] 4.3 Test: advancing the local date past midnight with the screen composed expands the newly current day — the regression test for #127, and for the midnight hardening that routed this anchor through `CurrentDateProvider`
- [ ] 4.4 Test: collapsing the current day and then emitting further history data for it leaves it collapsed
- [ ] 4.5 Test: a new current day being auto-expanded does not force-collapse or force-expand the day that was previously current, and earlier days stay as the player left them
- [ ] 4.6 Test: first open still expands the current day and collapses all earlier days — `app-ui/spec.md:1920-1922`, unchanged

## 5. Consistency and close out

- [ ] 5.1 Give all three sites the same short comment naming the rule and pointing at the others, so the next author copies the guarded version rather than the unguarded one. **Do not extract a shared helper** — the three identity tokens differ and a comparator-plus-publisher abstraction reads worse than the two lines it replaces (design.md Decision 5)
- [ ] 5.2 Confirm `refreshSelection`/`selectionLookupJob` in the same `LibraryViewModel` still needs no change: it cancels correctly and writes each result to Room as it arrives, so a cancelled run leaves consistent data. Verified by re-reading it against the new rule
- [ ] 5.3 `openspec validate --strict auditfix-ui-async-identity` passes
- [ ] 5.4 `./gradlew :app:testDebugUnitTest` passes
- [ ] 5.5 On a device: enter a vanity name, start resolution, edit the field mid-flight, and confirm the screen does not offer Finish for the abandoned input
- [ ] 5.6 Sync the deltas into `openspec/specs/` via the archive workflow, not by hand
- [ ] 5.7 Close #122, #126, #127

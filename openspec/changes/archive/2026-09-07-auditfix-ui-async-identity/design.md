## Context

See `proposal.md` — Why. Three instances of one bug: a result published without checking
whether it still answers the current question.

The reason this is a design document at all, for what could look like three small patches, is
that the codebase has *already solved this*, correctly, once —
`LibraryViewModel.previewPickerManualLink()` at `:490-517`, thirty lines below the broken
`changeMatch()` in the same class:

```kotlin
pickerManualLinkStates.update { map ->
    val current = map[appId] ?: return@update map
    val next = if (current.input.trim() == input) resolved(current) else current.copy(loading = false)
    map + (appId to next)
}
```

Its comment states the rule: "a stale preview must never overwrite newer user input."
`changeMatch()` twenty lines earlier checks only `states[appId] ?: return@update states` —
that the entry *exists*, not that it is *ours*. Same file, same class, same hazard, one
guarded and one not. So the design question is not "what should the guard be" but "what
should the guard be *called*, and how does it stop drifting apart again".

## Goals / Non-Goals

**Goals:**

- No stale result publishes over the state that superseded it, at any of the three sites.
- The idiom is recognisable as one idiom, so the next author copies it rather than reinventing
  the guarded and unguarded halves again.
- `CancellationException` is never reported as a failure.

**Non-Goals:**

- A general-purpose async framework or a new base ViewModel. Three call sites do not justify
  it, and the two-line comparison the existing code uses is clearer at the call site than an
  abstraction would be.
- Touching `refreshSelection`/`selectionLookupJob` in the same `LibraryViewModel`. It cancels
  correctly and writes each result to Room as it arrives, so a cancelled run leaves consistent
  data. Checked; no change needed.
- Any change to what onboarding persists, or to the account-change confirmation path. Only
  *which* result is allowed to reach `persist()` changes.

## Decisions

### Decision 1: Identity comparison at the publish point, not a cancellation-only fix

The obvious minimal fix for #126 is to rethrow `CancellationException` so a cancelled job
never reaches the update block. Necessary, but not sufficient — and understanding why sets
the shape for all three sites.

Cancellation is not the only way a job becomes stale. In `changeMatch` the replacement path
happens to cancel its predecessor, so rethrowing would cover the reported scenario. In
`resolveSteamId` (#122) **nothing is cancelled at all** — `onSteamIdInputChange` only resets
visible state — so a cancellation fix would do nothing there. And a job that completes
normally a microsecond before its successor starts is stale without ever being cancelled.

So the rule is: compare identity at the publish point. Cancellation-rethrow is an additional
guard that stops a cancelled job doing work at all; both go in, and neither is presented as
the whole answer.

### Decision 2: Compare the *thing that identifies the request*, per site

There are two workable identity tokens and the right one differs by site:

| Site | Token | Why |
|---|---|---|
| `changeMatch` | the `Job` itself | `pickerJobs[appId] === job` is already the idiom used in `invokeOnCompletion` two lines below, and the request has no user-visible input to compare |
| `resolveSteamId` / `finish` | the submitted input string | matches `previewPickerManualLink`, and the requirement is genuinely about what the screen *displays*, not about which coroutine ran |
| History auto-expand | the date | the state and the token are the same value; see Decision 4 |

Using the job for the picker is deliberate: `changeMatch` already keeps `pickerJobs[appId]`
and already compares by reference identity in `invokeOnCompletion`. Extending that same
comparison to the publish point is a smaller and more obviously correct change than
threading the search name through.

### Decision 3: Onboarding gets identity, not disabled controls

The audit offered "request identity/cancellation (or controls locked for the full
operation)". Identity chosen.

Locking the SteamID field and Back for the whole of resolution and verification would work,
and is arguably simpler. Rejected because verification is a network round trip against
Steam — on a slow connection that is a screen the player cannot edit or back out of, during
first-run setup, which is the worst possible moment to trap someone. The current code
deliberately leaves the controls live; the defect is that it does not then honour what they
did.

Identity keeps the responsive behaviour and makes it correct. Cancellation is added too — an
edit should stop the in-flight request, not merely orphan it — but the published-result guard
is what the requirement rests on, because cancellation is best-effort and the guard is not.

### Decision 4: Track the auto-expanded date, and keep manual collapse working

`autoExpandedToday: Boolean` becomes `autoExpandedDate: String?`. The effect expands when
`state.today != autoExpandedDate`, then records it.

This preserves the property the current code gets right and which a naive "expand whenever
`state.today` emits" would break: the player can collapse the current day and it stays
collapsed. `state.today` re-emits on every history data change, so keying on the value rather
than on the emission is what separates "a new day arrived" from "new data arrived for today".

The audit's phrasing — "while still allowing the user to manually collapse the current day
without unrelated emissions reopening it" — names exactly this, and it is the part most
likely to be lost in a hasty fix.

### Decision 5: Name the idiom in a comment at each site, do not extract a helper

Each site gets the same short comment naming the rule, pointing at the others. Not a shared
utility.

The three tokens differ (job reference, input string, date) and the three publish points have
different shapes, so a helper would take a comparator and a publisher and read worse than the
two lines it replaced. What actually failed here was not the absence of a helper — it was
that the correct instance carried its reasoning in a comment thirty lines from an incorrect
instance that did not. Consistent naming is the cheap fix for that, and it matches
`CLAUDE.md`'s convention of matching the surrounding code's idiom rather than importing a new
one.

## Risks / Trade-offs

**An over-strict guard drops results the player is waiting for** → Every spec has an explicit
scenario for the ordinary path — "The displayed input's own result is honoured", "The owning
search publishes normally", "First open is unchanged" — so the happy path is a reviewable
obligation rather than an assumption. Tasks 2.5, 3.4, and 4.4 test it directly.

**Rethrowing cancellation changes control flow in `changeMatch`** → `runCatching` currently
absorbs it, so nothing downstream expects a `CancellationException` to propagate. The job is
launched in `viewModelScope`, where cancellation is the normal termination path, so
propagating is the correct behaviour rather than a new hazard. Task 3.3 confirms no
`invokeOnCompletion` bookkeeping regresses.

**#122 is hard to test deterministically** → It is a race between an edit and a network
result. Task 2.4 requires a controllable suspension point rather than a timing-dependent
test, and the assertion is on what reaches the credential store, which is deterministic even
when the interleaving is not.

**Three changes will touch `app-ui`'s spec at sync time** → This one, plus
`auditfix-settings-boundary` and `auditfix-collections-editor`. All three add distinctly named
requirements, so this is a textual merge rather than a semantic conflict. Worth expecting
rather than being surprised by.

## Migration Plan

No data or schema change. Order within the change is by severity:

1. #122 first — it is the only one that writes wrong data to persistent storage.
2. #126 — user-visible false failure.
3. #127 — cosmetic, though it defeats an explicit earlier hardening effort.

Each is independently revertable and shares no file with the others.

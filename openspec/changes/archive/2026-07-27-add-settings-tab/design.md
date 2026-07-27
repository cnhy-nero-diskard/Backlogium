## Context

Home carries three administration blocks (Steam account, history import, sync row) among five
progress blocks, and `RuleConfig` — every gamification constant the engine reads — has a
persistence path with no writer: `SettingsRepository.setRuleConfig()` is called from nowhere.

The constraint that shapes most of this design is that **`GamificationUpdater.recompute()` is
stateless**. It rebuilds total XP, every stored day's `questMet`, and both streaks from raw
inputs under whatever config it is handed. Nothing accumulates. So exposing rule editing is not
"add a form that writes DataStore" — it is exposing a control that rewrites the player's entire
recorded history, and the design has to make that safe and legible rather than merely possible.

Two existing decisions constrain the solution. `SettingsRepository` is deliberately storage-only
(the `establish-cloud-seam` change made repositories a boundary over storage, not a place for
domain logic). And `gamification` is a pure module with no I/O — the streak bug is not in the
engine, which correctly returns the longest streak for the days it is given, but in how the
result is persisted.

## Goals / Non-Goals

**Goals:**
- A Settings destination that absorbs the account, sync, and data controls from Home.
- Make `RuleConfig` editable, with the retroactive consequence stated concretely before it lands.
- Rule changes visible immediately, not at the next 15-minute poll.
- `longestStreak` protected from being erased by a recompute.
- A shell-level sync indicator that reflects real sync activity.

**Non-Goals:**
- Theme settings (the app specifies one hand-authored scheme, deliberately).
- Sync-cadence configuration.
- Recovering a `longestStreak` a prior recompute already destroyed — nothing records it.
- Owning the library/history sort keys `enhance-library` will add to `SettingsDataStore`.

## Decisions

- **A `UpdateRuleConfigUseCase` in `domain/` owns write-then-recompute; the repository stays
  storage-only.** This is exactly the shape `PlaytimeBackfillUseCase` already has — it mutates
  state and then calls `gamificationUpdater.recompute(time.today(), config)` at
  `PlaytimeBackfillUseCase.kt:54` and `:69`. Rule editing is the same operation with a different
  mutation.
  *Why:* it keeps `SettingsRepository` a pure storage seam, which was a deliberate choice, and
  reuses a pattern already in the codebase rather than inventing a third one.
  *Alternative rejected:* injecting `GamificationUpdater` into `SettingsRepository` — undoes the
  cloud-seam boundary and makes a storage class depend on the domain layer.
  *Alternative rejected:* the view model calling `SettingsRepository` then `GamificationUpdater`
  in sequence — puts a two-step invariant in the UI layer, where a future caller can perform half
  of it. `PlaytimeBackfillUseCase` exists precisely so that cannot happen.

- **Split `recompute()` into `compute()` and `persist()`.** The confirmation dialog has to state
  the *concrete* effect ("your longest streak drops from 40 to 6"), which means running the real
  computation without writing it. `GamificationUpdater` gains `compute(today, config):
  GamificationResult` holding the XP state, per-day quest results, and streaks; `persist(result)`
  writes them. `recompute()` becomes `persist(compute(...))` so existing callers are unchanged.
  *Why:* the alternative — approximating the effect in the dialog — produces a warning that can
  disagree with what actually happens, which is worse than no warning.
  *Cost accepted:* preview runs the DAO reads twice (once for the dialog, once on confirm). These
  are local Room reads over a single player's data; correctness is worth the duplicate pass.

- **`longestStreak` becomes a high-water mark in `persist()`, not in the engine.** The engine's
  `streak()` keeps returning the longest for the days supplied; `persist()` writes
  `maxOf(stored.longestStreak, computed.longestStreak)`.
  *Why:* `gamification/spec.md` states the module performs no persistence, and "highest ever
  recorded" is inherently a fact about stored history, not about a list of days. Putting it in
  the engine would require passing the previous value in, making a pure function stateful in
  everything but name.
  *Consequence accepted:* "longest streak" now means *longest ever achieved* rather than *longest
  achievable under current rules*, so the two streak numbers can be inconsistent with each other
  under a config the player never actually played under. That is the correct trade: a record is a
  historical fact, and a settings toggle should not be able to delete one.

- **The confirmation dialog reports the *protected* longest streak, not the raw computed one.**
  Since `persist()` will floor it at the stored value, previewing the unprotected drop would warn
  about a loss that is not going to happen.

- **`syncInProgress` must treat periodic and one-time work differently.** The obvious
  implementation — union the two unique-work flows using the existing
  `state == ENQUEUED || state == RUNNING` predicate — is wrong. A `PeriodicWorkRequest` sits in
  `ENQUEUED` for the entire 15 minutes *between* runs, so the indicator would be permanently on.
  The periodic flow must match `RUNNING` only; the one-time flow keeps `ENQUEUED || RUNNING`
  (an expedited manual sync should show feedback from the moment it is enqueued).
  *Why called out:* this is the single most likely way to ship this change with a header that
  spins forever.

- **A minimum-visible latch on the indicator, in the flow, not the composable.** `syncInProgress`
  gains an operator that holds `true` for ~700ms after it would otherwise fall to `false`.
  *Why in the flow:* the composable then stays a dumb renderer, and the latch is unit-testable
  with a test dispatcher rather than requiring a Compose test.

- **Reduced motion degrades to a static cue, not to nothing.** `enhance-now-playing`'s design
  already commits to respecting motion-sensitivity settings; matching it here avoids two
  different answers to the same question in one shell. The animated glyph becomes a static one.
  *Note:* whichever change lands first should introduce the shared "reduced motion" helper and
  the other should use it.

- **Settings is reachable while unconfigured and offers onboarding.** Unlike the profile header,
  which hides entirely, a tab in the navigation bar cannot hide without the bar reflowing. So
  Settings renders in an unconfigured state whose account section is a "connect your Steam
  account" action into the existing onboarding flow, and whose rule controls are still editable
  (they are local preferences and need no credentials).

- **Advanced controls validate before they reach the engine.** `Gamification` has degenerate-input
  guards (`levelBase` of 0 would divide by zero in `levelFor`), but a guard is a crash-avoidance
  measure, not a UX. The advanced fields reject non-positive values at input.

## Risks / Trade-offs

- **Header spins forever from the periodic `ENQUEUED` state** → the dedicated decision above;
  worth an explicit test that asserts the indicator is false while periodic work is merely
  scheduled.
- **A rule change on a large library makes the confirm dialog slow to appear** → `compute()` is
  the same work a sync already does every 15 minutes, but it now sits in front of a user waiting
  on a dialog. Show the confirm affordance in a loading state rather than blocking the tap.
- **Two streak numbers that disagree** → accepted and specified; the Home streak card may want a
  brief affordance explaining "longest ever" if this proves confusing in use.
- **Home loses its sync affordance** → mitigated by the error card's inline Retry, which is the
  only case where an immediate manual sync actually matters.
- **`enhance-library` also touches `SettingsDataStore`, `SyncScheduler`, and
  `GamificationUpdater`** → this change edits them structurally and should land first; the sort
  keys are additive and rebase cleanly onto it, not the reverse.

## Migration Plan

**No database migration.** Every rule already has a DataStore key with a `RuleConfig` default, and
`longestStreak` is an existing column on `PlayerProfile`.

The only migration is semantic: on installs where a recompute has already lowered `longestStreak`
below its true historical maximum, the stored value is simply wrong and stays wrong. The
high-water rule applies forward from first run of the new build. There is no session-level history
of past streak lengths to recover from, and inventing one to fix a value nobody has complained
about is not worth a schema change.

Rollback is clean: reverting the build leaves DataStore keys that the old code reads through the
same `RuleConfig` defaults path, and a `longestStreak` that the old code will simply resume
overwriting.

## Open Questions

- Does the settings screen expose a "restore defaults" action? It is one confirmation away from
  being a full progress reset, so it may deserve to be absent rather than guarded.
- Should the per-tier achievement XP awards be individually editable, or collapsed to a single
  multiplier? Five separate integer fields is a lot of surface for a knob most players will never
  touch.
- Does the sync indicator also express *failure*, or is that solely the Home error card's job?
  Keeping it out avoids duplicating an error in two places, but the shell is where a persistent
  "something is wrong" dot would actually be seen.

## Context

`GamificationUpdater` is the single place derived values are written. `compute()` builds a
`GamificationResult` from Room; `persist()` writes it back. The two are split so the settings
confirmation dialog can evaluate a candidate `RuleConfig` and state its concrete before/after
without committing it — a previewed result and an applied one can never disagree.

`persist()` already holds both sides of every transition:

```kotlin
val profile = playerProfileDao.get() ?: PlayerProfile()   // old
playerProfileDao.upsert(profile.copy(
    level = result.xpState.level,                          // new
    currentStreak = result.currentStreak,
    ...
))
```

Nothing reads that diff. Every celebration in the app is instead derived from position:
`isStreakMilestone(streakDays)` is a pure function of the current streak, so it answers "celebrate"
identically on the first and fiftieth composition of day 7.

Four callers reach `persist`, three of them through `recompute` and one — `BackupMergeEngine` —
directly. They mean entirely different things, and the difference is invisible at the call site
today.

## Goals / Non-Goals

**Goals:**

- A transition is observable exactly once, survives process death, and cannot be re-shown.
- A write of derived values cannot happen without declaring whether its changes were earned.
- The detector is a pure function, unit-testable on the JVM with no Room and no Android.
- No Room migration.
- One real consumer, so the spec is reviewable against something that renders.

**Non-Goals:**

- Haptics. `add-haptic-feedback` consumes this stream; nothing here vibrates.
- Notifications. Events are presented when the app is in front of the player, not pushed.
- An event history or audit log. Nothing needs to ask what happened last Tuesday.
- A "personal record" event for beating the longest streak. `longestStreak` is a high-water mark
  with its own preservation rule; giving it an event is a separate question.
- Any XP, level, streak, or quest computation. The detector reads what the engine produced.

## Decisions

### 1. Provenance is a required parameter on `persist`, not `recompute`

`BackupMergeEngine` calls `persist` directly. Putting the argument on `recompute` would leave the
one caller most in need of it — a restore, which can move every derived value arbitrarily — able to
write without declaring anything.

It is required rather than defaulted because a default is a silent answer. `RecomputeSource.SYNC`
as a default would make every future caller celebratory-by-omission; `NOT_EARNED` as a default
would make the real one silently wrong the day someone adds a second sync path. A required
parameter converts "did you think about this?" into a compile error at the one chokepoint all
derived-value writes pass through.

*Alternative considered:* infer provenance from the caller via a stack inspection or an injected
marker. Rejected — invisible at the call site, untestable, and it fails exactly when a new caller
appears, which is the case the mechanism exists to catch.

*Alternative considered:* a separate `persistEarned` / `persistSilently` pair. Rejected — two
methods that must stay behaviourally identical except for one branch, and nothing stops a new
caller picking the wrong one as readily as it would pick the wrong enum value, with no exhaustive
`when` to update.

### 2. Events are emitted from `persist`, never from `compute`

`compute` is called speculatively. The settings dialog runs it against a candidate config and
throws the result away. Emitting there would fire a level-up every time a player opened the rules
dialog with a generous `xpPerMinute` typed in and then cancelled.

This is not a defensive nicety — it is the reason the preview/commit split is load-bearing for
more than the dialog's copy. The rule stated plainly: **speculative computation must have no
observable effect.**

### 3. High-water marks in DataStore, not a Room events table

Four preference keys carry the whole mechanism:

```
lastCelebratedLevel            Int      monotonic
lastCelebratedStreakMilestone  Int      monotonic
lastQuestCelebratedDate        String   ISO date
lastStreakBrokenDate           String   ISO date
```

Pending state is the *difference* between a mark and the stored profile. There is no queue to
drain, no row to delete, and no schema version. Consuming an event means advancing its mark.

This gives three properties for free:

- **Idempotence.** Re-deriving after a process restart yields the same answer until the mark moves.
- **Collapse.** A mark of 4 against a stored level of 7 is one `LevelUp(4, 7)`, not three events.
  The mark records where the player is, not every threshold crossed getting there — and `from`
  comes out of the mark itself.
- **No migration.** `SettingsDataStore` gains four keys, which is the documented way settings grow.

A Room table would add ordering and history. Nothing consumes either. It would also add a schema
version to a database that currently has no reason to change.

*A streak break is keyed by date, not by length.* A mark holding "last break was at length 14"
would suppress a later break at length 5. Breaks are at most one per day, so the date is the
natural identity.

### 4. Non-earned sources reseed the marks silently

Provenance does two jobs, and the second is easy to miss. A non-`SYNC` write must not celebrate —
but it must also not leave a stale baseline behind.

Clearing a playtime backfill drops the level from 7 to 4. If the marks were merely left alone at 7,
the player would then climb back to 7 through genuine play and receive nothing, because the mark
already claims 7 was celebrated. Worse, a restore that raises the level to 30 and leaves the mark
at 4 would fire `LevelUp(4, 30)` on the *next* sync, deferring the phantom celebration rather than
preventing it.

So: **`SYNC` emits events and advances the marks to the values it emitted. Every other source emits
nothing and sets the marks to the new values outright** — including downward. Provenance selects
between "report the transition" and "redefine the baseline."

### 5. First-ever persist seeds, never celebrates

A fresh install syncs a mature Steam library and lands at level 30 with the marks at their
defaults. Diffing naively yields `LevelUp(0, 30)` on first launch — a celebration for having
installed the app.

When no `PlayerProfile` row exists yet, `persist` seeds the marks from the computed values and
emits nothing, regardless of source. Onboarding is not an achievement.

### 6. The detector is a pure function in the app module's `domain/`

`ProgressEventDetector` takes plain values — old level, new level, marks, source, today — and
returns a list of events. No Room types, no Android, no injection. It is the piece worth testing
exhaustively, and it tests as a table of transitions.

It lives in `domain/` rather than `:gamification` for the same reason `StreakMilestone.kt` does:
`Gamification.kt` is a stub slated for wholesale replacement, and new code added there risks being
dropped when that lands. It also has no claim to be in the engine — it authors no derived values,
it observes changes in values the engine already authored. The invariant that the on-device engine
is the sole author of derived values is untouched.

### 7. Acknowledge after presenting, not before

A consumer renders the event, then advances the mark. If the process dies between the two, the
event shows once more on next launch. The inverse ordering trades that for the event being lost
entirely.

A duplicate celebration is a mild annoyance; a silently swallowed one is a bug the player cannot
report because they never saw it. Prefer the annoyance.

### 8. Simultaneous events are a list with a documented priority

One sync can level a player up, satisfy today's quest, and land a streak milestone. The stream
carries all of them; the consumer decides. The documented order is
`LevelUp > StreakMilestone > QuestMet > StreakBroken`, so a surface that can only show one shows
the largest. `StreakBroken` cannot co-occur with `QuestMet` on the same day by construction.

### 9. `StreakMilestone.kt` keeps the rule, loses the trigger

`STREAK_MILESTONE_INTERVAL_DAYS` and `isStreakMilestone` remain as the interval rule and stay where
they are. What changes is that the celebration is no longer "the current streak is a multiple of
7" but "the streak crossed a multiple of 7 that has not been celebrated." The Home animation moves
onto the event, gaining the memory it never had.

## Risks / Trade-offs

- **Every `persist` call site breaks.** → Intended, and the mechanism's entire value. Four call
  sites in four files, each a one-argument change, each forcing a deliberate reading of what that
  caller means.

- **An event can be presented long after it occurred.** A player who levels up during a background
  sync and opens the app the next morning sees the celebration then. → Accepted. The alternative is
  a notification, which is out of scope, or silently discarding events past a staleness horizon,
  which loses the moment entirely. Presenting late is honest: the transition did happen, and the
  overlay states what changed rather than implying it just occurred.

- **Marks and the profile can disagree if a write partially fails.** → The marks are advanced only
  after a successful `upsert`, and every event is re-derivable from the pair. A disagreement
  resolves to "emit again," never to "emit something that did not happen."

- **`SettingsDataStore` accretes non-settings state.** It already holds `LIVE_SESSION_APP_ID` and
  `NOTIFICATION_PERMISSION_REQUESTED`, so this is the established pattern rather than a new
  precedent — but the file is becoming two things. → Group the four keys and document them as
  presentation state, and treat a split as a separate cleanup if it grows further.

- **The overlay is the only consumer, so most of the vocabulary ships unexercised.** `LevelUp` and
  `QuestMet` will be defined and detected but not rendered until `add-haptic-feedback`. → Accepted
  deliberately: defining the full vocabulary once, against the engine's actual transitions, is
  cheaper than growing it one event per follow-up change, and the detector's unit tests exercise
  every case regardless of what renders.

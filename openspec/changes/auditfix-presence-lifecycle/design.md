# Design

## Context

Two constraints, both platform-imposed, both currently unhandled:

1. **Background FGS start restriction** (Android 12+). A backgrounded process may not start
   a foreground service except under specific exemptions. A periodic WorkManager execution is
   not generally one.
2. **`dataSync` runtime budget** (Android 15+). A cumulative per-24-hour cap, after which
   `onTimeout` fires and further `dataSync` starts may be refused until the app is brought
   to the foreground.

The code handles neither, and its recovery plan for (2) is a call that violates (1).

## Decision 0: Establish the facts before choosing a mechanism

This is the first task, not a preamble. The options below all depend on current platform
behaviour, and the existing comment is evidence that reasoning from memory about Android
service restrictions produces confident wrong answers.

What needs establishing on a real device, per API level the app supports:

- Does `startForegroundService` from a periodic worker actually throw, or does an exemption
  apply that has not been noticed? (WorkManager expedited work has different standing than
  ordinary periodic work.)
- Is `dataSync` the correct `foregroundServiceType` for this at all? The service polls a
  network API and shows an ongoing notification of what the user is playing. `dataSync` is a
  plausible reading; it is also the type carrying the harshest Android 15 budget.
- After `onTimeout`, what precisely is refused, and for how long?

**Do not write the fix before answering these.** The audit's finding is that the code
encodes an assumption about platform behaviour that is wrong; replacing it with a different
assumption is not progress.

## Decision 1: A refused start must never fail the sync

Independent of everything above, and implementable immediately.

`SteamSyncWorker.kt:88-90` is unguarded, sitting before `getOwnedGames`. The file's own
comment at `:84-87` explains that presence is deliberately ahead of the library poll so that
"neither a private library nor a failure later in this run may cost the player detection" —
the intent is that presence is *more* protected, not that it can sink the run.

Every other best-effort call in this worker is already wrapped: `getPlayerSummaries` in
`runCatching` (`:80`), genre enrichment (`:192`), the auto-snapshot write (`:251`),
achievement sync in try/catch with a comment stating a failure "must never fail an
otherwise-successful poll" (`:246-248`). The presence start is the one exception, and it
looks like an oversight rather than a decision.

**Guard it, and record the outcome** rather than discarding it. A bare `runCatching` would
fix the sync failure and make the monitoring failure permanently invisible — which is close
to the current situation, where nobody can tell that monitoring stopped working.

`app-diagnostics` already has `PresenceDecision` rows whose stated purpose is to record
"which condition produced that outcome". A refused start is such a condition.

## Decision 2: How monitoring starts, given the restriction

Options, to be chosen after Decision 0:

| Option | Mechanism | Cost |
|---|---|---|
| **A.** Expedited work | let expedited WorkManager execution carry the start | expedited quota is limited; `syncNow` already uses `RUN_AS_NON_EXPEDITED_WORK_REQUEST` fallback, so it degrades to ordinary work exactly when the quota is gone |
| **B.** Do the polling in the worker | no service; a worker polls presence directly | no ongoing notification, no sub-15-minute resolution — a session shorter than the interval is invisible |
| **C.** Start only from the foreground | monitoring engages when the app is opened, and after that survives until the platform stops it | unattended detection is lost, which is the feature's main purpose |
| **D.** Change the service type | a type whose start rules and budget fit better | depends entirely on Decision 0; may not exist for this use case |

**No recommendation here on purpose.** Each option trades away something real, and the
choice turns on the Decision 0 findings. What this design does commit to is that **whichever
option is chosen, the spec states what unattended monitoring actually does** — including "it
does not resume until you open the app", if that is the truth. A documented limitation is
usable; a false promise of self-healing is not.

**Rejected outright: leaving the current mechanism with a wrapped exception.** That is
Decision 1 alone, and it converts a loud failure into a silent one. It is a necessary first
step, not the fix.

## Decision 3: Make `onTimeout` honest

Regardless of Decision 2, the comment at `PresenceService.kt:100-106` must change. It is
wrong in two specific ways worth fixing precisely, because a future reader will otherwise
inherit the same false model:

- "~6h of **continuous** runtime" → cumulative across a rolling 24-hour window. This is the
  more important correction: continuous implies only marathon sessions are affected, which is
  why the comment concludes "a session that long is the exception, not the norm." Cumulative
  means ordinary daily use reaches it.
- "restarts the service [...] so stopping cleanly here self-heals" → delete, or replace with
  what actually happens per Decision 2.

`stopSelf(startId)` in `onTimeout` is itself correct — stopping cleanly is the required
response. The defect is the surrounding claim about what happens next.

**Behaviour to add**: record that the budget was reached, so a user finding monitoring
stopped can see why. If Decision 2 lands on an option where monitoring cannot resume
unattended, this is also the trigger for telling them.

## Decision 4: Telling the user

If monitoring cannot resume without foreground interaction, that must be visible. Not as an
error — nothing is broken — but as state.

The existing `StreakBrokenOverlay` shows the project already accepts surfacing a
consequential background event to the user. Presence monitoring having stopped is
consequential in the same way: the user believes playtime is being tracked at 30-second
resolution and it is not.

Minimum: the live-status surface distinguishes "monitoring" from "monitoring unavailable
until you reopen the app". Deciding where that lives is UI work that follows Decision 2.

## Verification strategy

**Unit tests cannot verify any of this.** Stated plainly because this change will otherwise
be closed on a green suite.

What can be automated: that a start failure does not fail the sync (inject a throwing
starter, assert `Result.success()`); that the diagnostic row is written; that `onTimeout`
stops the service.

What must be verified on-device via `run-on-device`:

- backgrounded periodic sync with monitoring enabled — does the start succeed, and does the
  sync survive if it does not
- reach the `dataSync` budget and observe what `onTimeout` does and what a subsequent start
  does
- confirm behaviour on the oldest and newest API levels the app supports, since the two
  restrictions arrived in different versions

Record the observations in design.md as findings. They are the evidence for the spec claims,
and without them this change is one more confident assumption about Android.

## What this change deliberately does not do

- Does not redesign live monitoring's polling architecture.
- Does not change the 30-second poll interval or the notification content.
- Does not remove live monitoring as a feature, even if Decision 2 lands on reduced
  unattended capability.
- Does not choose between Decision 2's options before Decision 0's investigation.

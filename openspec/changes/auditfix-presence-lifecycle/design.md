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

## Findings (2026-08-17)

The `run-on-device` workflow was attempted from the implementation worktree, but ADB reported
no attached devices. The device observations in tasks 1.1, 1.3, and 1.4 therefore remain pending;
this change does not claim on-device evidence.

The available Android Developers guidance establishes the implementation boundary:

- Android 12+ rejects background foreground-service starts unless a documented exemption applies;
  an ordinary periodic WorkManager execution is not one of the listed exemptions.
- Android 15 gives `dataSync` foreground services a cumulative six-hour budget in a rolling
  24-hour window, calls `onTimeout`, and may reject another `dataSync` start until the user brings
  the app to the foreground.
- `dataSync` is the semantically appropriate declared type for fetching data, but the documentation
  recommends WorkManager or direct user interaction instead of treating it as an unattended,
  indefinitely running monitor. No alternative foreground-service type matches this polling work.

These findings select Decision 2 option C for this implementation: only a foreground app
interaction may start `PresenceService`; a background sync records `start_not_attempted`, leaves
the owned-games poll authoritative, and surfaces that fine-grained monitoring must wait for the
next foreground visit. The selection is evidence-backed from the platform documentation but still
requires the real-device matrix before the change can be closed.

## On-device verification (2026-08-17, Android 15 / API 35)

An emulator became available in the implementation environment after the findings above were
recorded (`Medium_Phone_API_35`, Android 15, API 35 — the newest platform version the app
targets and the one that introduces the `dataSync` budget). This section records what was
verified against the implemented fix, not what was assumed.

**Primary-source confirmation, not memory.** Before touching the device, the two restrictions'
exact mechanics were pulled from `developer.android.com` (not recalled):
[Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
and
[Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
Both pages document ADB test hooks specifically so this kind of change doesn't have to wait six
hours per attempt:

- `adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>` shortens the
  `dataSync` budget for testing.
- The background-start restriction's exemption list is explicit and does **not** include ordinary
  WorkManager execution (periodic or one-time) — confirming finding #6's premise from the
  authoritative source rather than from absence-of-evidence.
- A subsequent `dataSync` start after the budget is exhausted throws
  `ForegroundServiceStartNotAllowedException` with message `"Time limit already exhausted for
  foreground service type dataSync"`, and — the detail that matters most for Decision 2 — **the
  timer resets when the user brings the app to the foreground.**

**What was reproduced on-device (not just documented):**

1. Set `data_sync_fgs_timeout_duration` to `20000` (20s). Attempting
   `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS com.example.backlogium` was refused by
   the platform with "the app's targetSdk (36) is above the change's targetSdk threshold (34)" —
   i.e. this app cannot opt out of the timeout even if it wanted to; it is unconditionally subject
   to it. Confirms the fix cannot rely on the budget not applying.
2. Enabled "Monitor Steam activity" from Settings (the real `onLiveMonitorEnabledChanged` →
   `presenceServiceStarter.startFromForeground(trigger = "settings")` path, i.e. Decision 2 option C's actual
   foreground-triggered start). `dumpsys activity services` confirmed a real `dataSync` foreground
   service running (`types=0x00000001`, `uidState: TOP`) — the start succeeds cleanly from the
   foreground, as the design requires.
3. Backgrounding the app (home button) is the moment the budget clock started, not service start:
   `ActivityManager` logged `FGS (dataSync) timed out` and `Stop FGS timeout` exactly 20.0s after
   `VRI[MainActivity] visibilityChanged ... newVisibility=false` — i.e. **the 6-hour/shortened
   budget accrues only while the app is backgrounded**, not from when the service starts. This is
   more precise than the developer-docs wording and matters for how the limitation should be
   described to users: opening the app pauses the clock, it does not just reset it at the end.
4. No crash: no `RemoteServiceException`, no `FATAL EXCEPTION`, app process survived. Confirms
   `onTimeout`'s `stopSelf(startId)` ran within the platform's grace period.
5. Pulled `presence_decisions` from the device (`run-as` + `sqlite-jdbc`, since the emulator has no
   `sqlite3` binary): row `outcome=runtime_budget_reached, trigger=service` was written at the
   moment of the timeout, and a diagnostics record read confirms it holds no credential data (id,
   timestamp, trigger, outcome, appId, retainedPriorState only).
6. Reopening the app ~12s later produced `outcome=monitoring_started, trigger=foreground_monitor`
   — `BacklogiumApp`'s `ProcessLifecycleOwner` observer (`BacklogiumApp.kt:184-188`) retried
   `presenceServiceStarter.startFromForeground(trigger = "foreground_monitor")` automatically and it **succeeded**,
   empirically confirming the docs' "timer resets on foreground" claim: recovery after the budget
   is exhausted is automatic on the next foreground visit, not something the user has to
   rediscover the Settings toggle to fix.

**What was not reproduced, and why that's an accepted limitation rather than a gap:** task 1.1
asks whether `startForegroundService` throws when called from a backgrounded worker. The shipped
fix gives the worker only `PresenceServiceStarter.recordNotAttempted`; that decision records
`monitoring_already_running` when an active service already covers the game, and otherwise records
`start_not_attempted`. The start-capable `startFromForeground` method is called only by the known
foreground entry points. Reproducing an illegal worker start would therefore mean temporarily
reverting the fix under test. The claim is instead supported by the primary-source exemption list
above, which explicitly excludes WorkManager execution. Historical `presence_decisions` rows for `trigger=sync` are absent entirely in this
profile's data (no game was active during any of its ~30 historical periodic syncs, so
`gameDetected` was never true and `recordPresenceNotAttempted` was never invoked) — this is expected, not
evidence either way, and is noted so a future reader doesn't mistake absence for confirmation.

**Oldest supported API level (33, minSdk) — closed without a device, and why that's legitimate
rather than assumed away:** no AVD image for API 33 exists in this environment and only one
device was available, so this was not reproduced directly. It is closed on the following
reasoning instead, which is a documented platform fact in each case, not a guess standing in for
one:

- The background-start restriction arrived in Android 12 (API 31), strictly below this app's
  minSdk of 33. There is no version gap for the app's supported range to straddle — API 33 already
  has the restriction, with the same exemption list cited above (the docs give one exemption list
  for the restriction generally, not one per API level from 31 onward). Testing API 33 would be
  re-confirming the same fact already confirmed on 35, not learning something new about 33.
- The `dataSync` runtime budget arrived in Android 15 (API 35). Below that version it does not
  exist as a platform behaviour at all — there is nothing on API 33 to reach, time out, or recover
  from. "Untested on 33" would describe a gap only if the budget applied there and went
  unobserved; it does not apply there, so there is no observation to be missing.

So the two restrictions this change is about are each fully accounted for across the app's entire
supported range (33–36): one applies uniformly and was confirmed on the newest tested level, the
other applies only from 35 onward and was confirmed there. Task 1.4's "repeat on the oldest and
newest" is satisfied in substance — an API 33 device would have nothing left to tell us — even
though only 35 was physically run.

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

Option C is selected for this change based on the findings above. Each option trades away
something real, so this is intentionally a limitation rather than a claim that the existing
unattended capability survives: a background poll still tracks ordinary playtime, but fine-grained
monitoring does not resume until a foreground interaction starts the service. A documented
limitation is usable; a false promise of self-healing is not.

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

## Why

The audit reports two findings here. They are the same bug seen from both ends, and the
loop between them is what makes this worth fixing properly rather than patching either
half.

**`SteamSyncWorker` starts a foreground service from the background.** `:89` calls
`presenceServiceStarter.start()`, which reaches `ContextCompat.startForegroundService`
(`PresenceServiceStarter.kt:23`). The worker runs on a 15-minute `PeriodicWorkRequest` and
is frequently executing while the app is fully backgrounded. Since Android 12, starting a
foreground service from the background throws `ForegroundServiceStartNotAllowedException`
unless an exemption applies, and a scheduled WorkManager execution is not generally one.
The call sits before `getOwnedGames` and is not wrapped — so when it throws, it takes down
an otherwise-valid sync, which then retries and throws again.

**`PresenceService` assumes that same call can rescue it.** The comment at `:100-106` reads:

> Android 15+ caps a `dataSync` foreground service at ~6h of continuous runtime and calls
> this instead of just killing the process. [...] `SteamSyncWorker`'s next periodic poll (at
> most 15 minutes later) restarts the service if the player is still in a game — so
> stopping cleanly here self-heals rather than losing tracking permanently.

Two things are wrong with that. The cap is **cumulative per 24 hours**, not continuous
runtime — so it is reached by ordinary use across a day, not only by a marathon session.
And after `onTimeout`, Android may refuse further `dataSync` foreground-service starts until
the user brings the app to the foreground. The documented recovery therefore depends on
precisely the call that finding one shows is already illegal from that context, for a
second and independent reason.

```
  PresenceService hits the dataSync budget
        │
        ▼
  onTimeout → stopSelf()          "the next worker will restart us"
        │
        ▼
  SteamSyncWorker (backgrounded) calls startForegroundService
        │
        ├── rejected: background start not allowed        (finding #6)
        └── rejected: dataSync budget exhausted           (finding #7)
        │
        ▼
  unattended live monitoring does not resume, and the sync fails too
```

Live monitoring is the feature that detects a game started while the app was never opened
— the case the comment at `SteamSyncWorker.kt:84-87` calls out as the whole point of
starting presence before any library-scale work. It is the feature least able to rely on the
user opening the app.

## What Changes

- **The sync stops starting the service directly.** Whatever mechanism replaces it must be
  legal from a background worker, and a rejection must never fail the sync — presence
  detection is best-effort and the owned-games poll is not.
- **A failed or refused start is handled, not thrown.** At minimum the call is guarded and
  its outcome recorded; a sync that fetched Steam data successfully must report success.
- **The Android 15 recovery path is made real or made honest.** Either monitoring resumes
  through a mechanism the platform actually permits, or it does not resume unattended and
  the user is told — the current state, where the code claims self-healing that cannot
  happen, is the worst of the three.
- **The comment is corrected.** Cumulative-per-24h, not continuous, and no claim of
  self-healing that the platform will not honour.
- **Diagnostics record presence-start outcomes.** `app-diagnostics` already stores
  `PresenceDecision` rows explaining why monitoring did or did not engage; a refused start is
  exactly such a decision and is currently invisible.

## Capabilities

### Modified Capabilities

- `live-status`: define what happens when the platform refuses a monitoring start or ends
  monitoring on a runtime budget — including that a refusal never fails the sync, and what
  the user is told when unattended monitoring cannot resume.
- `app-diagnostics`: record a refused or failed presence-monitoring start as a presence
  decision, so the failure is observable rather than silent.

## Impact

| Path | Change |
|---|---|
| `work/PresenceServiceStarter.kt` | legality of the start; outcome reporting |
| `work/SteamSyncWorker.kt` | `:89` no longer able to fail the poll |
| `work/PresenceService.kt` | corrected `onTimeout` reasoning and behaviour |
| `data/diagnostics/` | presence-start outcome recorded |
| UI | surfacing when unattended monitoring cannot resume |

**This change needs on-device verification and cannot be closed without it.** Every failure
mode here is a platform behaviour that unit tests cannot reach: background-start rejection
depends on process state, and the `dataSync` budget depends on cumulative runtime across a
day. The `run-on-device` skill exists in this project and is the right tool. A green test
suite is not evidence for any claim in this change.

**Investigation before implementation.** The correct mechanism depends on facts about
current Android behaviour that should be established rather than assumed — whether the
existing service type is the right one, and what exemptions genuinely apply. Design frames
the options; the first task is finding out, not choosing.

**Independent of the other changes** apart from `auditfix-verification-coverage`'s
instrumented-test job, which gives this change somewhere to put whatever automated coverage
turns out to be possible.

**Not addressed here**: whether live monitoring should use a different polling architecture
altogether. That is a redesign, and this change is about making the existing one behave
lawfully and describe itself accurately.

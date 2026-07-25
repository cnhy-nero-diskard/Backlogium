# Design — Prominent now-playing presence

## Context

Two independent mechanisms currently exist, and this change adds a third notion of "session"
between them. Naming them precisely is the point of this document.

```
 Steam playtime deltas               Live presence poll
 (15-min periodic worker)            (30s, foreground-only)
        │                                    │
   SessionDiffer                    LiveStatusRepository.nowPlaying
        │                                    │
   Session rows  ──▶ XP / streaks      Home banner (display only)
   (authoritative)                     (never persisted)
```

- `Session` (Room) — synthesized when a poll observes `playtimeForever` increase. Feeds
  `GamificationUpdater` → XP, quests, streaks. **Authoritative, coarse (15-min granularity).**
- `NowPlaying` — a transient live signal, explicitly never persisted, emitted by a cold flow
  that only ticks while collected.

The elapsed timer this change adds is **neither**. It is a display value derived from "when did
the live poll first see this game running", which requires persisting one timestamp — the first
persisted live-status state in the app. That is a deliberate, narrow exception to
`live-status`'s no-persistence rule, and it must not leak into the XP path.

## Goals / Non-Goals

**Goals:**
- Now-playing is the most prominent element on Home while in game.
- An accurate elapsed timer that survives app restart and resets per game.
- Presence visible outside the app via an ongoing notification.
- Polling bounded to in-game periods only.

**Non-Goals:**
- The live timer influencing XP (explicitly forbidden — see Decisions).
- A permanently resident service; notification actions; replacing the periodic sync.

## Decisions

- **A foreground service owns the poll; `LiveStatusRepository` exposes app-scoped shared state.**
  The cold `flow { while(true) … }` becomes a `SharedFlow`/`StateFlow` held at application scope,
  fed by a service-owned collector. Home (and the profile header) become plain observers.
  *Why:* the notification must update while no screen is composed, which is incompatible with
  `WhileSubscribed` ownership. *Alternative rejected:* a periodic `WorkManager` job — WorkManager's
  floor is 15 minutes, so the timer would jump in 15-minute steps and the notification could
  linger a quarter-hour after quitting a game. That was weighed and rejected in favor of accuracy.

- **The service starts on detection and stops on game end — it is not resident.** Something must
  poll to notice a game started, and that something cannot be the service itself without running
  forever. Resolution: the existing 15-minute periodic sync (already calling Steam) checks
  presence and starts the service when it sees a game; the service then polls at 30s and
  **stops itself** when a poll reports not-in-game. Home also starts it on open if in game.
  *Consequence, accepted:* session start can be detected up to ~15 minutes late if the app was
  never opened. The timer shows time *since detection*, not since the true game launch — this is
  a real limitation and must be reflected honestly in the UI copy rather than presented as exact.

- **Elapsed time is persisted as a single `(appId, startedAt)` pair in DataStore, not Room.**
  `SettingsDataStore` already exists for non-relational state. One key-pair, overwritten on game
  change, cleared on game end.
  *Why:* it is ephemeral display state with no relations, no history, and no queries — a Room
  table and migration would be overkill. *Why persist at all:* an in-memory timestamp resets on
  process death, which is exactly when a long session is most likely to be interrupted.

- **The live timer is display-only and structurally separated from `Session`.** It lives in
  DataStore, not in the `sessions` table; nothing in `domain/` reads it; `GamificationUpdater`
  is untouched.
  *Why:* a live-clock-derived duration is wall-clock time with a game running, which is not the
  same as Steam-reported playtime (Steam undercounts idle/offline states). Letting it near XP
  would make XP unverifiable against Steam and would double-count against `SessionDiffer`.
  This separation is the most important constraint in the change.

- **The timer ticks client-side, not per poll.** With `startedAt` known, elapsed is
  `now − startedAt`, recomputed on a 1-second (UI) / 60-second (notification) cadence with no
  network involvement. The 30s poll only answers "still in this game?".
  *Why:* a smooth timer at zero network cost; poll cadence and display cadence are independent.

- **Steel-blue (tertiary) for the card, not gold.** `Color.kt` reserves the gold/amber accent for
  milestone moments (level-up, streak milestone, 100% completion), yet the current banner uses
  `primaryContainer` — gold. The enlarged card moves to the tertiary steel-blue container.
  *Why:* "in game right now" is a *state*, not an achievement. Scaling it up in gold would
  dilute the one accent the app uses to mean "you accomplished something". This also makes the
  card distinct from every other card on Home, which was the request.

- **Ongoing notification: silent, low-importance, non-dismissable while in game.**
  `IMPORTANCE_LOW` on its own channel (separate from the existing `hltb_refresh` channel),
  `setOngoing(true)`, `setOnlyAlertOnce(true)`, content "Playing X" + "47m". Tap opens the app.
  Cleared when the service stops. Skipped silently when `POST_NOTIFICATIONS` was never granted —
  the same graceful degradation `HltbRefreshWorker` already implements.

## Risks / Trade-offs

- **Battery** — the dominant risk. Mitigations: service runs only while in game; 30s poll is a
  single small HTTP request; the timer itself is free (client-side). Worth measuring before
  release, and worth an eventual user-facing toggle (out of scope here).
- **OEM background restrictions** — aggressive power managers may kill the service. The timer's
  persisted `startedAt` means a restarted service resumes with the correct elapsed value rather
  than restarting from zero.
- **Detection latency (~15 min worst case)** — inherent to not having a resident poller. Copy
  must not imply exactness.
- **`FOREGROUND_SERVICE_DATA_SYNC` policy** — Play Store requires a declared foreground-service
  type and justification. `dataSync` is the honest fit; confirm against current policy before
  release, as this is a store-review surface, not just a technical one.
- **Two "session" concepts** — a permanent comprehension cost for future contributors. Mitigated
  by naming (`LiveSession` vs `Session`) and by keeping the live one out of `domain/` entirely.

## Migration Plan

No Room migration. One new DataStore key-pair (`liveSessionAppId`, `liveSessionStartedAt`), absent
by default and cleared whenever not in game — so an install with no live session behaves exactly
as today.

Manifest additions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and the service
declaration with `android:foregroundServiceType="dataSync"`. `POST_NOTIFICATIONS` is already
declared.

## Open Questions

- Should the notification also appear when the app is in the *foreground*? Leaning yes (simpler:
  service lifecycle drives it unconditionally), but it is arguably redundant with the Home card.
- Should a user-facing "track presence in the background" toggle ship with this change or follow
  it? Deferring, but a battery-conscious user will want it.

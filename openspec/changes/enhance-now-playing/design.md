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
- Now-playing is the most prominent element on Home while in game, and reads as live rather than static.
- The running game is identifiable in the Library.
- An accurate elapsed timer that survives app restart and resets per game.
- Presence visible outside the app via an ongoing notification.
- Polling bounded to in-game periods only.

**Non-Goals:**
- The live timer influencing XP (explicitly forbidden — see Decisions).
- A permanently resident service; notification actions; replacing the periodic sync.
- Motion anywhere but the now-playing card; live indicators beyond the Library row and Home card.

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

- **The card carries a slowly flowing gradient, driven by an infinite transition over a brush offset.**
  A `rememberInfiniteTransition` animates a horizontal offset feeding a linear-gradient brush across
  the card, cycling over several seconds — slow enough to read as breathing, not pulsing.
  *Why motion at all:* colour distinguishes *categories*; motion distinguishes *states*. A static
  steel-blue card says "this card is about the game you're playing"; a moving one says "this is
  happening now". That is the actual thing being communicated, and it is why a brighter static colour
  would not substitute.
  *Why a gradient rather than a pulse or a blink:* a pulsing card competes with the level-up and
  streak-milestone Lottie animations, which are the app's designated attention-grabbing moments. A slow
  directional flow occupies a different register — ambient, not celebratory.
  *Composition-scoped by construction:* `rememberInfiniteTransition` stops when the card leaves
  composition, and the card is only composed while in game. There is no animation running on a screen
  that is not visible, and none at all when no game is running.

- **The animation honors the system's reduced-motion setting.** When animations are disabled at the OS
  level, the card renders the same gradient statically rather than animating.
  *Why:* a continuously moving surface is precisely what motion-sensitivity settings exist to
  suppress. Android exposes this via the animator duration scale; a zero scale means no motion.
  *Consequence:* liveness must not depend on motion alone — the timer is ticking and the card is
  present, so the state is still legible without it.

- **The Library marks the running game with a small live dot, in whichever section it sits.** Driven by
  matching `NowPlaying.InGame.gameId` against each row's `appId`.
  *Why in the Library at all:* it is the screen that lists every game, and it currently knows the least
  about the one fact that is happening right now. *Why a dot rather than reordering or a banner:*
  ordering is now user-controlled per list, so hoisting the running game would fight the chosen sort;
  a dot marks without rearranging.
  *New dependency, deliberately sequenced:* `LibraryViewModel` does not inject `LiveStatusRepository`
  today. Under the current observation-scoped design, having Library observe `nowPlaying` would start a
  30s poll whenever the Library is open — an unwanted side effect. Once this change moves ownership to
  the service, Library becomes a pure observer of shared state and the dot costs nothing. **The dot
  should therefore land with the service rework, not before it.**

- **`gameId` can be null or unmatched, and the dot simply does not appear.** Steam's `gameid` may not
  parse, and the running game may not be in the owned set at all (a non-Steam shortcut, family sharing).
  *Why not fall back to name matching:* `gameExtraInfo` is a display string, and matching it against
  library names would occasionally mark the wrong game. A missing dot is a smaller failure than a
  misplaced one.

- **A dedicated "live" green token is added to the palette.** `Color.kt` currently carries only the gold
  accent, the navy surface family, and the steel-blue secondary — no green. The dot needs one, and it is
  semantic (live/active), not decorative.
  *Why green specifically:* it is the near-universal convention for active presence, and it is
  unambiguously distinct from both the gold milestone accent and the steel-blue in-game lane, so a dot
  can never be mistaken for either.
  *Cross-change note:* `document-color-palette` documents the palette in the README. Whichever change
  lands second must carry the token into that documentation.

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
- **Continuous animation cost** — a gradient animating while Home is open redraws every frame. Confined
  to one card, only while in game, only while composed. Worth confirming it does not measurably add to
  the service's battery figure, since both are active in exactly the same window.
- **Motion as the sole liveness cue** — defeated by reduced-motion settings, which is why the ticking
  timer and the card's presence must carry the meaning independently.
- **A stale dot** — if the service dies and presence goes unrefreshed, the Library could mark a game
  that is no longer running. Bounded by the existing behavior that a failed fetch retains the last
  value; worth ensuring presence clears when the service stops rather than lingering.

## Migration Plan

No Room migration. One new DataStore key-pair (`liveSessionAppId`, `liveSessionStartedAt`), absent
by default and cleared whenever not in game — so an install with no live session behaves exactly
as today.

Manifest additions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and the service
declaration with `android:foregroundServiceType="dataSync"`. `POST_NOTIFICATIONS` is already
declared.

One additive colour token in `ui/theme/Color.kt` for live/active presence, wired into the theme's
scheme. No existing colour changes.

## Open Questions

- Should the notification also appear when the app is in the *foreground*? Leaning yes (simpler:
  service lifecycle drives it unconditionally), but it is arguably redundant with the Home card.
- Should the live dot also animate (a slow pulse)? Leaning no — one moving thing per screen, and the
  Library is a dense list where a pulsing dot per row would be noise.
- Should a user-facing "track presence in the background" toggle ship with this change or follow
  it? Deferring, but a battery-conscious user will want it.

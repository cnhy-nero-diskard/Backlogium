# Prominent now-playing presence

## Why

The now-playing signal is the app's only real-time element, and it is currently the least
prominent thing on Home: a 32dp icon and two lines of text in a card the same size as
"Steam history". It also disappears entirely the moment you leave the app — which is precisely
when you are playing the game it describes.

Two gaps follow from that:

1. **On Home, the running game deserves the most visual weight**, not the least. And it answers
   "how long have I been at this?" nowhere — the number a player actually wants mid-session.
2. **Off Home, there is no signal at all.** `LiveStatusRepository`'s poll is
   observation-scoped by construction (`stateIn(WhileSubscribed)`), so nothing tracks presence
   while the app is backgrounded. A player in a two-hour session sees nothing from the app that
   is ostensibly tracking it.

## What Changes

- A **large, visually distinct now-playing card** on Home when in game: prominent game art, the
  game's name, and a **live session timer**, in its own color lane so "playing right now" reads
  as its own category.
- A **live session start timestamp**, persisted, so the timer is accurate, survives app restart,
  and resets when the game closes.
- An **ongoing (sticky) system notification** while in game — "Playing X · 47m" — updated on the
  live cadence and cleared when the game closes.
- A **foreground service** that owns the 30s poll while in game, replacing the
  observation-scoped poll as the *owner* of live status. Home becomes an observer of shared
  state rather than the thing that keeps polling alive.

## Capabilities

### Modified Capabilities
- `live-status`: live presence gains a persisted session-start timestamp and a service-owned
  polling lifecycle that continues while the app is backgrounded. The "never persisted" rule for
  *presence itself* is unchanged.
- `app-ui`: the now-playing indicator becomes a prominent card carrying elapsed session time,
  and an ongoing notification surfaces the same state outside the app.

## Impact

- **Affected code (new):** a foreground service owning the poll; an ongoing-notification channel
  and builder; persisted live-session state; the enlarged Home card.
- **Affected code (modified):** `LiveStatusRepository` — the poll loop moves behind a
  service-owned, app-scoped shared flow; `HomeViewModel`/`HomeScreen`; `AndroidManifest`
  (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, service declaration; `POST_NOTIFICATIONS`
  is already requested for the HLTB worker).
- **Sequencing:** land after `add-steam-profile-header`, which also reads live presence. Doing
  this second means the header picks up service-owned state without being rewritten.
- **Battery:** a 30s network poll that runs while the app is backgrounded is the single largest
  ongoing cost this app has taken on. Bounded by starting the service only when in game and
  stopping it when not — see design.

## Non-goals

- **Polling while not in game.** The service starts when a game is detected and stops when it
  ends; it is not a permanently resident service.
- **Making the live timer authoritative for XP.** Session minutes for XP remain
  `SessionDiffer`-synthesized from Steam playtime deltas. The live timer is a **display** value
  and must never feed the gamification pipeline — two distinct notions of "session" now coexist
  deliberately.
- **Notification actions** (stop tracking, quick links). Tap opens the app; nothing more.
- **Replacing the 15-minute periodic sync.** The service tracks presence; the worker still owns
  playtime, sessions, and recompute.

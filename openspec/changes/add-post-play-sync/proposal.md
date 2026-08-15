## Why

Quitting a game leaves the app visibly stale. The live monitor knows the moment a session ends —
`LiveStatusRepository` transitions `NowPlaying.InGame` to `NotPlaying` on the next 30-second poll —
but nothing acts on that knowledge. Playtime is only ever discovered by the periodic sync's
playtime diff, which runs every 15 minutes, so History can show nothing for a session that ended
14 minutes ago. The only way to see the session you just finished is to open Settings and press
"Sync now", which makes the app look like it did not notice something it demonstrably did notice.

The fix is small and specific: when presence reports that a game stopped, ask Steam about that one
game. It is one request, its size is independent of library size, and it fires at most a handful of
times a day — once per play session rather than on a schedule.

The reason this is not a one-line change is that Steam's `playtime_forever` does not update the
instant a game exits. It settles over the following minutes, so a request fired at the moment
presence drops usually returns the value the app already has. A useful post-play fetch is therefore
a short bounded retry that stops as soon as the increase appears, not a single call.

## What Changes

- Observe the `InGame` → `NotPlaying` transition and enqueue a targeted playtime fetch for the game
  that just stopped.
- Fetch via `IPlayerService/GetRecentlyPlayedGames` with a count of one — the smallest response that
  answers the question, and a plain GET, unlike `GetOwnedGames`'s `appids_filter`.
- Retry on a bounded schedule — immediately, then at 1, 3, and 8 minutes — stopping at the first
  observation that shows the increase, and giving up silently after the last attempt.
- Feed the observed playtime through the existing session-synthesis and persistence path, so a
  post-play fetch and a periodic poll produce identical records and cannot double-count.
- Record each post-play fetch as its own diagnostics run, distinguishable by trigger from a periodic
  or manual sync.

## Capabilities

### Modified Capabilities

- `steam-sync`: Adds a targeted, play-triggered poll whose scope is a single game, defines its
  bounded retry schedule and its termination conditions, and requires it to commit through the same
  exactly-once path as every other poll.
- `live-status`: The end of an observed session becomes an event other work can act on, without the
  live-status layer itself performing library work or persisting presence.
- `app-diagnostics`: Post-play fetches are recorded and distinguishable from periodic and manual
  runs.

## Impact

- **Affected code:** `data/remote/SteamApi.kt` (one new endpoint), a new DTO, a new WorkManager
  worker and its scheduler, `data/repo/LiveStatusRepository.kt` or `work/PresenceService.kt` for the
  transition hook, and the sync persistence path it reuses.
- **Storage:** No schema change. The fetch writes through the existing games/sessions/daily-progress
  commit.
- **Network:** At most four small requests per completed play session, typically one or two. No
  change to the periodic 15-minute cadence.
- **Dependencies:** None new. WorkManager, Retrofit, Room, and Hilt are already in use.

## Non-goals

- Changing the periodic sync's 15-minute cadence, or making it conditional on this feature.
- Achievement, genre, or HowLongToBeat refresh for the played game. This change fetches playtime
  and nothing else; existing deferred passes continue to own the rest.
- Acting on the start of a session. Only the end carries new playtime.
- A user-facing setting. The fetch is recorded in diagnostics but has no toggle.
- Deriving sessions anywhere other than the on-device engine. This change moves *when* an
  observation happens, never *who* interprets it.

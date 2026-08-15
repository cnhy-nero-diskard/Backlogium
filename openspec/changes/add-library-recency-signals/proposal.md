## Why

Backlogium can tell you how much you have played a game and how far through its achievements you
are. It cannot tell you *when* anything happened, or that anything changed.

Three consequences, all felt as the same absence:

- **A library is a flat wall.** Nothing distinguishes a game bought this morning from one owned for
  six years, or a game just started from one abandoned in 2019. Scanning the Library for "what's
  going on lately" is impossible; every card looks equally settled.
- **Buying games is invisible.** A Steam sale adds eight games and the app says nothing. They appear
  silently, sorted by playtime into the bottom of the list where nothing is ever seen.
- **Game detail cannot answer "when did I last play this?"** — the single most common question about
  a backlog. It shows total playtime and completion length but no date, so a game with 40 hours
  gives no hint whether that was last week or four years ago.

The root cause is that two facts are simply not recorded. `Game` has no notion of when a game
entered the library, and `OwnedGamesDto` never requests `rtime_last_played`, so Steam's own answer
to the last-played question is discarded on every sync. Everything above follows from those two
gaps, which is why they are one change rather than three.

## What Changes

- Record when each game first appeared in the library, and record Steam's last-played timestamp for
  each game by requesting `rtime_last_played` on the owned-games call.
- Evaluate dormancy inside the poll that ends it — while the pre-return last-played time still
  exists — and record that a return happened, since the poll's own update destroys the evidence.
- Derive three mutually exclusive recency states per game — **newly added**, **newly played**, and
  **returned to play** — each with a defined onset, a defined expiry, and a defined precedence.
- Present the active state as a symbolic corner badge on Library rows and grid cells, on the game
  detail header, and on Home's game surfaces, at every display density.
- Show the game's last-played date in the game detail summary, distinguishing "never played" from
  "no date known".
- Announce newly acquired games with a dismissible Home banner that names how many arrived, links to
  them, and expires 24 hours after the sync that found them.
- Baseline the first sync so that a fresh install badges nothing and announces nothing, and keep a
  restore from *creating* recency events while still reproducing the ones the backup recorded.

## Capabilities

### New Capabilities

- `library-recency`: Defines what is recorded when a game enters the library, when it was last
  played, and when it returned from dormancy; the three recency states with their onset, expiry,
  precedence, and mutual exclusivity; the baselining rule that keeps a first sync silent; the
  boundary between recency data a restore carries and recency events a restore must not cause; and
  the new-acquisition announcement's lifecycle.

### Modified Capabilities

- `steam-sync`: The owned-games poll requests and persists Steam's last-played timestamp, stamps
  first-seen on games it has not seen before, and evaluates dormancy before overwriting the value
  that evaluation depends on — while a baseline poll does none of it in a way that reads as new.
- `app-ui`: Game lists and the game detail header carry a symbolic recency badge at every density;
  the game detail summary shows the last-played date; Home carries the new-acquisition banner.
- `backup-restore`: All three new per-game fields round-trip through export and import, and a restore
  records no arrival, no return, and no announcement of its own.

## Impact

- **Affected code:** `data/local/entity/Game.kt`, `data/local/BacklogiumDatabase.kt` (migration
  16→17), `data/remote/dto/OwnedGamesDto.kt`, `data/remote/SteamApi.kt`, the sync persistence path,
  `data/repo` for the state derivation, `ui/library/`, `ui/gamedetail/`, `ui/home/`,
  `data/backup/`.
- **Storage:** Three nullable columns on `games`. A dismissal timestamp and the last acquisition
  batch in Preferences DataStore.
- **Network:** No additional requests. `rtime_last_played` is an extra field on a call the sync
  already makes with `include_appinfo=1`.
- **Dependencies:** None new.

## Non-goals

- A history of when games were acquired. One first-seen timestamp per game, not an acquisition log.
- Detecting removed games, refunds, or family-shared titles leaving the library.
- Wishlist arrivals. `add-wishlist-section` owns that surface.
- Notifications. The acquisition announcement is in-app only.
- Badges on collection member lists.
- Sorting or filtering by any recency state. These are signals to notice, not a new query axis.
- Reconstructing when previously-owned games were acquired. Games present at baseline have no
  meaningful first-seen date and must not claim one.

## Depends on

Nothing. This change is self-contained. It is, however, a prerequisite for anything that wants to
present a library "what changed" view, and it should be sequenced before `add-hidden-games` reaches
the same Library renderers if both are active.

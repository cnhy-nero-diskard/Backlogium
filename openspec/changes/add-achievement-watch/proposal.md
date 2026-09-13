# Achievement Watch

## Why

The app is an achievement tracker that finds out about achievements last.

Everything needed to be first is already in place and already running. `LiveStatusRepository` knows
which game is running and re-checks every 30 seconds. `SteamApi.getPlayerAchievements(appId)` answers
"has this one game unlocked anything" in a single request whose cost is independent of library size.
And `progress-events` is a durable at-most-once delivery pipeline with process-death survival, a
priority order, and a first-run baseline that suppresses history — built for exactly this shape of
moment.

The two are simply not connected. An achievement unlocked at 21:04 is discovered by whichever comes
first: the next periodic sync, up to fifteen minutes later and often longer under Doze; or the
deferred reconciliation pass, which waits for the device to be charging on an unmetered network. By
the time the app knows, the player has stopped playing, and the app's response to a thing that just
happened is a number that quietly changed.

The gap is a request-shaped gap, and it is small:

```
   21:04  unlock happens
   21:04  Steam knows                                     ← app could ask, and does not
   21:17  periodic sync eventually notices                ← or much later
   next   the number in the Library is different          ← the app's entire reaction
```

There is also a real correctness hazard sitting in the obvious implementation, which is the second
reason to specify this rather than write it. `Achievement.snapshotPercent` is captured at the **first
sync that observes an achievement unlocked** and is never overwritten, and the engine computes XP
from the snapshot rather than the live percentage. A watcher that fetches only per-player unlock
state would store unlocked rows with **no snapshot**, and those achievements would be worth **zero
XP permanently** — silently, irreversibly, and precisely for the achievements the player was most
present for. The watch has to fetch global percentages alongside, exactly as
`Per-data-kind freshness` already requires of every other refresh path.

## What Changes

- **A watch loop bound to presence.** While the player is in a game and presence observation is
  running, the app polls that one game's achievements. It starts when presence starts, stops when
  presence stops, and never extends presence's lifetime or starts a schedule of its own.
- **Backing off when nothing happens.** The interval starts around a minute and doubles toward a
  five-minute ceiling across consecutive unchanged observations, resetting to the floor on any
  unlock. Unlocks cluster; idle sessions should not cost what active ones do. An eight-hour session
  with nothing happening costs roughly a hundred requests — around a tenth of what the 30-second
  presence poll already spends over the same period.
- **Global percentages fetched whenever a new unlock is stored**, and only then. Steady-state cost
  stays one request per tick; the second request is paid only on the tick that has something to
  record. This is what keeps the rarity snapshot — and therefore the XP — correct.
- **A new progress event: achievements unlocked.** It joins the closed vocabulary carrying the game
  and the achievements observed, collapses several unlocks in one observation into one event, sits
  below a level-up and above a quest-met in the priority order, and inherits the pipeline's
  at-most-once delivery and process-death survival unchanged.
- **One event, two possible surfaces.** Backgrounded, it arrives as a notification on its own channel.
  Foregrounded, it is presented in the app. It is delivered **once**, whichever surface got there
  first — the notification and the in-app reveal are two deliveries of one durable event, not two
  features. With no notification permission it simply waits in-app, and nothing errors.
- **The first observation of each watch session is a baseline and celebrates nothing.** Starting a
  game whose stored achievement data is months stale — or absent, as it is for any game the tiered
  refresh has never fetched — must not fire three hundred notifications. This is the same rule
  `SessionDiffer.baseline` and "a player's first recorded progress is not celebrated" already
  establish.
- **Only the watch produces these events.** A periodic sync or a reconciliation pass that discovers
  unlocks stores them as it does today and announces nothing. Reconciliation covers the whole library
  and would be a notification flood.
- **The watch stores observations and derives nothing.** Storage goes through the existing serialized
  per-game refresh path, so a watch tick and a sync cannot race, and the on-device engine remains the
  sole author of XP.
- **A setting, defaulting on.** It is new network activity while playing, and the app already gives
  the player a switch over the live monitor for the same reason.

## Capabilities

### New Capabilities
- `achievement-watch`: the loop's binding to presence, its cadence and back-off, the per-watch-session
  baseline, what it fetches and when, its serialization against other refreshes, the rule that it
  derives nothing and never extends presence, and its behaviour when unavailable or refused.

### Modified Capabilities
- `progress-events`: the vocabulary gains an achievements-unlocked event, with its collapse rule, its
  place in the priority order, and its baseline behaviour.
- `steam-achievements`: the watch is a recognised refresh path, subject to the existing serialization
  and rarity-snapshot rules, and required to fetch global percentages with any newly observed unlock.
- `app-ui`: the in-app presentation of an unlock event, and its notification.
- `app-settings`: a switch for the watch, beside the existing live-monitor switch.
- `haptic-feedback`: the vocabulary gains an intent for an achievement unlock, deliberately lighter
  than a level-up's.
- `app-diagnostics`: watch fetches are recorded and distinguishable from periodic, manual, and
  reconciliation work.

## Impact

- **Affected code (new):** an achievement watcher observing `LiveStatusRepository`'s in-game state,
  its cadence policy, the unlock diff, and the notifier for the new channel.
- **Affected code (modified):** `data/repo/AchievementRepository.kt` (a single-game watch fetch through
  the existing serialized path), `domain/ProgressEvent.kt` and `ProgressEventDetector` (the new event
  kind), `work/PresenceService.kt` (hosting the watch alongside presence), `ui/util/Haptics.kt` (the
  new intent), `ui/settings/`, and the surface that presents pending events.
- **Storage:** no schema change. The watch writes achievement rows through the path the sync already
  uses; the event's delivery record uses the existing progress-event durability.
- **Network:** one request per tick while in a game, plus one more on a tick that observes an unlock.
  No requests at all while not in a game.
- **Overlaps `add-post-play-sync`.** Both hang targeted work off presence transitions — that change on
  the *end* of a session, this one *during* it. Neither depends on the other, and if both land they
  should share one place where presence-triggered work is registered rather than each hooking
  presence separately.
- **Inherits presence's lifecycle honestly, including its ceiling.** `live-status` already requires
  that behaviour after a platform runtime budget is reached be stated rather than pretended away. The
  watch stops when presence stops, for whatever reason, and does not claim otherwise.
- **The notification channel is new and is not the presence channel.** `presence` is a silent,
  low-importance, ongoing notification for a foreground service. An unlock is a discrete, dismissible,
  alerting event, and putting it on the same channel would either make the ongoing notification alert
  or make the unlock silent.

## Non-goals

- Watching a game the player is not currently in, or any background schedule independent of presence.
- Producing unlock events from the periodic sync or the reconciliation pass.
- Real-time playtime, session detection, or any derivation. The watch observes and stores; the engine
  derives.
- A notification for anything other than an unlock — no level-up, quest, or streak notifications are
  introduced here.
- Achievement *progress* short of an unlock. Steam's stats API can expose partial progress for some
  games; this change watches unlock state only.
- Reading the cloud presence poller. It samples presence, not achievements, and derives nothing by
  design.

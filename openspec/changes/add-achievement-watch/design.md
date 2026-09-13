## Context

Three existing mechanisms determine almost everything about this change's shape.

**Presence already runs a loop.** `PresenceService` polls `GetPlayerSummaries` every 30 seconds while
the player is in a game, and `live-status` makes the background presence observer — not any screen —
the owner of that cadence. A second loop with its own lifecycle would need its own answer to every
question presence has already answered: when it starts, what happens when the process dies, what
happens when the platform's runtime budget runs out. Binding to presence inherits all of them.

**Progress events already deliver exactly once, durably.** The pipeline survives process death,
collapses multiple threshold crossings into one event, defines a priority order, forces an exhaustive
haptic mapping, and refuses to celebrate a first baseline. An unlock is precisely that kind of moment,
and the vocabulary is closed specifically so a new one has to be added deliberately rather than
delivered through a side channel.

**The rarity snapshot is a one-way door.** From `Achievement`'s own documentation:

> `snapshotPercent` is the global unlock percent captured the first sync that observed the
> achievement unlocked, and is **never overwritten afterward** — it, not the live `globalPercent`,
> drives the engine's rarity/XP.

So the first writer of an unlocked row decides that achievement's XP forever. If the watch becomes
that first writer and has no global percentage in hand, the achievement is worth zero XP for good:

```
   watch observes unlock
        │
        ├── with globals    → snapshotPercent set  → tiered XP, correct, permanent
        └── without globals → snapshotPercent null → 0 XP, permanent, silent
```

A later sync cannot repair it, because the snapshot is only ever taken at first observation. This is
the single most consequential detail in the change and the reason it is specified rather than
inferred.

## Goals / Non-Goals

**Goals:**

- Tell the player about an unlock while they are still playing.
- Cost a small fraction of what the presence loop already costs.
- Keep XP exactly correct, which means never storing an unlock without its rarity snapshot.
- Reuse the durable event pipeline rather than inventing a delivery mechanism.
- Fail quietly and completely: no permission, no presence, no network — nothing breaks.

**Non-Goals:**

- Sub-minute latency. "While you are still playing" is the requirement, not "instantly".
- Watching anything other than the running game.
- A background schedule the player did not start by launching a game.
- Notifying about unlocks discovered by the sync or by reconciliation.
- Deriving anything. The watch is an observer.

## Decisions

### 1. The watch is a passenger on presence, never a driver

```
   presence observation running ────────────────────────────────▶ stops
        │                                                            │
        └── watch ticks ─── ─── ─── ─── ─── ─── ─── ─── ─── ─── ─────┘
                                                             (no grace, no extension)
```

The watch starts when presence starts reporting a game, stops when presence stops for any reason, and
has no opinion about why. If the platform ends the foreground service, the watch ends with it — and
`live-status` already requires that outcome be stated honestly rather than worked around.

Critically the watch must never keep presence alive: a watcher that extends a foreground service's
lifetime to finish its own work turns an opt-in live monitor into something the player did not agree
to.

### 2. Back off, and reset on an unlock

A fixed cadence has to choose between latency and cost, and the traffic is bursty — unlocks cluster
around a boss, a chapter end, a run completing, and then nothing for two hours.

```
   tick    1     2     3     4     5     6      7 ...
   gap    1m    2m    4m    5m    5m    5m     5m       (unchanged: double, cap at 5m)
                            ↑ unlock observed
   gap                     1m    2m    4m     5m        (reset to floor)
```

Rough cost of an eight-hour session with nothing happening: about a hundred requests. The 30-second
presence poll spends roughly 960 over the same period, so the watch is around a tenth of a cost the
app already pays without comment. A flat 30-second watch would have been comparable to presence
itself, for a signal that does not need it — nobody experiences "within one minute" as less immediate
than "within thirty seconds" when the alternative is fifteen minutes.

### 3. Globals only when there is something to record

Fetching global percentages on every tick would double steady-state cost to protect against a case
that happens a handful of times per session.

```
   tick with no change   →  1 request   (per-player state)
   tick with an unlock   →  2 requests  (per-player state, then globals)
```

The snapshot rule is satisfied because the globals fetch happens **before** the newly unlocked row is
written, not after. Ordering matters here and is specified, not left to the implementation.

If the globals fetch fails, the correct behaviour is to write nothing rather than to write an
unsnapshotted row — a missed notification is recoverable by the next sync, a null snapshot is not.
This is the one case where the watch deliberately drops an observation.

### 4. The first observation of a watch session is a baseline

The alternative — diffing the first observation against stored state — fails badly in a case that is
not rare but typical. `steam-achievements`' tiered refresh never fetches achievements for a game with
no recorded playtime, and reconciles the rest only when charging on unmetered Wi-Fi. So the stored
state for a game the player is launching for the first time in a year is either months old or
entirely absent.

```
   stored: 12 unlocked (or nothing at all)
   Steam:  312 unlocked
   naive diff → 300 "new" unlocks → 300 notifications, all of them years old
```

So: the first observation stores what it finds — with globals, so the snapshots are right — and
produces no event. Subsequent observations in the same watch session diff against the previous
observation.

The accepted cost is real and worth naming: an achievement unlocked in the first minute of a session
is not announced. It still reaches storage, XP, History, and the Library. It just does not pop. That
is the same trade `SessionDiffer.baseline` and "a player's first recorded progress is not celebrated"
already make, and consistency with them is worth more than recovering a sixty-second window.

### 5. Only the watch produces unlock events

A reconciliation pass covers the entire library and can discover hundreds of unlocks in one run. A
periodic sync refreshes every game with a playtime increase. Neither is a moment, and treating either
as one produces a notification flood on exactly the days the player was away.

The event is therefore produced only where it means something: the player is in the game, right now,
and the thing just happened. Every other path stores unlocks as it does today and says nothing.

### 6. One event, whichever surface is available

```
                    ┌─ app foregrounded  → in-app presentation ─┐
   unlock event ────┤                                            ├─→ acknowledged once
                    └─ app backgrounded  → notification ────────┘
```

This is not two features and must not become two records. The pipeline's "an event is delivered once"
requirement already guarantees it, including across process death, which is exactly the case the
user's description implies — *"or a one-time short pop-up the moment they go back to the app"*.

With no notification permission the backgrounded branch simply does nothing, no error is surfaced,
and the event stays pending for the in-app branch. That is a better outcome than the permission-gated
paths the app already has, and it comes for free from using the durable pipeline.

### 7. Priority: below a level-up, above a quest

The existing order is level-up → streak milestone → quest met → streak broken. An unlock slots after
the streak milestone and before quest-met:

- below level-up and streak milestone, which are rarer and larger;
- above quest-met, because a specific named achievement is more informative than "you played thirty
  minutes", and a quest-met is a near-certainty on any day the player is in a game at all.

### 8. A lighter haptic, deliberately

The vocabulary is rationed, and unlocks are by far the most frequent earned moment in the app —
plausibly twenty in an evening against one level-up in a fortnight. Reusing the level-up intent would
make the rarest moment feel like the most common one.

So: a new intent, explicitly lighter than a level-up's, delivered once per **event** rather than once
per achievement in it. And only where the event is presented in-app — a notification's own vibration
belongs to the platform, and "a haptic never fires alone" forbids one for something the player cannot
see.

### 9. Its own notification channel

`presence` is `IMPORTANCE_LOW`, silent, ongoing, and updated in place for a foreground service. An
unlock is discrete, dismissible, and worth an alert. Sharing the channel would force one of the two to
be wrong, and channel importance is user-editable per channel — which is the correct place for a
player who wants unlocks silent to say so.

### 10. A setting, on by default

It is new network activity, and `app-settings` already carries an opt-in live-monitor switch for the
same reason. Defaulting it off would be the app declining to do the thing it was just built to do; the
switch exists so a player on metered data can decline instead.

## Risks / Trade-offs

- **An unsnapshotted row is unrecoverable, and this change creates a new writer of unlocked rows.** →
  The mitigation is ordered and absolute: globals first, write second, and on a globals failure write
  nothing at all. This is the change's highest-value test — a fixture where the globals call fails
  must leave storage untouched.

- **Request volume grows while playing.** → Bounded by back-off and by presence's own lifetime, and
  roughly a tenth of what presence already spends. It lands in the rolling request counters, so it is
  observable rather than assumed, and it is switchable off.

- **The first-minute window is genuinely missed.** → Named and accepted. Recovering it means diffing
  against stored state, which produces the 300-notification failure in the common stale-data case.

- **Unlock notifications could become noise in an achievement-dense game.** → Collapsed per
  observation rather than per achievement, so a tick that sees six unlocks is one notification. If
  that is still too much, the next lever is a longer floor, not per-achievement filtering, which would
  require the app to judge which achievements matter.

- **Two changes now hang work off presence transitions.** `add-post-play-sync` hooks the end of a
  session; this hooks the duration of one. → If both land, they should register through one seam
  rather than each observing `LiveStatusRepository` independently, or the ordering between "session
  ended" and "final watch tick" becomes accidental.

- **The watch runs only while presence does, so a session played with the app killed and Live monitor
  off produces nothing.** → Correct and honest. The feature is "tell me while I'm playing", and the app
  has to be observing to do that. The sync still catches everything afterwards, as it does today.

- **A new event kind touches a pipeline with subtle serialization requirements.** `progress-events`
  has hard-won rules about transition recovery and acknowledgement races. → The new event is produced
  from a watch observation rather than from a derived-value transition, so it does not participate in
  the persist/recovery protocol at all — a distinction worth stating explicitly so it is not wired
  into that protocol by pattern-matching.

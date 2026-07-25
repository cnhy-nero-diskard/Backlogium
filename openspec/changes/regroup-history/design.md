# Design — Grouped playtime history

## Context

Three facts about the data shape this design, and two of them are counter-intuitive.

**1. A session's clock range is an estimate; its minutes are not.** `SessionDiffer` states the
convention outright:

> `startAt` = the previous poll's time (best estimate of when play began)
> `endAt` = the last-increase timestamp (kept current while the session is open)

`minutes` accumulates Steam's `playtime_forever` deltas. So the range endpoints are quantized to the
15-minute poll cadence, and `endAt − startAt` is **not** `minutes`. They diverge whenever polls are
spaced irregularly — a sleeping device, a deferred `WorkManager` job, offline play synced on the next
poll. A session can legitimately read "2-hour span, 90 minutes played".

**2. Per-day attribution already happens per poll, not per session.** `SteamSyncWorker` adds each
poll's `playedDeltaByAppId` to *that poll's* day. A session spanning 11:50 pm → 12:20 am therefore has
its minutes split across two `DailyProgress` rows, while remaining one `Session` row with a single
`startAt`.

**3. Reads are row-capped, not date-ranged.** `SessionRepository.recentSessions` is
`observeRecent(100)`. Thirty days of history routinely exceeds 100 sessions, so the query must change
regardless of anything else in this change.

Current structure, and where it's going:

```
  today                              →   ▼ July 25, 2026 · 3h 12m · quest met
  ── Recent sessions (flat, 100) ──         ▼ (art) Game X · 3h 12m
     Game X   Jul 25, 3:00 pm  2h 35m           ~12:00 am – 12:37 am · 37m played
     Game Y   Jul 25, 8:00 pm  40m              ~3:00 pm – 5:55 pm · 2h 35m played
     Game X   Jul 24, 9:15 pm  1h 05m         ▶ (art) Game Y · 40m
     …                                     ─ Daily stats ─
  ── Daily stats (flat) ──               ▶ July 24, 2026 · 1h 05m · quest met
     Jul 25   3h 12m · quest met         ▶ July 23, 2026 · 0m
     Jul 24   1h 05m · quest met             … 30 days, then "Load older"
```

## Goals / Non-Goals

**Goals:**
- Reconstruct a day at a glance: what was played, for how long, roughly when.
- One structure instead of two lists that duplicate each other.
- Never present an estimate as exact, or let two different measurements read as one.

**Non-Goals:**
- Exact clock ranges, span-derived durations, midnight splitting, authoritative day totals,
  filtering, per-game history elsewhere, session editing.

## Decisions

- **Sessions group by the local date of `startAt`.** A midnight-crossing session sits entirely on the
  day it began.
  *Why:* it matches how a player narrates their own evening ("I played until half past midnight"), and
  the alternative — splitting at midnight — would require prorating `minutes` across the span, which
  the data does not support. *Consequence:* accepted and handled by the next decision.

- **A day header shows the sum of the sessions listed beneath it, not the stored `DailyProgress`
  total.** The quest indicator and goal-minutes still come from `DailyProgress`.
  *Why:* a header that doesn't add up to its own contents is the inconsistency users spot
  immediately, and this is a breakdown screen — internal consistency is its whole job.
  *Consequence, accepted:* for a midnight-crossing session, History's day total can differ from the
  total that drove that day's quest, because `DailyProgress` split those minutes per poll. Both
  figures are correct measurements of different things. The quest boolean comes from the authoritative
  source, so the two can never contradict each other on the thing that matters — whether the quest was
  met. *Alternative rejected:* showing the `DailyProgress` total keeps History and Home in lockstep but
  guarantees the expanded list won't sum to its own header.

- **Session rows show an approximate range plus tracked minutes, visibly distinguished.**
  `~3:00 pm – 5:55 pm · 2h 35m played`. The tilde marks the range as poll-quantized; "played" marks
  the minutes as what Steam counted.
  *Why:* both numbers are true and they measure different things, so the only dishonest option is to
  present them as one. *Alternative rejected:* deriving duration from the span (always internally
  consistent, but overstates playtime and would disagree with the XP those minutes earned).
  *Copy is load-bearing here* — dropping the tilde or the word "played" turns a careful distinction
  back into an apparent arithmetic error.

- **"Daily stats" collapses into the day headers rather than remaining a separate section.** Total,
  goal minutes, and quest state move onto each day's header row.
  *Why:* the old section existed because there was nowhere else to put per-day information. Now every
  day has a header, and two lists keyed by the same date is duplication. *Kept:* the section label as a
  divider above the past days, matching the requested layout — today's group sits above it, expanded.

- **One flat `LazyColumn` with expansion state, not nested lazy lists.** Compose does not support
  nesting a vertically-scrolling `LazyColumn` inside another; the list must be flattened into
  `item`/`items` calls whose contents depend on which days and games are expanded.
  *Why:* the natural expression of a tree is nested lazy lists, and it throws at runtime. Calling this
  out here because it is the first thing anyone implementing this will reach for.

- **Expansion state is transient, keyed by date and appId.** Today expanded by default, everything else
  collapsed; state resets on navigation away.
  *Why:* a lens, not a preference. Keying by date/appId rather than list index keeps expansion stable
  when a sync inserts new sessions mid-list.

- **Sessions are read by date range; 30 day-groups initially, with "load older".** A new
  `observeSince(cutoff)` on `SessionDao` replaces the fixed limit for this screen, with the cutoff
  derived from the requested window.
  *Why:* `observeRecent(100)` cannot cover 30 days for an active player — a real correctness bug for
  this feature, not a performance nicety. *Note:* the window is over days, not rows, so a heavy 30 days
  loads more sessions than a light one; acceptable for a local Room query, worth watching if someone
  raises the default.

- **Open sessions render with an open-ended range** (`~3:00 pm – now`) and are included in their day's
  total.
  *Why:* the existing screen already flags `open` sessions with "· live"; the tree should not lose that.

- **Days with progress but no sessions render a header with nothing to expand.** Rare (both derive from
  the same sync) but possible.
  *Why:* an expand affordance that opens onto nothing is worse than no affordance.

## Risks / Trade-offs

- **Day total vs. quest total divergence** — bounded by the post-midnight portion of a crossing session,
  and only visible to late-night players. Documented rather than papered over.
- **Copy drift** — the tilde and the word "played" are doing real work. If either is dropped in a later
  tidy-up, the screen starts looking arithmetically broken. Worth a comment at the call site.
- **Row density and depth** — three levels of hierarchy on a phone. Mitigated by collapsing past days
  by default and keeping session rows to a single line.
- **Load cost of a wide window** — 30 days of an active library is a few hundred rows joined against
  the game list in a `combine`. Fine locally; revisit if the default window grows.
- **Losing the "recent sessions" glance** — today's flat list showed the last few sessions across all
  days at once. With grouping, yesterday's tail is one collapsed row away. Accepted: the request is
  explicitly for per-day structure.

## Migration Plan

None. No schema change, no new persistence, no new network calls. `DailyProgress`, `Session`, and the
sync path are all untouched; this is a read-side regrouping plus one new DAO query.

## Open Questions

- Should the 30-day default be user-adjustable, or is "load older" enough?
- Worth showing a per-day XP figure on the header, now that per-game XP contribution is being derived
  elsewhere? Tempting, but XP is not attributed per day anywhere in the engine — it is recomputed from
  cumulative totals — so a per-day XP number would have to be invented. Deliberately out of scope.

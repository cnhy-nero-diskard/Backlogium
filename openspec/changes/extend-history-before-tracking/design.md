## Context

Two facts about the stored data drive everything here.

**One.** `AchievementMerge` stores Steam's unlock time faithfully, including its absence:

```kotlin
unlockedAt = dto.unlocktime.takeIf { it > 0L }?.times(1000L)
```

So the app holds dated unlocks reaching back years — and also a real population of unlocked
achievements with **no date at all**, because Steam reports `unlocktime = 0` for unlocks predating
its recording of them. Any per-day accounting has to answer for both.

**Two.** XP does not decompose by addition. `GamificationUpdater` feeds the engine one cumulative
figure per game:

```kotlin
(backfillByGame[appId] ?: 0) + (trackedByGame[appId] ?: 0)
```

and `gameXp` tapers over that total. Sixty minutes on a fresh game and sixty minutes on a game at
twice its completionist length are worth wildly different amounts, so "XP today" is a difference
between two states, not a sum over the day's minutes.

Today the History screen assumes every day has sessions, and every day is worth nothing in
particular:

```
   today                    ┌──────────────────────────────┐
     │                      │ sessions ✓  progress ✓  XP ✗ │
     │                      └──────────────────────────────┘
   install ─────────────────  the floor. Nothing below it exists.
     │
     │   2019-03-11  ▓▓▓ 7 achievements unlocked    ← stored, never rendered
     │   2017-08-02  ▓ 1 achievement unlocked       ← stored, never rendered
     ▼
```

## Goals / Non-Goals

**Goals:**

- Stop discarding dated unlocks, at any depth, including inside the current window.
- Let History reach back to the player's earliest dated evidence.
- Make each day state what it was worth, correctly, including days before the app existed.
- Never fabricate a minute, and never let an unknown quantity render as zero.
- Keep quests, streaks, and the current level bit-for-bit unchanged.

**Non-Goals:**

- Estimating pre-tracking playtime from anything — lifetime totals, achievement counts, HowLongToBeat
  lengths, or a rate derived from tracked days.
- Persisting a per-day XP figure.
- Making pre-tracking days interactive in the way tracked days are. There are no sessions to expand.
- A second definition of XP. Every value routes through `Gamification`.

## Decisions

### 1. Unlock dates join the day union; nothing else does

The fix is one term:

```kotlin
val allDates = (sessionsByDate.keys + progressByDate.keys + achievementsByDate.keys)
```

Deliberately not also: game purchase dates, `rtime_last_played`, or any date implied by a lifetime
total. Each would introduce a day carrying no evidence of anything that happened *on that day* — a
last-played date says a game was played then and says nothing about the day's shape, and a purchase
date is not play at all. `add-library-recency-signals` owns the last-played field; if it lands, a
follow-up can decide whether one more dated point per game is worth a day of its own.

### 2. Two kinds of day, distinguished at the model, not in the renderer

A day whose only content is unlocks is not "a day with zero minutes". Making the distinction in
presentation alone means every consumer of the grouping has to remember it, and one of them will
not.

```
   tracked day               pre-tracking day
   ───────────               ────────────────
   minutes: 47               minutes: unknown
   games:   expandable       games:   named by their unlocks, nothing to expand
   quest:   from stored row  quest:   not evaluated
   XP:      derived          XP:      derived (achievements only)
```

The day model carries which kind it is. "Minutes unknown" is a distinct state from "minutes zero",
matching how the app already refuses to conflate missing trophy data with zero trophies.

### 3. Pre-tracking days never reach the engine

`Gamification` computes streaks over a contiguous day sequence, and `gamification`'s spec requires
that sequence be contiguous. Feeding it a day from 2019 does one of two wrong things: with the gap
filled as unmet, it breaks the current streak; with the gap collapsed, it fabricates one.

So the boundary is absolute: these days are produced by the History grouping and consumed by the
History screen. They write no `daily_progress` row, are not evaluated for a quest, and are not part
of the sequence handed to the streak computation. A pre-tracking day therefore shows no quest state
at all — not an unmet one, which would be a claim the app cannot support.

### 4. Per-day XP is marginal, derived by replay, and reconciled

For each game, order its sessions by local start date and walk them, carrying the cumulative total
that already includes the frozen backfill offset:

```
   day d's playtime XP for game g
     = gameXp(backfill_g + cumulative_through_d)  −  gameXp(backfill_g + cumulative_before_d)
```

Summed over days, the terms telescope to `gameXp(backfill_g + tracked_g) − gameXp(backfill_g)`.
Achievement XP is a plain sum and decomposes by `unlockedAt` directly. So across the whole library:

```
   totalXp  =  Σ_days dailyXp(d)  +  Σ_g gameXp(backfill_g)  +  Σ undated unlocks achievementXp
                                     └──────────── undatedXp ─────────────────────────────────┘
```

Both remainder terms are real and neither is negligible: the first is exactly the XP the Steam-history
import granted, and the second covers achievements Steam never dated. This identity is the change's
correctness test, in the same spirit as `LibraryXp`'s assertion that per-game contributions sum to
the stored total — that one splits the total by game, this one splits it by day.

Ordering within a day does not matter: the telescoping holds for any order, because each day's
marginal value is a difference of cumulative totals at day boundaries.

### 5. The undated remainder is shown, not absorbed

The tempting shortcut is to fold `undatedXp` into the earliest day, or into the day of first sync,
so the columns add up. That is a lie with a plausible face — it would claim the player earned
several thousand XP on the afternoon they installed the app.

Instead the remainder is presented as its own line: XP the app counts but cannot place in time, with
its two sources named. A player who imported their Steam history should see that the import is what
produced it. A player who never imported sees only the small undated-unlock term, or nothing.

### 6. Pre-tracking days carry XP, and it will look surprising

Achievement XP is already in the player's total — the rarity snapshot is taken at the first sync
that observes an achievement unlocked, so pre-install unlocks are snapshotted and do contribute. So
a day in 2019 with twelve rare unlocks genuinely carries several hundred XP, and shows it, beside
"minutes unknown".

That reads oddly at first and is exactly right: that XP is in the total, it was earned on that day,
and the minutes that accompanied it are unrecoverable. It is also the single clearest illustration of
why provenance labelling is worth having.

### 7. Derived on read, at the same cost posture as the rest of the app

Nothing here is persisted. A stored per-day XP column would need invalidating on every sync, every
unlock, every backfill import or reset, and every rules edit — and `app-settings` already promises
rule edits are retroactive, which a stored series would silently violate.

The walk is O(sessions + unlocked achievements) with a sort per game, over data the History screen
already loads for its window. The one genuinely new query is the extent of dated unlocks, needed to
know how far back paging can go; it is a single min/max, not a full scan on each page.

### 8. Paging is bounded by evidence, not by a fixed depth

The existing 30-day window and its "load earlier days" step are kept. The floor moves from "the
first session" to "the oldest dated unlock", and once reached, the affordance disappears rather than
offering pages that no data could populate — the same rule the Analytics anchor already follows.

## Risks / Trade-offs

- **A wall of near-empty days.** A player with three scattered unlocks across 2016–2019 pages back
  through years of nothing. → Paging steps by days, not by evidence, so the steps are predictable —
  but if this bites, the correction is to step to the *next date with content* rather than to widen
  the window blindly. Noted as the likely first refinement, not pre-solved.

- **"Minutes unknown" invites a guess.** Somebody will want to divide lifetime playtime across
  evidenced days. → Refused explicitly and in the spec. A fabricated distribution would feed the
  daily chart, quest history, and Personal Pace, all of which are currently honest.

- **The reconciliation identity is only as good as its test.** It is easy to state and easy to break
  silently. → It is asserted as a property test over generated ledgers, not as one hand-built case,
  and the undated remainder is on screen, so a drift is visible rather than merely untested.

- **Re-deriving on every read repeats work across History and Home.** → A single pass over already-
  loaded flows. If it registers, the fix is caching per emission, not materializing.

- **Two kinds of day complicate a screen that just got regrouped.** `2026-08-10-regroup-history` is
  recent work. → Every existing History scenario is restated with a defined answer for the new kind,
  rather than being left to interact by accident.

- **The cloud poller already holds better data for part of this period.** Per-minute presence since
  deployment would give real session boundaries where achievements give only a date. → Genuinely
  better and genuinely out of scope: the poller's rules deny all client access, and reading it is an
  auth decision, not a UI one. Named so it is not forgotten.

# Extend History Before Tracking

## Why

The History screen begins on the day Backlogium was installed, and it says nothing at all about how
much progress a day produced. Both are gaps in the same thing — the app's account of the player's
own past — and both turn out to be answerable from data already sitting in Room.

**History drops dated evidence it already holds.** `Achievement.unlockedAt` carries Steam's
`unlocktime`, which reaches back to the account's beginning. But `groupHistory` builds its day list
like this:

```kotlin
val achievementsByDate = achievementUnlocks.groupBy { localDate(it.unlockedAt, zone) }   // grouped
val allDates = (sessionsByDate.keys + progressByDate.keys).distinct()                    // …and ignored
```

Unlock dates never contribute a day. An achievement unlocked on a day with no session and no stored
progress row is silently discarded — **not only for pre-install history, but inside the current
30-day window**. Idle-game unlocks, a friend's session on a shared machine, an unlock Steam
backdated: all vanish. That is a bug, and fixing it is most of what "history should extend beyond
the Backlogium start date" asks for.

**No day says what it was worth.** `DailyProgress` holds `minutesPlayed`, `goalMinutesPlayed`, and
`questMet`. There is no XP. And XP cannot simply be summed, because `gameXp` tapers over a game's
*cumulative* minutes — the same hour is worth more on a fresh game than on one already at its
completionist length. "XP gained today" is inherently a **marginal** quantity, and the app stores
only the running total.

The good news is that it is fully derivable. Sessions carry `(appId, startAt, minutes)`; unlocked
achievements carry `(unlockedAt, snapshotPercent)`. Replaying that ledger day by day through the
*same* `Gamification` entry points yields an exact per-day series for all of history — and
re-derives correctly after a rules edit, which a stored snapshot could not.

**And they share one hard limit, which is why they are one change.** Steam exposes no historical
playtime by date, at any endpoint. So:

| Fact about a pre-tracking day | Recoverable? | From |
|---|---|---|
| an achievement was unlocked, and when | **yes** | `Achievement.unlockedAt` |
| which game that achievement belonged to | **yes** | the same row |
| **how many minutes were played** | **no** | nothing exposes it |
| lifetime total per game | yes, but **undated** | `playtimeForever` / `backfillMinutes` |

Pre-tracking history is therefore **dated evidence of play, never reconstructed playtime** — and the
imported backfill is real XP belonging to no day at all. The XP series has the same seam: it sums to
the player's total *minus an undated remainder*. One problem, two symptoms.

## What Changes

- **Achievement unlock dates produce History days.** A date with unlocks and no sessions becomes a
  day in the list instead of being dropped. This fixes the existing drop inside the current window
  as a side effect of extending past it.
- **History reaches back before tracking began**, page by page through the same "load earlier days"
  affordance, bounded by the oldest dated unlock rather than by the first session.
- **A pre-tracking day states what it can and cannot say.** It shows the achievements unlocked and
  the games they belong to, and it presents its minutes as **unknown, never as zero** — a distinction
  the app already insists on for trophy data and must not abandon here.
- **Pre-tracking days are presentational only.** They produce no `daily_progress` row, are never
  evaluated for a quest, and never enter the streak's day sequence. Injecting 2019 into a contiguous
  day sequence would either shatter the current streak or fabricate a longer one.
- **Per-day XP, derived from the ledger.** A day's XP is the marginal amount the day's sessions and
  unlocks added, computed through `Gamification.gameXp` and `Gamification.achievementXp` — not a
  parallel formula. Derived on read; nothing new is persisted.
- **Today's XP on Home, and per-day XP on History.** Home already carries level and XP progress and
  is the right place for "what today has been worth"; History carries the same figure per day.
- **The series is reconciled to the total, not merely plausible.** The identity is stated and
  asserted:

  ```
  totalXp  =  Σ dailyXp(d)  +  undatedXp
  ```

  where `undatedXp` has exactly two named sources — imported backfill playtime, which has no date by
  construction, and unlocked achievements Steam reports with `unlocktime = 0`, which genuinely
  exist. The undated remainder is **presented**, not absorbed, so the numbers reconcile on screen.
- **A rules change re-derives the whole series.** `app-settings` already discloses that rule edits
  are retroactive; deriving on read makes past days obey that for free, where a stored per-day
  snapshot would silently keep the old rules' answer.

## Capabilities

### New Capabilities
- `untracked-history`: what history before tracking consists of, which facts are recoverable and
  which are not, how a day is produced from dated unlocks alone, the rule that unknown minutes are
  never rendered as zero, and the isolation of these days from quest evaluation and streaks.
- `daily-xp-attribution`: XP as a marginal per-day quantity, its derivation through the existing
  engine entry points, the reconciliation identity and its undated remainder, behaviour under a
  rules change, and the rule that nothing is persisted.

### Modified Capabilities
- `app-ui`: the History screen produces days from unlock dates, distinguishes a pre-tracking day
  from a tracked one, shows per-day XP, and pages back past the first session; Home shows today's
  XP.
- `playtime-backfill`: imported historical playtime is explicitly undated and is presented as such
  wherever a per-day accounting would otherwise appear to lose it.

## Impact

- **No migration, no new table, no new request.** Both halves derive from `sessions`, `achievements`,
  `games`, and `hltb_data` as they already exist. The only new query is an all-time unlock-date
  query to bound the reachable past; the existing `unlockedSince(cutoff)` already covers each page.
- **Affected code (new):** a pure day-XP derivation in `domain/`, taking sessions, unlocked
  achievements with their snapshots, per-game backfill and HowLongToBeat lengths, and a `RuleConfig`
  — the same inputs `GamificationUpdater.compute` builds, in the same shape as `LibraryXp`, which
  already splits the total per game and asserts that the parts sum to the whole. This does the same
  along the other axis.
- **Affected code (modified):** `ui/history/HistoryGrouping.kt` (the day union and a day's kind),
  `ui/history/HistoryViewModel.kt` (paging bound, XP inputs), `ui/history/HistoryScreen.kt`,
  `ui/home/` (today's XP), `data/repo/AchievementRepository.kt` (an unlock-extent query).
- **Cost is O(sessions + unlocked achievements), on read.** The same posture `add-smart-collections`
  takes, and for the same reason: a materialized series would need invalidation on every sync, every
  unlock, and every rule edit, and would be wrong between them.
- **A pre-tracking day is a new kind of row, and that is the risky part.** Every existing History
  behaviour is defined against days that have sessions. Each is restated: totals, expansion, quest
  state, thumbnails, and the empty state all need a defined answer when a day has unlocks and
  nothing else.
- **Interacts with `disclose-data-provenance`.** A pre-tracking day is the clearest case the
  provenance vocabulary exists for: observed unlock times, unknown minutes, no inference at all. If
  both are active, this change adopts those terms rather than inventing wording.
- **Interacts with `add-library-recency-signals`.** That change adds `rtime_last_played`, which would
  give a dated point for a game with no achievements at all. Useful and deliberately not depended on
  — this change works with what is already stored.

## Non-goals

- Reconstructing minutes played on any pre-tracking day. It cannot be done and must not be
  approximated.
- Creating `daily_progress` rows for pre-tracking days, or letting them affect quests, streaks, or
  the current level.
- An XP chart on Analytics. The derived series makes one trivial to add later; this change puts the
  figure where the question is asked.
- Reading the cloud presence poller's transition log, which holds real dated presence since the
  poller was deployed. It would be a genuinely better source for the period it covers, and it needs
  a client-auth decision the poller does not have.
- Changing how XP is computed. Every number comes from the existing engine entry points.

## Context

Three facts in the current code shape this.

**The sync rebuilds `games` rows rather than updating them.** `SteamSyncWorker` constructs a fresh
`Game` from the Steam DTO and copies app-owned fields back by hand — `isGoal`, `targetMinutes`,
`backfillMinutes` — with a comment recording that forgetting one previously wiped imported XP.

**XP is derived from sessions; daily progress is not.** `GamificationUpdater.compute()` builds XP
from `sessionDao.trackedMinutesByGame()` plus `Game.backfillMinutes`, so excluding a game from XP is
a matter of what is fed in. `DailyProgress.minutesPlayed`, by contrast, is a stored per-day
aggregate written incrementally by the sync from `diff.playedDeltaByAppId`. Nothing recomputes it
from sessions, and no path exists to.

**The preview/commit split already exists.** `compute()` returns a full `GamificationResult` without
writing, specifically so the settings confirmation dialog can state a concrete before/after.

Relevant standing positions: `app-settings` requires that rule changes disclose their retroactive
effect and that longest streak is never lowered by a recompute; `add-progress-events` requires every
write of derived values to declare provenance.

## Goals / Non-Goals

**Goals:**

- A hidden game is absent everywhere a game can appear, including while it is running.
- Every number the app shows is explicable from what it shows.
- Hiding is reversible with nothing lost.
- The retroactive effect is stated in concrete terms before it is applied.
- Hidden games stop costing requests.
- Removing a dozen tools takes one action, not a dozen.

**Non-Goals:**

- Deleting anything. Hiding is exclusion, never removal.
- Automatic hiding. Nothing is hidden without confirmation.
- Rewriting historical daily quest results or streaks.
- Hiding wishlist entries, which are not library games.
- A per-surface hiding model — hidden is global or it is nothing.
- Password-protecting or otherwise securing hidden games. This is tidiness and preference, not a
  security feature.

## Decisions

### 1. A standalone table, not a column

`hidden_games` is keyed by app id with no foreign key to `games`.

The column version has a specific, likely failure: the sync rebuilds each row from the DTO, so a
`hidden` flag persists only if someone remembers to copy it across alongside `isGoal` and
`backfillMinutes`. The code already carries a comment about that exact mistake having wiped
`backfillMinutes` once. A hide that silently reverts on the next sync would read as the feature
being flaky rather than as a missing line.

Omitting the foreign key is also deliberate: a game that leaves the library — refunded, delisted,
removed from a family share — should still be hidden if it returns, and a hide should not be
cascade-deleted by an event unrelated to the player's intent.

### 2. Hidden means excluded, because the alternative is unexplainable

Hiding from view while still counting produces a level, an XP total, and analytics that the visible
library cannot account for, with no surface able to explain the difference. The player cannot audit
it and neither can the app.

Exclusion is retroactive, which is the objection — but this codebase has already answered that
objection once. `app-settings` mandates disclosure of retroactive effect for rule changes, and
`compute()` exists so the disclosure can be the real number rather than an approximation. Hiding
reuses that seam exactly: run the computation with the game excluded, present the concrete before
and after, apply on confirmation.

The safety rails are already in place too. Longest streak is protected from being lowered by any
recompute, so hiding cannot erase a record.

### 3. XP is recomputed; historical days are not rewritten

This is the one place "globally excluded" is deliberately partial, and the asymmetry is principled
rather than a shortcut.

```
   XP / level          an all-time aggregate over sessions
                       → recomputed with the game excluded

   daily quest result  a dated fact about one day
   streaks             derived from those dated facts
                       → left alone
```

`DailyProgress.minutesPlayed` is a stored aggregate, not a derivation over sessions, and no path
exists to rebuild it per-game. But the stronger argument is that it should not be rebuilt: a day
the player met their quest is a day they met their quest, and a bookkeeping preference expressed
months later does not unmake it. The spec that protects longest streak already states this
principle — a record is a historical fact, and recomputing under different rules must not erase
one.

Going forward, hidden games do not contribute to daily progress at all, so the divergence does not
grow.

### 4. A hidden game that is running reads as not running

`live-status` currently resolves in-game state directly from `gameid`. When that game is hidden,
the state resolves to not-in-game — and the now-playing card, profile header presence line, Library
live indicator, and ongoing notification all follow from that single point rather than each
filtering separately.

Anything less makes the feature pointless for one of its two motives: a game hidden from the
Library that announces itself in a notification the moment it launches has not been hidden.

Sessions are still recorded while it runs, because hiding destroys nothing and unhiding must
restore everything. They are simply excluded from every derived value and every surface, like the
rest of that game's history.

### 5. Reversibility is the guarantee that makes the rest safe

No rows are deleted: not sessions, not achievements, not HowLongToBeat data, not collection
memberships. Collection membership in particular is retained and filtered on read, so unhiding
restores a game to the collections it was in rather than requiring the player to re-add it.

This is what makes a retroactive, level-lowering operation acceptable to offer. The effect is
alarming only if it is one-way.

### 6. Hidden games stop costing requests

`optimize-steam-sync` spent its entire budget getting achievement work down to what play evidence
justifies. Hidden games justify none: no achievement fetch, no schema fetch, no global percentages,
no HowLongToBeat matching, no store enrichment.

This is a genuine efficiency gain rather than an incidental one — hiding a dozen tools removes a
dozen games from every enrichment path permanently — and it exists only under exclusion. Under a
view-only filter they would remain full library members in every sense but rendering.

### 7. The app type comes from a response already fetched

`appdetails` returns each app's `type` — `game`, `application`, `tool`, `demo`, `music` — and
`StoreAppData` currently deserializes only `genres`, discarding it. Recording it alongside the genre
result adds a field to a fetch that is already scheduled and makes the bulk action possible with no
new requests.

**The bulk action suggests; it never acts.** The player is shown what would be hidden and confirms.
Store types are occasionally wrong, and a misclassified game silently vanishing — taking its XP
with it — is precisely the outcome the confirmation exists to prevent.

Items whose type has not been fetched are not offered, rather than assumed to be games or not.

### 8. Hiding a goal game clears its goal

A goal the player cannot see, whose progress no surface reports, is incoherent. Rather than
forbidding the hide or leaving a phantom goal, hiding clears the flag — disclosed in the same
confirmation as the XP effect, since it is the same kind of consequence.

Unhiding does not restore the goal flag. Re-declaring a goal is one tap, and silently reinstating a
goal the player may have moved on from is the worse default.

## Risks / Trade-offs

- **Hiding a heavily-played game visibly drops the level.** → Disclosed with real numbers before
  it is applied, reversible in full, and unable to touch the longest-streak record. The player is
  making an informed trade rather than discovering one.

- **XP moves while streaks do not, which is an inconsistency a careful user will notice.** → Named
  in the spec rather than left implicit, with the reasoning: aggregates recompute, dated facts do
  not. The alternative — rewriting months of quest history from a preference set today — is worse
  and has no implementation path.

- **The bulk action depends on store types that are sometimes wrong.** → It proposes rather than
  acts, shows exactly what would be hidden, and everything it does is reversible. Unfetched types
  are excluded from the offer entirely.

- **A hidden game running produces a silent gap** — no now-playing card, no notification, while
  sessions accumulate invisibly. → Intended. It is the difference between a hiding feature and a
  list filter, and the sessions remain for whenever the game is unhidden.

- **Hidden state is one more thing backup must carry.** → Included in export and import; without it
  a restore silently unhides everything and re-applies XP the player deliberately removed.

- **Every read path must remember to exclude.** A missed one leaks a hidden game into a list. →
  Centralised in the repository layer rather than applied per screen, so surfaces receive already-
  filtered data and cannot forget.

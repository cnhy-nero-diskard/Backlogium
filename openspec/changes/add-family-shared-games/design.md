## Context

Two facts about the current code decide most of this design.

**Sessions come from playtime diffs, not presence.** `SessionDiffer` consumes
`PollGame(appid, playtimeForever)` built from `GetOwnedGames`. A game absent from that response has
nothing to diff. `Session` also carries a foreign key to `games` with `ON DELETE CASCADE`, so no
session can exist without a game row.

**Presence is a terminal display input.** `LiveStatusRepository` resolves `gameid` and
`gameextrainfo` into a `NowPlaying.InGame`, looks up an icon from `games` if the row happens to
exist, and stops there. `LiveSessionTracker` keeps an elapsed-time state for the now-playing card
and writes no sessions.

Two further constraints:

- `CLAUDE.md`: "Two independent session detectors would produce records with disagreeing boundaries
  that cannot be deduplicated — this is a load-bearing constraint, not a stylistic preference."
- `CLAUDE.md`: the on-device engine is the sole author of derived values.

And one verified fact from a live test against a public profile: `GetPlayerSummaries` reports
nothing at all for non-Steam shortcuts — no `gameid`, no `gameextrainfo` — even when Steam itself
launched them. Family-shared games are unaffected by this, because their `gameid` is a real app id.

## Goals / Non-Goals

**Goals:**

- A shared game becomes an ordinary game everywhere it is not genuinely different.
- Play on a shared game earns XP, satisfies quests, and extends streaks.
- No game is ever subject to both session mechanisms.
- Partial coverage is disclosed rather than presented as a total.
- The source concept accommodates a third value later without rework.

**Non-Goals:**

- Non-Steam games. No Steam-side signal exists; they need the desktop agent.
- Manual playtime entry. `backfillMinutes` exists for owned games and extending it is a separate
  decision.
- Reconstructing history from before this change. There is no source for it.
- Any change to how owned games are synced, diffed, or scored.
- Distinguishing *who* shared the game. Steam does not say, and nothing here needs it.

## Decisions

### 1. Session mechanism is partitioned by source, never overlapped

The invariant forbids two detectors. It does not forbid two mechanisms, provided no game is
subject to both:

```
  source = STEAM_OWNED      playtime diffing        (existing, unchanged)
  source = FAMILY_SHARED    presence observation    (new)
```

Partitioning is what makes this safe. Deriving presence sessions for owned games as well would be
strictly worse than what exists — coarser boundaries, gaps whenever the app is closed — and would
produce two overlapping session sets for one game that no deduplication could reconcile, which is
precisely the failure the invariant describes.

Consequently `SessionDiffer` is fed only owned games, and the presence deriver only ever sees games
that have no Steam-reported playtime. The partition is a property of the wiring, not a runtime
check that could be forgotten.

### 2. Presence observations are raw; the derivation stays on the phone

The presence deriver is a pure function over observed `(appId, observedAt)` samples plus the open
session state, returning session actions — the same shape `SessionDiffer` already returns. It lives
in `domain/`, takes no Room types, and is testable as a table of observation sequences.

This keeps the engine as the sole author of derived values and mirrors the rule the cloud poller
already follows: record observations, derive nothing. It also means the deriver can later consume
observations from another sensor without changing its contract.

### 3. Admission requires three conditions, all of them cheap

A game is admitted as shared when:

1. Presence reports an app id that has no row in `games`, **and**
2. a successful sync has completed since the app id was first seen — so a game that is merely not
   yet synced is never mistaken for a borrowed one, **and**
3. the Steam store reports the app id as a game.

The second condition is the one that prevents the most likely false positive. The third prevents
the least likely but most annoying one: Family Sharing covers a whole library, including tools and
applications, and admitting a screensaver as a tracked game is the sort of thing that erodes trust
in the whole feature.

Free-to-play games cannot reach this path — `SteamApi.kt` already passes
`include_played_free_games=1`, so they are owned games and arrive through the normal sync.

### 4. Automatic admission, because the identity is real

Unlike a non-Steam shortcut, a shared game arrives with a real app id that the store can confirm.
There is no ambiguity to resolve and nothing for the player to disambiguate, so a review queue
would be a confirmation dialog with one obvious answer.

Admission is announced by a notification and reversible by removal, which together cover the case
where the automatic answer was wrong. Removal is **sticky** — an excluded app id is not re-admitted
on the next play — because a removal that undoes itself is worse than no removal at all.

*Alternative considered:* a candidates inbox, matching what non-Steam support will need. Rejected
here because the risk it manages does not exist for a store-verified app id; adopting it anyway
would add a step to every admission to prepare for a feature that will justify the step on its own
terms.

### 5. Purchase converts in place, from a fresh baseline

When an admitted shared game appears in `GetOwnedGames`, its source becomes owned and it moves onto
playtime diffing. Its existing sessions are kept.

The baseline is what matters here. `playtime_forever` for a newly purchased game includes the hours
played while borrowing, which the app has already recorded as sessions. Treating that total as a
diff would synthesize a second, enormous session covering time already counted. Instead the
conversion stores the current total as the baseline and emits no sessions, exactly as first-sync
baselining already does for a new install.

XP is unaffected either way, because XP is computed from tracked session minutes plus
`backfillMinutes` and never from `playtime_forever` — but goal progress does read
`playtime_forever`, and a phantom session would corrupt history regardless.

### 6. Coverage is disclosed, because it is genuinely partial

Presence is observed when the app is foregrounded, and continuously only under the opt-in live
monitor. A shared game played with the phone in a pocket and the monitor off produces nothing.

The app therefore knows what it *saw*, not what Steam knows, and says so wherever a shared game's
playtime is shown. This is the same honesty the existing now-playing card applies to elapsed time,
which is deliberately "not presented as exact." A total presented as complete when it structurally
cannot be would be the app's first false claim about the player's own history.

Enabling the live monitor is the actionable remedy, so the disclosure points at it rather than
merely apologising.

### 7. Achievements are presented where Steam reports them, and not asserted where it does not

Achievement progress on a borrowed game is recorded against the borrower's own account, so
`GetPlayerAchievements` is expected to answer for a shared game the player has played. If it does,
the existing achievement, rarity, rarity-XP, and rarity-standing surfaces work with no special
casing at all — they key on app id and global unlock percentages, neither of which depends on
ownership.

This is written as conditional behaviour rather than an assumption: where Steam returns
achievements they are presented as for any game, and where it does not the game is presented
without an achievement surface. That way the feature is correct whichever way the verification
lands, and a task exists to establish which.

**Verification status (task 7.1): not yet performed.** It requires a real borrowed game on a real
account, and the implementation was carried out in an environment with neither. Nothing in the
implementation depends on the answer:

- A family-shared game is included in the achievement fetch scope on both paths. Reconciliation
  already covered it (`fetchReconciliationGames` reads every `games` row); the inline sync path was
  extended to include shared games, whose freshness tier is derived from tracked session minutes
  because Steam reports no playtime for them. A newly admitted game therefore lands in the cold
  tier with no stored metadata, which the existing missing-data override picks up promptly.
- If `GetPlayerAchievements` answers, the achievement, rarity, rarity-XP, and rarity-standing
  surfaces receive its rows through the same repository and render them with no special casing:
  none of them reads a game's source, and all of them key on app id and global unlock percentages.
- If it does not answer, the game reaches those surfaces with an empty achievement list, which is
  exactly the state an owned game with no achievements reaches them in — the detail screen already
  presents no achievement surface for that case rather than an empty one.

So the outstanding work is to *record what Steam does*, not to change behaviour in response to it.
Should the answer turn out to be "Steam refuses for shared games", the correct follow-up is to stop
spending requests on them, not to add a surface — which is why that is not pre-emptively built.

### 8. `source` is an enum on `Game`, not a separate table

A shared game is an ordinary game missing one input. Giving it its own table would fork every
query, join, and repository in the app to reunite two things that differ by a single column, and
would leave collections, goals, genres, and HLTB each needing to handle two shapes.

A nullable-free enum column defaulting existing rows to owned is a widening migration with no data
movement, and every existing query keeps working untouched.

## Risks / Trade-offs

- **Presence-derived session boundaries are coarser than playtime-derived ones.** They are bounded
  by observation times, not by minute-accurate totals. → Accepted and disclosed. For a game with no
  alternative source, an approximate record is unambiguously better than none, and the app already
  declines to present elapsed time as exact.

- **A shared game played entirely while unobserved records nothing.** → The most likely
  disappointment in this change. Mitigated by disclosure that points at the live monitor, and
  unavoidable without a sensor the app does not have.

- **The store lookup needed for admission requires network.** → Admission is deferred rather than
  guessed: an unrecognised app id that cannot be verified is not admitted, and is reconsidered on a
  later observation. Nothing is admitted on incomplete information.

- **A borrowed game removed by its owner simply stops appearing.** Its row and history remain, with
  no further sessions. → Correct behaviour, and indistinguishable from a game the player stopped
  playing. No special handling.

- **Adding a second session mechanism raises the cost of any future change to session logic.** →
  Contained by keeping both mechanisms as pure functions returning the same session-action shape,
  and by partitioning at the wiring rather than inside either one.

- **`source` will need a third value for non-Steam games.** → Anticipated. The enum, the migration,
  and the surfaces that branch on it are written so that adding a value is an exhaustive-`when`
  compile error at each decision point rather than a silent default.

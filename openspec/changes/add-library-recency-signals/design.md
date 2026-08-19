## Context

- `Game` (`data/local/entity/Game.kt`) holds `playtimeForever`, `playtime2Weeks`, `lastPlaytime`,
  and `lastSyncedAt`. There is no first-seen timestamp and no last-played timestamp. `lastSyncedAt`
  is not a substitute — every game carries the same value after every sync.
- `OwnedGameDto` declares four fields. Steam returns `rtime_last_played` on the same call when
  `include_appinfo=1`, which the app already sends. The data is arriving and being dropped.
- `sessions` records `startAt`/`endAt` per synthesized session, but only for play observed since
  install. A game last played in 2019 has no session row, so sessions alone cannot answer
  "when did I last play this?"
- `backfillMinutes` is an aggregate of pre-install playtime with no dates attached — deliberately,
  since Steam exposes no historical session data.
- `steam-sync` already has a *First-sync baselining* requirement for a structurally identical
  problem: the first poll must record totals without inventing history.
- `backup-restore` merges by natural key and must not resurrect state.
- `progress-events` exists and is tempting for the acquisition banner, but its first rule is
  *"Only earned progress produces events."* Buying a game is not earned progress.
- Room schema is at version 18.
- `StreakBrokenOverlay` is the precedent for a non-modal, dismissible Home announcement: the
  surrounding `Box` has no click handling, so Home stays usable behind it.

## Goals / Non-Goals

**Goals:**

- A fresh install, and a restore from backup, produce zero badges and zero announcements.
- The three states are mutually exclusive by construction, so a card can never carry two.
- Every state has an expiry, so the Library returns to quiet on its own.
- "Never played" and "last-played date unknown" are distinguishable, never conflated.
- No new network requests.

**Non-Goals:**

- Any new sort key, filter, or query axis.
- An acquisition log or a removal signal.
- Notifications.
- Reconstructing dates for games owned before install.

## Decisions

### 1. Three nullable columns, and null means "not known" rather than "zero"

`firstSeenAt: Long?`, `lastPlayedAt: Long?`, and `returnedToPlayAt: Long?` on `games`, all nullable,
all defaulting to null in migration 18→19. The third exists for the reason set out in decision 3:
dormancy is knowable only at the instant it ends, and cannot be recovered afterwards.

Nullability is the whole baselining mechanism, not a convenience:

- **`firstSeenAt = null`** means the game was present when the app started keeping track. It is not
  new, was never new, and can never become new. Migration sets it null for every existing row, and
  the baseline poll sets it null for every game it discovers.
- **`firstSeenAt = <timestamp>`** is written only by a *non-baseline* poll encountering an app id
  not already in `games`. That is exactly "a game arrived while we were watching."

The alternative — stamping every game with the migration time and adding a separate
`libraryBaselinedAt` marker to suppress badges — needs two facts to stay consistent forever, and
gets the wrong answer the moment someone reads `firstSeenAt` without knowing the marker exists. One
nullable column cannot be misread.

`lastPlayedAt` is Steam's `rtime_last_played`, converted from epoch seconds to the epoch
milliseconds the rest of the schema uses. Null means Steam reported no value — which it does for
never-played games and occasionally for very old ones. **Never-played is determined from
`playtimeForever == 0`, not from a null timestamp**, so a game with 40 hours and no timestamp reads
"last played: unknown" rather than "never played."

### 2. Three states, one slot, resolved by precedence

The states themselves are derived, never stored — storing a *state* would mean a second author of a
derived value and a write on every sync purely to expire things. What is stored is the set of
timestamped **observations** the derivation reads. The distinction matters and decision 3 turns on
it: an observation that cannot be reconstructed later must be recorded when it happens; a state that
follows from observations by arithmetic must not be.

```
    ┌──────────────────────────────────────────────────────────────┐
    │ NEWLY_ADDED   firstSeenAt within 7 days                       │
    │               and playtimeForever == 0                        │
    ├──────────────────────────────────────────────────────────────┤
    │ NEWLY_PLAYED  first-ever recorded session for this game,      │
    │               within 7 days                                   │
    ├──────────────────────────────────────────────────────────────┤
    │ RETURNED      returnedToPlayAt within 7 days                  │
    └──────────────────────────────────────────────────────────────┘
                        ↓ precedence, highest first
              NEWLY_PLAYED > RETURNED > NEWLY_ADDED > none
```

**Precedence exists because the conditions genuinely overlap, and each overlap has an obviously
better answer.** A game bought and played the same day is *newly played* — that it is also new is
the less interesting half. A game dormant for a year that you finally started for the first time is
*newly played*, not *returned*; you never left it. `NEWLY_ADDED` sits last because it is the state
with the least information: it says only that nothing has happened yet.

**`NEWLY_ADDED` requires zero playtime**, which is what keeps the states from fighting rather than
merely ranking them. A game bought and played leaves `NEWLY_ADDED` immediately rather than being
outranked by it — so the two "new" states describe successive phases, not competing views.

**"Newly played" means the first ever recorded session, once per game, for life.** The alternative
reading — "played within the last N days" — was rejected: it would badge most of an active player's
Library simultaneously, which makes the badge describe the population rather than the exception, and
it overlaps `RETURNED` almost entirely.

**Both windows are constants, not settings.** Dormancy is 30 days; every badge lasts 7 days. A
setting here would need a preference, a backup field, and an explanation, for a threshold nobody has
an opinion about until they have lived with the default.

### 3. Dormancy is recorded when it ends, because afterwards it is gone

An earlier draft of this design had dormancy computed at read time from the `sessions` table, falling
back to "the `lastPlayedAt` value observed on the previous sync". **That fallback is not
implementable.** There is one `lastPlayedAt` column and the sync overwrites it on every poll, so by
the time anything reads it, the pre-return value — the only thing that establishes there *was* a gap
— has already been destroyed by the write that signalled the return. A pure function of stored state
cannot recover it.

The fix is to record the fact at the one moment both halves of it exist. When a poll observes a
game's playtime increase, it holds the *old* `lastPlayedAt` (about to be overwritten) and the *new*
one. If the gap between them meets the dormancy threshold, the poll records the return. Derivation
then reads a single timestamp and asks only whether it is within the badge window.

**Both the gap and the recorded timestamp are measured in event time, never in poll time.** This is
the second thing an earlier draft got wrong, and it was worse than a rounding error — it compared
`now - previousPlayAt` and wrote `returnedToPlayAt = now`, which conflates *when the player played*
with *when the app happened to find out*. Those diverge by however long the app went unsynced, which
is unbounded: a phone left off for a week, a revoked API key, airplane mode on holiday.

Two distinct defects followed:

- **Manufactured returns.** Last played Aug 1, actually played again Aug 30 — a 29-day gap, not
  dormant — but the sync only runs Sep 2. Poll-time arithmetic sees 32 days and records a return
  that never happened.
- **A badge window anchored to the wrong instant.** `returnedToPlayAt = now` starts the 7-day window
  when the *sync* ran. A sync three days late gives the badge ten days of life, and a sync ten days
  late shows a "returned!" badge for something that stopped being news a week ago.

The poll already holds the correct instant — Steam's newly reported `rtime_last_played` — so both
uses switch to it:

```
   poll observes an increase for game G
            │
            ├── previousPlayAt = max( end of G's most recent stored session,
            │                         G's stored lastPlayedAt before this poll )
            │
            ├── observedPlayAt = min( G's NEW lastPlayedAt from Steam, now )
            │                    └─ clamped: Steam's clock may lead the device's
            │
            ├── if observedPlayAt - previousPlayAt >= 30 days
            │                      ──▶  returnedToPlayAt = observedPlayAt
            │
            └── lastPlayedAt = <new value from Steam>     (safe to overwrite now)
```

Every quantity in that comparison is now an event time, including both sides of `previousPlayAt` —
a session end and a Steam timestamp are both statements about when play happened.

**A late-discovered return can be recorded already expired, and that is correct.** If the app finds
out ten days afterwards, `returnedToPlayAt` is ten days old and the badge never appears. The player
returned; the app simply missed the window in which saying so was interesting. Announcing it late
would be worse than staying quiet.

**Where Steam reports no new last-played time, the caller supplies the observation instant it does
have.** The committing path takes `observedPlayAt` as an explicit argument rather than reading a
clock, so each caller passes the best estimate available to it: a periodic poll passes Steam's
timestamp, and the post-play targeted fetch passes the session end that triggered it — which is
seconds to minutes old and therefore *more* accurate than a coarse Steam value would be. If a caller
has neither, it records no return; the failure mode stays a missing badge rather than a wrong one.

Making `observedPlayAt` a parameter is what keeps the two paths honest. A commit path that reads
`System.currentTimeMillis()` internally cannot be given a correct event time by any caller, however
much better information that caller has.

Taking the **max** of the two sources unifies what the earlier draft split into a primary path and a
fallback. A game with recorded sessions and a game whose only prior play predates the install go
through the same expression; whichever source knows more wins, and neither needs a special case.

**This is a stored observation, not a stored state.** `returnedToPlayAt` records *that a return
happened and when* — a fact about a transition, unreconstructable after the fact. Whether the game
currently *shows* the returned badge is still pure arithmetic against the badge window, so expiry
still costs no write, and the design's rule that states are never stored is intact.

**Where neither source knows anything, no badge is shown.** A game with no stored sessions and no
prior `lastPlayedAt` — possible only for a game acquired and first played between two polls — leaves
`returnedToPlayAt` null. That case is `NEWLY_PLAYED` anyway, which outranks `RETURNED`.

**The threshold is applied at write time, not at read time.** If the dormancy constant ever changes,
already-recorded returns keep the meaning they had when they were observed. That is the honest
behaviour for a fact about the past, and it is the unavoidable consequence of recording rather than
deriving.

### 4. The acquisition announcement is its own state, not a progress event

`progress-events` has exactly the delivery semantics this wants — delivered once, acknowledged,
survives process death, priority-ordered. It is also explicitly restricted to earned progress, and
its vocabulary is a closed set that presently means level-ups, quests, and streaks. Adding
"you bought something" would break the one sentence that gives that capability its meaning.

Instead: a small piece of Preferences DataStore state holding the app ids from the most recent
acquiring sync, the timestamp of that sync, and a dismissal flag. The banner shows when the batch is
non-empty, the sync is under 24 hours old, and it has not been dismissed.

**A later acquiring sync replaces the batch and clears the dismissal.** Buying more games is new
information, and a dismissal 20 hours ago was about different games. Replacement rather than
accumulation also bounds the stored state to one batch.

**The banner names up to three games and counts the rest**, so the common case (one or two games)
reads concretely and a sale reads as a number. Its action opens the Library; it does not attempt a
filtered view, since this change deliberately adds no query axis.

**Expiry is computed, not scheduled.** No worker, no alarm. The banner is absent because the
timestamp is old, which means it is correct after any period of the app being closed.

### 5. The badge is a corner glyph, and it is the same glyph everywhere

An icon in the card's top-left corner over the artwork, with the state's name in
`contentDescription`. Three Tabler glyphs: a sparkle for newly added, a play-triangle for newly
played, a rotate arrow for returned.

Icon-only is what makes the badge survive `COMPACT_GRID`, where a three-column tile has one
truncated line of text and no room for a labelled pill. A signal that vanishes at the density where
you are scanning the most games is a signal that fails when it matters.

**It does not join the density ladder.** `GameListField` governs *detail*; recency is a live-ish
signal like currently-playing, which the density requirement already exempts on exactly this
reasoning. The badge shows at every density, including the densest.

**It occupies a different corner from the selection indicator.** `TileSelectionIndicator` already
holds `Alignment.TopStart` in grid cells — the recency badge takes `TopEnd` there. On list rows it
attaches to the icon, near the existing HLTB status badge, and the two must be checked together
rather than assumed disjoint.

**It never competes with the currently-playing signal.** A game being played right now is more
important than any recency state, and the playing treatment is a border and a text colour rather
than a corner glyph — so they coexist without a rule, but the combination is worth verifying on
device.

### 6. Restore reproduces a timeline; it does not create events

An earlier draft asserted two things that cannot both hold: that the recency columns round-trip
through backup, and that a restore leaves every game with no recency state. They contradict — a
backup taken yesterday carries a `firstSeenAt` from yesterday, and a pure derivation reading it
correctly yields `NEWLY_ADDED`. There is no signal in the stored data by which the derivation could
tell "restored" from "observed", and inventing one would mean tagging every restored row.

The contradiction resolves by dropping the wrong half. **"No badges after a restore" was the wrong
requirement.** A backup is a snapshot of a timeline, and restoring it is supposed to reproduce that
timeline — including the fact that a game was acquired two days ago. The badge windows already do
the discriminating work that a suppression rule would have done clumsily:

| Backup age | Restored `firstSeenAt` | Result |
|---|---|---|
| 3 months | 3 months old | already expired — no badge, by arithmetic |
| yesterday | yesterday | badged — and correctly so; it *was* acquired yesterday |
| predates the fields | absent → null | "was already here" — no badge, ever |

What must genuinely never happen is narrower and sharper, and it survives unchanged:

- **The merge engine must not stamp `firstSeenAt` for a game it inserts.** A restore inserting 300
  games is not 300 acquisitions, and the insertion path is the same one a sync uses. This is the
  sharpest edge in the change: the natural implementation of "insert a game that isn't there" is
  exactly what must *not* set the field. It gets its own test.
- **No import produces an acquisition announcement.** The banner is tied to a *poll observing*
  previously unknown games; an import is not a poll. The batch state lives in Preferences DataStore
  and is deliberately not exported, so a restore cannot re-announce a purchase from another device
  or another week.

The distinction throughout is between recency data that a restore *carries* and recency events that
a restore *causes*. The first is reproduction and is wanted; the second is fabrication and is not.

**The accepted cost:** restoring a recent backup onto a new device reproduces badges the user
already saw on the old one. That is continuity rather than duplication — the same library in the
same state — and the alternative, suppressing signals that are still true, would make the restored
device disagree with the device it was restored from.

The first sync after a restore is *not* a baseline poll: prior playtime is stored, so games Steam
reports that the backup did not contain are genuine acquisitions and are stamped and announced
normally. That is correct, and it falls out of the existing baseline rule without a special case.

## Risks / Trade-offs

- **Dormancy is unknowable when a return is the first thing ever observed for a game.** Stated in
  decision 3; that case resolves to `NEWLY_PLAYED`, which outranks `RETURNED` anyway.
- **`returnedToPlayAt` freezes the threshold in effect when it was written.** Changing the dormancy
  constant later does not retroactively re-judge past returns. Unavoidable once the fact is recorded
  rather than derived, and recording it is forced by decision 3.
- **A recent backup restored elsewhere reproduces its badges.** Accepted in decision 6.
- **`rtime_last_played` is not contractually documented by Valve.** Absent field parses to null and
  the detail row reads "unknown", so the app degrades rather than breaks.
- **Three badges is close to the limit of a learnable vocabulary.** Mitigated by mutual exclusivity
  — one slot, one meaning at a time — and by `contentDescription`. Adding a fourth should be
  resisted.
- **Migration 18→19 on a large library.** Three nullable columns with no backfill and no index; the
  `ALTER TABLE` is O(1) in SQLite.

## Migration Plan

Migration 18→19 adds `firstSeenAt INTEGER`, `lastPlayedAt INTEGER`, and `returnedToPlayAt INTEGER`,
all nullable, all null for existing rows. No backfill: an existing library is by definition not new,
its last-played dates fill in on the next sync from Steam, and no return has been observed yet
because nothing was watching. The database is at version 18 as of this update — renumber at apply
time if another schema change lands first.

The first sync after upgrade populates `lastPlayedAt` for every game and stamps `firstSeenAt` for
none, because every app id it sees is already in `games`. An upgrading user therefore gets last-played
dates immediately and no badges — which is the intended behaviour, not an accident of ordering.

## Open Questions

None.

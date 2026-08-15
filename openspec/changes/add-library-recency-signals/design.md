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
- Room schema is at version 16.
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

### 1. Two nullable columns, and null means "not known" rather than "zero"

`firstSeenAt: Long?` and `lastPlayedAt: Long?` on `games`, both nullable, both defaulting to null in
migration 16→17.

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

The states are derived, never stored. Storing them would mean a second author of a derived value and
a write on every sync to expire things.

```
    ┌──────────────────────────────────────────────────────────────┐
    │ NEWLY_ADDED   firstSeenAt within 7 days                       │
    │               and playtimeForever == 0                        │
    ├──────────────────────────────────────────────────────────────┤
    │ NEWLY_PLAYED  first-ever recorded session for this game,      │
    │               within 7 days                                   │
    ├──────────────────────────────────────────────────────────────┤
    │ RETURNED      a session within 7 days, preceded by a gap of   │
    │               30+ days with no play                           │
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

### 3. Dormancy is computed from sessions, with `lastPlayedAt` only as a fallback

`RETURNED` needs the gap *before* the recent session, which `lastPlayedAt` cannot supply — by the
time it is read, Steam has already advanced it to the session that just happened.

So dormancy reads the `sessions` table: take the most recent session, take the one before it, and
measure the gap. Where there is no prior session — a game played before install and again now —
fall back to the `lastPlayedAt` value observed on the *previous* sync. This is the one place the
feature is imperfect, and it is worth stating plainly: for a game whose only prior play predates the
install, dormancy is knowable only if a sync happened to observe it before the return.

The fallback is best-effort and its failure mode is a missing badge, never a wrong one.

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

### 6. Restore cannot manufacture signals

Both columns round-trip through backup. On import, `firstSeenAt` is taken from the backup where
present and left null where absent, so an older export imports as "was already here" — correct,
since it was.

**The merge engine must not stamp `firstSeenAt` for a game it inserts.** A restore inserting 300
games is not 300 acquisitions, and the insertion path is the same one a sync uses. This is the
sharpest edge in the change: the natural implementation of "insert a game that isn't there" is
exactly what must *not* set the field. It gets its own test.

The acquisition batch lives in Preferences DataStore and is not exported. A restore should not
re-announce a purchase from another device or another week.

## Risks / Trade-offs

- **Dormancy is unknowable for pre-install play with no prior sync observation.** Stated above;
  fails toward a missing badge.
- **`rtime_last_played` is not contractually documented by Valve.** Absent field parses to null and
  the detail row reads "unknown", so the app degrades rather than breaks.
- **Three badges is close to the limit of a learnable vocabulary.** Mitigated by mutual exclusivity
  — one slot, one meaning at a time — and by `contentDescription`. Adding a fourth should be
  resisted.
- **Migration 16→17 on a large library.** Two nullable columns with no backfill and no index; the
  `ALTER TABLE` is O(1) in SQLite.

## Migration Plan

Migration 16→17 adds `firstSeenAt INTEGER` and `lastPlayedAt INTEGER`, both nullable, both null for
existing rows. No backfill: an existing library is by definition not new, and its last-played dates
fill in on the next sync from Steam.

The first sync after upgrade populates `lastPlayedAt` for every game and stamps `firstSeenAt` for
none, because every app id it sees is already in `games`. An upgrading user therefore gets last-played
dates immediately and no badges — which is the intended behaviour, not an accident of ordering.

## Open Questions

None.

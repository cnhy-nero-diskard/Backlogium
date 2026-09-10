# Hidden Games

## Why

A Steam library is not a list of games. `GetOwnedGames` returns applications, tools, utilities, and
playtests alongside them — SteamVR, Wallpaper Engine, benchmark suites, engine tooling. The app
treats every one as a game: they occupy the Library, dilute averages, appear in collections and
completion figures, and consume request budget fetching achievements they do not have.

There is a second, smaller reason. Some games a player simply does not want to see, and an app that
renders a personal library on a phone should be able to honour that.

Both want the same thing, and the word for it is *invisible* rather than *filtered*. A game hidden
from lists while still contributing to XP, level, and analytics produces numbers that no surface can
explain — the level says one thing, the visible library accounts for another, and there is nowhere
to go to reconcile them. That is not a smaller feature than exclusion; it is a worse one.

Excluding a played game from derived values is retroactive, and this app already knows how to
handle that. `app-settings` requires that rule changes disclose their retroactive effect, and
`GamificationUpdater.compute()` exists so a candidate change can be evaluated without committing
it. Hiding is the same shape as changing an XP rate: preview the concrete effect, confirm, apply.

## What Changes

- **Hiding makes a game globally invisible and globally excluded.** It leaves the Library, search,
  collections, smart collections, analytics, history, and game detail, and it stops contributing to
  XP and level.
- **A hidden game that is being played reads as not playing.** The now-playing card, the profile
  header, the live indicator, and the ongoing notification all present no game. A hiding feature
  that names the hidden game the moment it launches is not one.
- **Hiding destroys nothing.** No rows are deleted — not sessions, not achievements, not collection
  memberships. Unhiding restores the game exactly as it was, XP included.
- **The retroactive effect is disclosed before it is applied**, stating the concrete XP and level
  before and after, computed rather than estimated.
- **Historical days are not rewritten.** XP is an all-time aggregate over sessions and is
  recomputed; daily quest results and streaks are dated facts about days that happened and are
  left alone. A day the player met their quest remains a day they met their quest.
- **Hidden games stop consuming request budget** — no achievement fetches, no schema fetches, no
  HowLongToBeat matching, no store enrichment.
- **A bulk action for non-games**, offered from the store's own app type: *"these 12 items in your
  library are applications or tools — hide them?"* Reviewed and confirmed, never automatic.
- **A hidden-games list in Settings**, with individual and bulk unhiding. A game that can be hidden
  and not found again is a trap.
- **Hiding a goal game clears its goal flag**, disclosed alongside the rest of the effect.

## Capabilities

### New Capabilities
- `hidden-games`: what hiding means, what it excludes and what it deliberately does not, its
  reversibility guarantee, how its retroactive effect is disclosed, how a hidden game's presence is
  treated, how hidden games are excluded from remote work, and how non-game items are identified
  and offered in bulk.

### Modified Capabilities
- `live-status`: the in-game state resolves to not-in-game when the running game is hidden.
- `app-settings`: a hidden-games section listing what is hidden, with unhiding, and the bulk
  non-game action.
- `app-ui`: hiding is reachable from a game's own surface, and hidden games are absent everywhere
  a game would otherwise appear.

## Impact

- **A standalone `hidden_games` table**, keyed by app id with no foreign key to `games`.
  `SteamSyncWorker` **rebuilds** each `games` row from the Steam DTO and manually copies app-owned
  fields back — a `hidden` column would silently reset on every sync unless a line is remembered,
  which the code's own comment about `backfillMinutes` records having already happened once. A
  separate table cannot be clobbered by a rebuild it is not part of, and it lets a hide outlive a
  game temporarily leaving the library.
- **Affected code (new):** the `hidden_games` table, DAO, and migration; a repository exposing the
  hidden set; a hide-effect preview built on `GamificationUpdater.compute`; the settings section;
  the non-game bulk action.
- **Affected code (modified):** `GamificationUpdater` (exclude hidden games from the XP input);
  `SteamSyncWorker` (exclude hidden games from daily-progress attribution going forward, and from
  enrichment scheduling); the achievement tiering and HowLongToBeat batch paths (skip hidden);
  `LiveStatusRepository` (resolve hidden to not-in-game); Library, search, collections, smart
  collections, analytics, and history read paths.
- **The store enrichment additionally records each app's type**, which the same `appdetails`
  response already carries and the app currently discards. No new request is made to support the
  bulk action.
- **A new `RecomputeSource` value.** `add-progress-events` requires every write of derived values to
  declare provenance; hiding is not earned, so it emits no events and reseeds the baseline —
  including downward. Without that, hiding a large game would leave a stale high-water mark and
  suppress the next genuine level-up. `auditfix-session-ledger-integrity` (#104) added
  `RecomputeSource.GAME_REMOVAL` for the same reason on Family Shared removal, named for the event
  rather than a generic non-earned catch-all — this change follows that pattern with its own
  distinctly-named source for hide/unhide rather than widening `GAME_REMOVAL` to cover both.
- **Backup and restore carry the hidden set**, or a restore would silently unhide everything.
- **Nothing is deleted, so nothing needs a migration path back.** The reversibility guarantee is
  what makes a retroactive effect safe to offer.
- **No network, no cloud, no permission.** The app type comes from a fetch already scheduled.

# Family-Shared Games

## Why

A game played through Steam Family Sharing appears in the now-playing banner and then vanishes.
It is in no library list, has no game detail, earns no XP, contributes to no quest or streak, and
appears in no analytic. Hours of real play leave no trace anywhere in an app whose entire purpose
is that hours of play leave a trace.

The cause is structural rather than cosmetic. There are two paths into the app and only one of them
feeds anything:

```
GetOwnedGames ──▶ playtimeForever ──▶ session synthesis ──▶ XP, quests, streaks, analytics
GetPlayerSummaries ──▶ gameid ──▶ now-playing banner ──▶ nothing further
```

A borrowed game is absent from `GetOwnedGames`, so it has no playtime to diff, so it produces no
sessions — and `Session` carries a foreign key to `games`, so it cannot produce one even in
principle. Presence is a display input; playtime is the derivation input; the two never meet.

What makes this worth doing now is how little is actually missing. A shared game's `gameid` is a
**real Steam app id**. Its name, artwork, genres, store metadata, and HowLongToBeat length are all
reachable exactly as they are for an owned game, because none of those lookups check ownership.
The only thing Steam will not give is `playtime_forever`.

So this is not a new class of second-rate game needing a parallel set of reduced surfaces. It is an
existing kind of game missing exactly one input — and the app already observes that input every
time it resolves presence.

## What Changes

- **Games carry a source**: owned from Steam, or shared through Family Sharing. Every existing
  surface treats them alike except where the source genuinely changes what is true.
- **A shared game is admitted automatically** the first time it is observed in presence, once a
  successful sync has confirmed it is genuinely not owned and the Steam store confirms the app id
  is a game. It arrives with its real name, artwork, and genres.
- **Sessions for shared games are derived from observed presence**, since no playtime exists to
  diff. Once stored they are ordinary sessions: they earn XP, satisfy quests, extend streaks, and
  appear in history and analytics like any other.
- **Playtime diffing stays the sole session source for owned games.** The two mechanisms are
  partitioned by source and never both apply to one game — two session detectors on one game would
  produce overlapping records with disagreeing boundaries.
- **Coverage is stated, not implied.** Presence is only observed while the app is foregrounded or
  the opt-in live monitor is running, so a shared game's tracked time is what the app saw, not
  what Steam knows. Surfaces say so rather than presenting partial totals as complete.
- **Removal is available and sticky.** A removed shared game does not re-admit itself the next time
  it is played, and removals can be reversed from Settings.
- **Buying the game converts it in place.** When a shared game appears in `GetOwnedGames` it becomes
  owned, keeps its history, and moves to playtime diffing from a fresh baseline.
- **A notification when a new shared game is detected and added**, so admission is never silent.
- **Achievements where Steam provides them.** Achievement progress on a borrowed game is recorded
  against the player's own account, so the existing achievement, rarity, and rarity-standing
  surfaces are expected to work unchanged — and where Steam reports nothing, the game is simply
  presented without an achievement surface.

- **Manual import and Steam-data probe in Settings.** A player may paste a Steam Store URL or app
  id. Backlogium checks the current owned-games response, verifies the Store app is a game, imports
  an eligible unowned title as Family Shared, and reports whether Steam returned per-player
  achievement data. It never presents absent borrowed-game playtime as a Steam total.

## Capabilities

### New Capabilities
- `game-sources`: what sources a tracked game may come from, how a played-but-unowned game is
  identified and admitted, which session mechanism applies to which source, how removal and
  re-admission behave, and how a shared game converts when it is later purchased.

### Modified Capabilities
- `steam-sync`: session synthesis by playtime diffing is scoped to games that have Steam-reported
  playtime, so it cannot also apply to a game whose sessions come from presence.
- `app-ui`: game detail and Library indicate a game's source; the Analytics screen distinguishes
  shared games; a notification announces a newly admitted game.
- `app-settings`: manual Family Shared import/data probing plus a section listing removed shared
  games, from which a removal can be reversed.

## Impact

- **No dependency on `add-desktop-agent`.** Shared games are launched through Steam and reported by
  Steam, so this needs no new sensor and no software on any other machine. It is entirely
  buildable today, unlike non-Steam support, which has no Steam-side signal at all.
- **Affected code (new):** a `source` column on `Game` with its migration; a table of excluded app
  ids; a presence-to-session deriver in `domain/`, pure and JVM-testable; store metadata lookup for
  admission.
- **Affected code (modified):** `LiveStatusRepository` (admission on an unrecognised app id),
  `SteamSyncWorker` and `SessionDiffer` wiring (partition by source), `GameDao`/repositories, game
  detail, Library, Analytics, and the settings surface.
- **A Room migration** adding `Game.source`, defaulting existing rows to owned — a widening with no
  data loss and no rewrite of existing rows.
- **The single-detector invariant is preserved deliberately.** `CLAUDE.md` states that two
  independent session detectors produce records with disagreeing boundaries that cannot be
  deduplicated. This change adds a second detector and partitions it by source so that no game is
  ever subject to both.
- **The on-device engine remains the sole author of derived values.** Presence observations are raw
  facts; the derivation into sessions happens on the phone, as it does today.
- **No cloud, no Firestore, no new permission.** The presence data used here is already fetched by
  the existing live-status path.
- **Non-Steam games are explicitly out of scope.** They produce no Steam presence at all — verified
  against a live profile — so they need a different sensor entirely. The `source` concept
  introduced here is designed to admit a third value later without rework.

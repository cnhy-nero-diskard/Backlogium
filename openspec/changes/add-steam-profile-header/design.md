# Design — Steam profile header

## Context

`ISteamUser/GetPlayerSummaries/v2` is called from two places today:

- `LiveStatusRepository.fetch()` — every 30s, but only while something collects `nowPlaying`
  (Home, via `stateIn(WhileSubscribed)`).
- Nothing else. `SteamSyncWorker` calls `GetOwnedGames` and `GetSteamLevel`, not summaries.

`PlayerSummaryDto` deserializes four fields (`steamid`, `gameid`, `gameextrainfo`,
`personastate`) out of a response that also contains `personaname`, `avatarfull`, `avatarmedium`,
`profileurl`, and `lastlogoff`. Because `kotlinx.serialization` is configured to ignore unknown
keys, adding fields is purely additive.

The `app-ui` spec requires that "all screens display the last synced state and never block on a
network call". A header driven only by the live poll would render empty on a cold offline launch
— so identity must be persisted, not merely fetched.

## Goals / Non-Goals

**Goals:**
- Identity visible at the top of every top-level screen, from local state, offline.
- Zero additional network calls.
- Live in-game state reflected while the app is open.

**Non-Goals:**
- Steam level in the header (see proposal Non-goals), profile editing, friends data.

## Decisions

- **Persist persona name + avatar URL on `PlayerProfile`; do not treat identity as transient.**
  `PlayerProfile` is already the single row holding cross-cutting player aggregates (XP, level,
  streaks, `lastSyncAt`), so identity belongs there rather than in a new table or in DataStore.
  *Why:* satisfies the offline requirement, and the header then has exactly one source of truth
  regardless of whether a live poll is running. *Alternative rejected:* holding identity only in
  `LiveStatusRepository`'s in-memory flow — the header would be blank on every cold launch and
  would flicker in as the first poll returned.

- **`SteamSyncWorker` becomes the writer; the live poll is a read-through refresher.** The
  periodic sync gains a `GetPlayerSummaries` call and persists persona/avatar. The 30s live poll,
  which already fetches the same payload, updates the same row when the values differ.
  *Why:* the sync is the established persistence path and runs even when no screen is open; the
  live poll keeps the header current within a session without owning persistence. Avatars change
  rarely, so a redundant write is cheap and idempotent.

- **Persona state is derived, not persisted.** Online/away/in-game is a live signal with the same
  character as `NowPlaying` — stale "Online" text after two days offline would be a lie. The
  header shows a persisted-identity + live-state split: name and avatar from Room, state from
  `LiveStatusRepository`, falling back to a neutral presentation when no poll is active.
  *Why:* mirrors the existing decision that `NowPlaying` is never persisted.

- **`topBar` on the shell `Scaffold`, not a per-screen composable.** `BacklogiumAppRoot` already
  owns the `Scaffold` and `bottomBar`; the header goes in `topBar` so it survives navigation
  without each screen re-declaring it, and `innerPadding` (already applied to the `NavHost`)
  handles the layout offset with no per-screen change.
  *Why:* "tippy top of the screen" means the shell, not Home. *Cost accepted:* ~56dp of vertical
  space on Library/History too.

- **Hidden while unconfigured.** When `CredentialsState` is not `Configured`, `topBar` renders
  nothing. *Why:* Home already replaces itself with the onboarding takeover, and a skeleton
  avatar above a "connect your account" flow is noise. This also avoids a flash of empty header
  during the `loading` state.

- **The live poll's ownership question is deferred.** Making `LiveStatusRepository` emit identity
  as well as `NowPlaying` widens its contract slightly, but the poll remains
  observation-scoped and foreground-only *in this change*. `enhance-now-playing` is what moves
  the poll into a foreground service; sequencing that change second means this one does not
  have to anticipate it. The header will simply pick up whatever produces the live state.

## Risks / Trade-offs

- **Vertical space on every screen** — accepted; the header is a single slim row, and Library's
  `LazyColumn` already scrolls.
- **Avatar load failures** — Steam's CDN can 404 after an avatar change. Reuse the existing
  `SubcomposeAsyncImage` loading/error pattern from `GameIcon`, with a themed initial/glyph
  fallback so the header never collapses.
- **A migration for two nullable strings** — unavoidable, but additive and defaulting to null,
  so pre-migration installs render the fallback until the next sync.

## Migration Plan

`BacklogiumDatabase` v4 → v5, additive only:

```sql
ALTER TABLE player_profile ADD COLUMN personaName TEXT;
ALTER TABLE player_profile ADD COLUMN avatarUrl TEXT;
```

Both nullable with no backfill. The next sync (or live poll) populates them; until then the
header shows the fallback avatar and the SteamID-derived label.

> If `enhance-game-detail` lands in the same release, fold its columns into a single v5 migration
> rather than shipping two consecutive versions. (`enhance-library` needs no migration.)

## Open Questions

- Should tapping the header do anything (scroll Home to top, open the Steam account card)? Left
  inert for now; a no-op tap target is worse than none.

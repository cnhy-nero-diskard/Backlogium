# Game detail: summary and achievement legibility

## Why

`GameDetailScreen` is named for a game but shows only a flat achievement list — no information
about the game itself, despite the app holding plenty: playtime, tracked-vs-imported split, all
four HowLongToBeat lengths, completion percentage, XP contributed, session history. A player
arriving from the Library sees achievements and nothing else.

The achievement list has three gaps of its own. It is unordered — whatever order Room returns — so
neither "what did I just unlock" nor "what are my rarest" is answerable. Each row shows only a
name plus a status line, omitting the achievement's description, which is often the only thing that
explains what an achievement *is*. And a row can say "Legendary · +250 XP" without ever saying *how
rare* that is — the tier name is the app's own vocabulary, while the underlying unlock rate is the
fact behind it. Both the description and the rate are already available: `GetSchemaForGame` returns
the description alongside the `displayName` and `icon` the app stores, and the unlock rate is already
persisted on every achievement row.

## What Changes

- A **game summary section** above the achievement list: game art, playtime, HowLongToBeat
  lengths, completion percentage, and the game's XP contribution.
- **Sorting** for the achievement list: by date achieved or by rarity.
- **Achievement descriptions** shown beneath each achievement's name.
- **The unlock rate on each row** — "0.8% of players have this" — so the rarity tier is backed by
  the number that produced it.

## Capabilities

### Modified Capabilities
- `app-ui`: the game detail screen gains a summary section, an achievement sort control, and
  per-achievement descriptions.
- `steam-achievements`: the achievement schema fetch retains each achievement's description.

## Impact

- **Affected code (new):** a summary section composable, a sort control, sort logic.
- **Affected code (modified):** `AchievementSchemaDto` gains `description`; `Achievement` entity
  gains a `description` column (additive migration); `AchievementMerge` carries it through;
  `GameDetailViewModel`/`GameDetailScreen`.
- **No new network calls.** `GetSchemaForGame` already returns descriptions, and the unlock rate is
  already stored on every achievement row (`snapshotPercent` / `globalPercent`) — the row simply
  never displays it.
- **Existing rows are not backfilled.** Descriptions populate as games are naturally re-synced;
  rows without one render name-only until then.

## Non-goals

- **Tabs or a separate achievements route.** The summary is a header section on the same
  scrolling screen, so the achievement list — the thing most often wanted — stays one glance away.
- **A forced re-fetch of every game's schema** to populate descriptions immediately. That is one
  `GetSchemaForGame` call per owned game with achievements, for cosmetic text. Lazy fill instead.
- **Revealing Steam's hidden achievements.** Steam withholds descriptions for achievements flagged
  hidden; those are labelled as hidden rather than worked around.
- **Persisting the chosen sort** across visits.
- **Session history on the summary.** Playtime totals only; a per-game session list belongs with
  the History screen's concerns.

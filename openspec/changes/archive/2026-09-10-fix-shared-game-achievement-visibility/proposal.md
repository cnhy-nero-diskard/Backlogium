## Why

Family-shared games get worse achievement coverage than owned games in two concrete, connected
ways. First, importing a shared game by pasting its Steam link tells the player its achievements
were found and tracked, but the detail screen shows an empty achievement list — the import probes
Steam only to build that message, then discards the result instead of persisting it. Second, a
shared game that was completed before Backlogium ever observed a play session for it (no local
tracked minutes) is permanently excluded from achievement fetching by the tiered-refresh rule that
treats zero *locally tracked* playtime as "never played" — a rule that is a safe proxy for an owned
game, whose `playtimeForever` comes straight from Steam, but is not a safe proxy for a shared game,
whose local tracked minutes structurally undercount real play (already acknowledged by
`game-sources`' "disclosed as observed, not total"). The practical symptom the player sees is a
100%-completed shared game (e.g. God of War Ragnarok) never appearing in the Completed derived
collection, because its achievement data was never fetched at all.

## What Changes

- Manual paste-link import (`FamilySharedGameRepository.importManually` /
  `probePlayerData`) persists the achievement data it fetches from Steam through the same
  merge/persist path normal sync uses, instead of reducing it to an in-memory count for a toast.
- A family-shared game's eligibility for achievement fetching no longer depends on locally tracked
  playtime being nonzero. Admitting a family-shared game — whether by manual import or by automatic
  presence-based admission — triggers a one-time achievement fetch for it regardless of tracked
  minutes.
- A bounded backfill pass identifies family-shared games already in the library with no stored
  achievement sync data (including ones currently stuck in the tiered refresh's "never" bucket
  purely because they show zero tracked local playtime) and makes them eligible for fetching.
- No change to how the Completed (or any other) derived collection computes membership — its
  achievements-first / playtime-fallback rule, including treating "not yet fetched" as unknown, is
  already correct. This change fixes the upstream data gap that was causing shared games to sit
  at "not yet fetched" indefinitely.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `game-sources`: "Manual admission preserves source safety" gains the requirement that the
  post-import achievement probe persists real achievement rows and sync metadata, not just a
  diagnostic count.
- `steam-achievements`: "Tiered achievement refresh" is amended so a family-shared game's tier
  eligibility is not gated on locally tracked playtime alone; family-shared games become eligible
  for an immediate one-time fetch on admission and for a bounded backfill pass if already admitted
  without stored achievement data.

## Impact

- `app/src/main/java/com/example/backlogium/data/repo/FamilySharedGameRepository.kt` — route
  `probePlayerData`'s result through `AchievementRepository`'s persistence path; trigger fetch on
  both manual and automatic admission.
- `app/src/main/java/com/example/backlogium/data/achievement/AchievementFreshness.kt` — tier
  selection must stop excluding family-shared games from fetch eligibility on the basis of zero
  local tracked playtime.
- `app/src/main/java/com/example/backlogium/data/repo/AchievementRepository.kt` — expose or reuse
  `applyRefresh` for a single-game, source-agnostic fetch-and-persist call usable outside the
  library-wide sync.
- Backfill: a one-time pass (WorkManager one-off job or startup check) over existing family-shared
  games with no `GameAchievementSync` row.
- No change to `domain/SmartCollections.kt` or the smart-collections spec — verified its Completed
  rule already treats missing achievement data as unknown, not as "no achievements."

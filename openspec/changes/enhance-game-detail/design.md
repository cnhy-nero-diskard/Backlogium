# Design — Game detail: summary and achievement legibility

## Context

`GameDetailViewModel` combines `gameRepository.library`, `achievementRepository.observeForGame(appId)`,
and `settings.ruleConfigFlow`, and maps each `Achievement` to an `AchievementUi` carrying tier and XP.
Tier and XP already come from `snapshotPercent` — the percent captured at first observed unlock —
never the live `globalPercent`, per the add-steam-achievements rarity-drift policy.

Two fields matter for sorting and are already persisted:

- `unlockedAt: Long?` — set when unlocked, null when locked.
- `snapshotPercent: Double?` — set at first unlock, **null while locked**.
- `globalPercent: Double?` — refreshed every sync, present regardless of unlock state.

So a rarity sort cannot use `snapshotPercent` alone: locked achievements would all sort as unknown.
`globalPercent` is the only rarity signal available for locked achievements.

`AchievementSchemaDto` maps `name`, `displayName`, `icon` from a response that also carries
`description` and a `hidden` flag. Adding fields is additive (unknown keys are ignored).

The three fields above live on the `Achievement` entity, but the ViewModel doesn't read that entity —
it reads `AchievementRepository.observeForGame`, which returns `GameAchievement`, a collapsed DTO
exposing only `apiName`, `displayName`, `iconUrl`, `unlocked`, and `rarityPercent` (deliberately just
the frozen `snapshotPercent`, per the rarity-drift policy). It has no `unlockedAt` and no
`globalPercent`. Both are needed here — `unlockedAt` for the date sort, `globalPercent` as the
fallback for locked rows' rarity sort/display — so `GameAchievement` and its mapper need widening
before sorting/display logic can be written; this is not just a ViewModel-side join.

## Goals / Non-Goals

**Goals:**
- Game context on the game screen.
- Answer "what did I just unlock" and "what are my rarest".
- Explain what each achievement is.

**Non-Goals:**
- Tabs, forced re-fetch, revealing hidden achievements, persisted sort, session history.

## Decisions

- **Summary is a header section on the existing scrolling screen, not a tab or a separate route.**
  Rendered as `item {}` blocks above the achievement list in the existing `LazyColumn`.
  *Why:* no navigation change, no new route, no per-tab scroll state, and the achievement list stays
  immediately reachable. *Alternative rejected:* a `TabRow` (cleaner separation, but adds a tap to
  the most-used content and a second spec surface) and a Summary-as-landing route (adds a nav hop to
  the thing the screen is currently *for*).

- **Summary content is limited to what the app already holds, joined read-side.** Game art,
  `playtimeForever`, the tracked-vs-imported split (`backfillMinutes` vs summed session minutes), all
  four HLTB lengths, achievement completion (`unlocked / total`), and the game's XP contribution
  (same derivation as the Library badge).
  *Why:* no new network calls and no new persistence.

- **`LibraryGame` gains the two HLTB lengths it currently drops.** `Game.toDomain()`
  (`GameRepository.kt`) maps only `completionistMinutes` and `mainStoryMinutes` from `HltbData` into
  `LibraryGame`; `mainExtraMinutes` and `allStylesMinutes` are dropped even though the cached
  `HltbData` row already carries all four. Extend `LibraryGame` with both fields and carry them
  through `toDomain()`, rather than having `GameDetailViewModel` read `HltbRepository`/`HltbData`
  directly.
  *Why:* `gameRepository.library` is already the one join every screen reads for HLTB data; adding a
  second, game-detail-only read path for two of the four lengths would leave two places that can
  drift. `LibraryScreen` doesn't need the extra two fields today but carrying them costs nothing.

- **The XP contribution reuses `LibraryXp.contribution(input: GameXpInput, cfg: RuleConfig): Int`
  (`domain/LibraryXp.kt`) directly — it already exists from `enhance-library`, nothing to extract.**
  `GameDetailViewModel` builds one `GameXpInput` (tracked + backfill minutes, completionist minutes,
  unlocked rarity snapshots) for the current game and calls it, the same way `LibraryViewModel` does.
  *Why:* `enhance-library` already shipped the shared derivation; the risk this change originally
  flagged (writing the formula twice) doesn't apply — importing is the whole task.

- **Rarity sort uses `snapshotPercent` when present, falling back to `globalPercent`.** Unlocked
  achievements sort by the percent that actually determined their XP; locked ones sort by the live
  global percent, which is the only rarity signal they have.
  *Why:* using `globalPercent` for unlocked achievements too would rank them by a number that
  disagrees with the XP shown on the same row. The fallback is explicitly a *display* concern and
  does not touch the rarity-drift policy, which governs XP only.

- **The displayed unlock rate is the percent that produced the row's tier.** Unlocked rows show
  `snapshotPercent`; locked rows, which have none, show `globalPercent`. This is the same
  `snapshotPercent ?: globalPercent` rule the rarity sort uses, so what is displayed, what is sorted
  on, and what earned the XP are all one number.
  *Why:* showing the live percent on unlocked rows would routinely contradict the tier beside it — a
  0.8%-at-unlock achievement now sitting at 6% would read "6% of players · Legendary", which looks
  like a bug and invites someone to "correct" the tier. *Alternative deferred:* showing both when
  they diverge ("6.0% now · 0.8% when you unlocked it") is the most informative option and makes the
  rarity-drift policy visible, but it adds a second line to rows that are simultaneously gaining a
  description. Worth revisiting once the row's density is known in practice.

- **Locked achievements group last in both sort modes.** In date order they have no date; in rarity
  order their percent is a different kind of signal (how rare it *is* vs how rare *yours* was).
  *Why:* interleaving null-dated rows into a date sort produces an arbitrary result that looks like
  a bug. Unlocked first, sorted; locked after, sorted by the same key.

- **Default sort is date achieved, descending.** *Why:* the most common question on opening the
  screen is "what did I just get". Rarity is the deliberate, opt-in view.

- **Sort is transient view state, not persisted.** A `remember`ed value in the composable (or
  ViewModel state), reset per visit. *Why:* it is a lens, not a preference; persisting it needs a
  DataStore key and a settings surface for a control that costs one tap.

- **Descriptions fill lazily; no forced re-fetch.** The new column is nullable. Rows written before
  the migration keep `null` until their game's next natural schema fetch. The UI renders name-only
  for those.
  *Why:* forcing population means one `GetSchemaForGame` call per owned game with achievements —
  a heavy, rate-limit-adjacent sweep for cosmetic text. *Consequence, accepted:* descriptions appear
  unevenly for a while, and the freshness gate means some games wait a long time. If that proves
  annoying, a manual "refresh descriptions" action is the follow-up — deliberately not shipped here.

- **Steam's `hidden` flag is respected, and hidden-but-unlocked achievements still show their
  description.** Steam withholds descriptions for hidden achievements the player has not unlocked;
  for those the UI shows a "Hidden achievement" label rather than blank space. Once unlocked, Steam
  supplies the description and it is shown normally.
  *Why:* a blank line reads as a bug; naming the state explains it. Requires persisting the `hidden`
  flag alongside the description.

## Risks / Trade-offs

- **Duplicated XP derivation** across this change and `enhance-library` — resolve by extracting a
  shared function the first time both need it, not by copying.
- **Uneven descriptions** — the accepted cost of lazy fill; make sure a missing description renders
  as absence rather than as an error or an empty bubble.
- **A denser screen** — the summary pushes achievements below the fold. Keep it compact enough that
  the first achievement row is visible or nearly visible on a typical phone.
- **`snapshotPercent` / `globalPercent` divergence** — a rarity sort can order two achievements by
  different signals. Only visible in mixed locked/unlocked lists, and the locked-group-last rule
  confines it to within-group ordering.

## Migration Plan

`BacklogiumDatabase` → next version, additive only:

```sql
ALTER TABLE achievements ADD COLUMN description TEXT;
ALTER TABLE achievements ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
```

No backfill. Existing rows render name-only until re-fetched. `hidden` defaults to 0 (not hidden),
which is the correct assumption for already-visible achievements.

Current schema version is 6 (`MIGRATION_5_6`, `add-backup-restore`). `add-steam-profile-header`
already shipped at v4→5 — there is nothing pending to combine with. No other open proposal has a
schema change (`add-rarity-standing` and `hltb-inline-picker` both say explicitly they don't). This
change is `MIGRATION_6_7`, targeting version 7, on its own.

## Open Questions

- Should the summary include a goal toggle, so the game can be tagged without going back to the
  Library? Reasonable, but it duplicates the 3-dot dialog's job — deferred.

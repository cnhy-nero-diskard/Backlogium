## Context

The only grouping mechanism today is a single binary `Game.isGoal` tag, which splits the Library into
"Focus" and "Your games." It carries no intent — no deadline, no order, no completion goal. Backlogium
already stores every signal a meaningful group needs: per-game HowLongToBeat completion lengths (cached for
the whole library), achievement rows with rarity snapshots, playtime, and the gamification engine's
`goalProgress`. The Home screen is already a derived dashboard (level, XP, quest, streak, now-playing),
rendered from local state — a natural surface for mission cards.

Two architecture precedents shape this design:

1. **The `:gamification` module is pure** — no clocks, no I/O, no persistence; callers supply inputs and
   render outputs. Plain-JVM unit tests lock the math. The app has analogous pure domain classes
   (`LibraryXp`, `LibrarySorting`, `LibrarySortKey`) in `:app/domain/`.
2. **The `enhance-library` change (2026-07-28) explicitly rejected** a second promotion tier above the tag
   and "bulk tagging," because they stacked on the binary and added ways for sync to drop flags. That
   rejection was against *stacking*; collections are a *different kind of thing* — intent-bearing,
   Home-surfaced, independent of the tag.

A key invariant from `enhance-library`: each `appId` is in exactly one Library section, and a duplicate key
crashes Compose. Collections do not touch that invariant — they are a separate, additive layer.

## Goals / Non-Goals

**Goals:**
- Let users define named game groups with intent (mode), surfaced on Home as mission cards.
- Derive every banner value from signals already stored per game — no engine change, no new network calls.
- Keep the derivation pure and plain-JVM-testable, mirroring the `:gamification` stance.
- Stay migration-light: no alteration to existing tables, the Focus tag, the engine, or the nav contract.

**Non-Goals:**
- Derived/automatic membership rules, Focus-tag migration, gamification integration beyond read-side
  derivation, more than four modes, a Collections tab, per-game deadlines, deadline feasibility inference.

## Decisions

- **Additive, not a migration of the Focus tag.** Collections coexist with `isGoal`. `QuestMode.GOAL_ONLY`,
  `DailyProgress.goalMinutesPlayed`, `observeGoalGames()`/`observeBacklog()`, and the Library split are
  untouched; a game may be Focus and in a collection independently.
  *Alternative considered:* seed a default Focus collection from every `isGoal=1` game on upgrade (the
  "migrate into" option). Rejected for the first slice — it touches `SteamSyncWorker.persistPoll` and the
  Library sections, and is a separate scoping decision best made after collections exist.

- **Collections surface on Home, not a new tab.** Home is already a derived dashboard; a collections section
  is another card of the same kind. The four-tab `app-ui` nav contract is unchanged; the management screen
  is a pushed sub-destination like GameDetail and HltbReview.
  *Alternative:* a fifth "Collections" tab. Rejected — changes the nav contract and collections read better
  as mission cards on the existing dashboard than as a browsing surface.

- **Derivation is pure classes in `:app/domain/`, not a separate Gradle module.** Follows the
  `LibraryXp`/`LibrarySorting` precedent. `:gamification` is a separate module because it is a reusable,
  stable rules engine; collections are an app feature whose derivation is pure but app-specific.
  *Alternative:* a `:collections` module. Rejected for now; extractable later if reuse emerges.

- **Four modes as presets, not a raw config matrix.** A mode ⊗ sort ⊗ banner ⊗ progress-metric space is
  mostly nonsense; presets hide it. Mirrors `LibrarySortKey`'s "one sensible direction per key" stance.

- **Manual membership only.** Derived rules re-open the "bulk tagging" rejection and add sync-time
  membership drift. Manual is unambiguously additive and stable.

- **Aggregate completion progress = mean of member completion fractions** (members without HLTB data
  excluded). Simplest intuitive, testable aggregation.
  *Alternatives:* playtime-weighted mean (honest but complex); count of completed games (loses nuance).
  Mean chosen for v1; weighted aggregation deferred.

- **Completion fraction reuses `Gamification.goalProgress`** (playtimeForever ÷ completionistMinutes,
  clamped 0–1). Consistency with the Library's existing progress bars and the engine's definition; no new
  math, no engine change.

- **Deadline is collection-level, not per-game.** One target date for the whole collection. Per-game
  deadlines add a date column to the join table and per-member countdowns; deferred.

- **Two tables, no foreign key from members to games.** `collections` (id, name, mode, sort, targetDate,
  createdAt) and `collection_members` (collectionId, appId, orderIndex; PK = collectionId+appId). A FK from
  members to `collections` with `CASCADE` delete drops memberships when a collection is deleted. No FK to
  `games` — Steam syncs can transiently omit games; a hard FK would cascade-delete memberships on a glitch.
  The soft `appId` reference plus graceful omission is safer.

## Risks / Trade-offs

- **Dangling member rows** → a member referencing a game that permanently left the library stays in the
  table. Mitigation: omitted from rendering; a future cleanup can prune members absent for N syncs.
- **Unweighted mean progress** → a 2-hour and a 200-hour game contribute equally. Trade-off accepted for
  simplicity; noted for a later weighted option.
- **Deadline banner without feasibility** → "12 days, 65%" is less actionable than "behind schedule."
  Accepted for v1; feasibility inference is an open question.
- **Home density** → Home already carries several cards. Mitigation: compact cards or a horizontal row; the
  now-playing card's "most prominent" priority (enhance-now-playing) must not be demoted.
- **Two grouping systems coexist** → collections (Home) vs. the Focus tag (Library). Trade-off accepted;
  they have distinct semantics. Copy must distinguish them.
- **Label/identifier mismatch continues** → user-facing mode names vs. `CollectionMode` code names, the same
  deliberate trade-off as Focus/Your-games vs. `isGoal`/`observeBacklog()`.

## Migration Plan

Room schema version bump + a `Migration` that creates the two new tables. No existing table is altered;
`isGoal`, `targetMinutes`, and all existing columns are untouched. A fresh install and an upgrade both start
with zero collections — the Home collections section renders its empty state until the user creates one.

No Steam Web API change, no engine change, no Preferences DataStore key changes (collections persist in
Room). Rollback: dropping the two tables and reverting the version bump removes the feature cleanly; no
existing data depends on it.

## Open Questions

- **Deadline feasibility ("on track")** — infer pace (HLTB remaining ÷ days left vs. recent playtime rate),
  or just show countdown + progress? Inference is pure and testable but adds edge cases. Deferred from v1.
- **Per-game deadlines** — target date per member vs. one for the collection. More granular but more state.
- **Sort options** — beyond name and the mode-relevant metric, should playtime or achievements-remaining be
  cross-mode sorts? Deferred; four-mode presets cover the first slice.
- **Home card layout** — vertical list vs. horizontal scroll for collection cards; a density decision for
  implementation.

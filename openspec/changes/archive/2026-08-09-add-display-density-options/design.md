# Design — Display density for game lists

## Context

Both game lists render one full-width row per game. In the Library, `GoalGameRow` and
`BacklogGameRow` are near-identical bodies — same `GameCard` wrapper, same icon-with-badge, same
name/playtime/progress/badges column, same trailing control — differing only in the type of `game`
they accept. The collection overview's `CollectionGameCard` is a third variant of the same idea, with
a `GameHeaderBackdrop` behind it.

The row's content already forms a natural importance ladder:

```
GameIconWithHltbBadge     identity + HLTB state + currently-playing
Text(game.name)           identity
PlaytimeLabel             the number most often wanted
CompletionProgress        HLTB-derived bar
GameBadges                achievements, XP
RowTrailing               selection / goal management
```

Density is a matter of choosing where to cut that ladder — provided the cut is defined once rather
than per surface.

Persistence precedent is exact. `SettingsDataStore` holds `LIBRARY_FOCUS_SORT` and `LIBRARY_ALL_SORT`
as `stringPreferencesKey`s, resolved through `librarySortKeyOrNull(...) ?: defaults.x` so an unknown
stored value degrades to the default rather than failing. The project invariant is that nothing under
`ui/` imports a storage type, so these reach view models through `SettingsRepository`.

The collection form's configuration is six `SectionLabel` + control blocks at `titleMedium` with
12dp spacing, every option full-width and equally weighted, ahead of the member list and the
add-games pool.

**Two other changes touch these files.** `improve-collection-organization` modifies
`Collection management screen` (description, delete confirmation) — this change's delta is written on
top of that version. `improve-search-relevance` repositions the Add-games search within
`CollectionForm` but modifies a different requirement, so the two coexist in the spec while both
editing the same file.

## Goals / Non-Goals

**Goals:**
- Trade detail for reach on both game lists, by an explicit ladder rather than per-surface judgement.
- Remember each surface's choice independently.
- Make the collection form's configuration materially shorter without removing any option.

**Non-Goals:**
- New information on any game row.
- Changing sections, sorting, search, or filtering.
- A density choice on History, Analytics, or the Home collection cards.
- A global density setting spanning surfaces.
- Removing configuration options to achieve compactness.

## Decisions

- **Density is one ladder, defined once, and denser views are strict subsets.** Identity at every
  density; playtime everywhere but the densest; completion progress in the list and the least dense
  grid; badges in the list only.
  *Why:* the alternative — each surface deciding what its own "compact" means — produces two
  vocabularies for one word and makes "compact" unpredictable as the user moves between screens. The
  strict-subset rule is what makes increasing density feel like zooming out rather than switching to a
  different screen.
  *Consequence:* it constrains future additions. Anything new on a row must be placed on the ladder,
  which is the point.

- **Currently-playing survives every density; it is a signal, not detail.**
  *Why:* it is live state the rest of the app treats as important enough for a dedicated color token
  and a shell-level indicator. Dropping it at high density would make the densest view the one where
  the most time-sensitive fact disappears.

- **Selection must work at every density, and its placement is the open design problem.** The
  Library's selection affordance and goal-management control currently live in `RowTrailing`, which a
  grid cell has no equivalent of.
  *Why it matters:* the Library's multi-select drives batch HLTB operations, and a density that
  quietly disabled selection would be a trap — the user would reach for the densest view precisely
  when operating on many games.
  *Direction, not yet decided:* an overlay affordance on the cell (a corner check on long-press) keeps
  the interaction identical at every density without reserving permanent space. Settled during
  implementation against the spec's "selection available at every density" scenario.

- **Each surface persists its own density; there is no global setting.** Following the two existing
  per-list sort keys rather than introducing an app-wide display preference.
  *Why:* the Library and a collection are used for different things — scanning a large library versus
  reviewing a curated handful — and the existing sort keys already establish that these two surfaces
  hold their own view preferences. A single global density would force one to be wrong.
  *Alternative rejected:* a global density in Settings — fewer controls, but it makes the choice
  remote from the list it affects and couples two surfaces that have no reason to agree.

- **Density is persisted, unlike the achievement sort and the genre filters.**
  *Why:* the codebase draws this line consistently — a *lens* on the current view is transient
  (achievement sort, genre filter), a *stated preference about how a surface works* is persisted
  (sort keys). Density is the latter: a user who prefers a dense grid prefers it every time, and
  re-choosing it on every visit would be the annoyance the sort keys were persisted to avoid.

- **The row bodies are unified before densities are added, not after.** `GoalGameRow` and
  `BacklogGameRow` are near-duplicates today; adding two densities each would make four
  near-duplicates.
  *Why:* the duplication is currently tolerable because it is two short bodies. Multiplying it by the
  ladder is how a rendering inconsistency between the tracked and untracked sections becomes
  inevitable.

- **Form compaction reduces vertical space without removing options, and may use disclosure.** The
  spec permits an option to live behind a disclosure the user can open.
  *Why:* the reported complaint is that options are "too spaced out", and the structural cause is that
  a rarely-revisited setting (estimate basis, accent) is weighted identically to the name. Tightening
  spacing alone recovers some space; letting the seldom-touched settings collapse recovers more. The
  spec constrains the outcome — games reachable without scrolling past a full screen — and leaves the
  mechanism open.
  *Explicitly not:* dropping any option. Stated as its own scenario so compaction cannot quietly
  become removal.

## Risks / Trade-offs

- **Selection in a grid cell.** The main unknown. → Spiked first; the spec pins the requirement so the
  spike has a pass condition.

- **Grid cells and header art.** `CollectionGameCard` uses `GameHeaderBackdrop` as a right-aligned
  faded wash sized for a wide row. In a square-ish cell that composition does not transfer directly.
  → Treat header art as a density-dependent treatment rather than assuming it scales; the least dense
  grid may keep it and the densest drop it, which the ladder already permits since it is not
  information.

- **`GameIcon` is hardcoded at `RoundedCornerShape(8.dp)` and a fixed size.** Grid densities need it
  parameterized. → `polish-game-surfaces` needs a `shape` parameter on the same component for circular
  thumbnails. Whichever lands first should add parameters without changing the default, so the other
  is unaffected.

- **Three changes editing `CollectionScreen.kt`.** This one, `improve-collection-organization`, and
  `improve-search-relevance`. → Only the first shares a requirement with this change and the ordering
  is stated; the other is a different requirement. Sequencing is a merge concern, not a spec one.

- **Compaction is judged by a qualitative bar.** "Materially less than a full screen" is not
  measurable in a unit test. → Deliberate: an exact dp budget would be wrong on some screen sizes.
  Verified by hand on a phone-sized viewport, which is what the complaint came from.

## Migration Plan

Two new preference keys with defaults, following the existing sort-key pattern including the
tolerate-unknown-value fallback. No schema change, no data migration. Unset keys resolve to the
current list rendering, so an upgrade changes nothing until the user chooses otherwise. Revertable by
removing the controls; stored keys become inert.

## Open Questions

- How many grid densities — two, or two grids plus the list? The spec requires a list and at least two
  grids, leaving room to settle this against real content once the ladder is built.
- Should the collection overview's density choice also apply to the member list inside the management
  form, or does the form keep a fixed rendering since it is an editing surface with per-row controls?
  Leaning toward the form keeping a fixed rendering, since its rows carry remove and reorder controls
  that the overview's do not.

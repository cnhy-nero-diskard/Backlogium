# Design — Library navigation, sorting, universal completion progress, and targeted HLTB refresh

## Context

`LibraryScreen` renders one `LazyColumn` with two sections built from two independent Room
queries (`gameRepository.goalGames`, `gameRepository.backlog`). There is a live comment noting
that a just-tagged game can momentarily appear in both, and that a duplicate `appId` key crashes
Compose — the backlog list is defensively filtered against goal ids. **That invariant (each `appId`
in exactly one section) must survive any change here.**

The tagged section's history matters for this design. It was built when a goal carried a
user-entered minutes target; `GameRepository.tagGoal` documents that target as retired, and
`Game.targetMinutes` is dormant. What the tag still does today:

| Job | Status |
|---|---|
| Shows progress vs the HLTB completionist length | **Not exclusive** — the batch refresh fetches HLTB for every owned game; `BacklogGameRow` simply never renders a bar |
| `DailyProgress.goalMinutesPlayed` per-day accounting | Live (`SteamSyncWorker.kt:142`), surfaced in History |
| `QuestMode.GOAL_ONLY` quest scoping | Implemented in the engine; dormant — nothing writes the setting |
| Tagging triggers a single-game HLTB fetch | Convenience; the batch sweep covers it anyway |

So the tag is a real concept with real accounting behind it, but its *visual* privilege is an
artifact of a retired feature.

`HltbRepository.refreshBatch(games, force, onProgress)` already takes an arbitrary
`List<Pair<Long, String>>` — a subset is not a new concept to the repository, only to its callers.
`HltbRefreshWorker` unconditionally builds that list from `gameDao.getAll()`, and its
`setProgress(done, total)` output is dropped by `SyncScheduler`, which maps work info to a boolean.

XP per game is fully derivable today with no engine change:

```
gameXp(backfillMinutes + trackedMinutes(appId), hltb.completionistMinutes)
  + achievementXp(this game's unlocked achievements, by snapshotPercent)
```

which is precisely what `GamificationUpdater.recompute` sums across the library.

## Goals / Non-Goals

**Goals:**
- Find a specific game, and order each list by what matters.
- Show every game's progress toward completion, since the data exists for every game.
- Make both section labels match what they now mean.
- Show what a game contributed to XP, consistently with the engine.
- Make the batch sweep legible and targetable.

**Non-Goals:**
- Pinning, retiring the tag, renaming internal fields, dropping `targetMinutes`, completion-percentage
  sorting, non-name filters, persisted batch logs, bulk tagging, redefining XP.

## Decisions

- **Completion progress is shown for any game with a known completion length, tagged or not.**
  `BacklogGameRow` gains the same progress presentation `GoalGameRow` already has, conditional on
  `completionistMinutes != null` exactly as the tagged rows are.
  *Why:* the HLTB integration already fetches this for the whole library — withholding it from
  untagged rows is a leftover from when a target only existed on tagged games. *Consequence:* the
  tagged section is no longer distinguished by *what it shows* but by *what it means*, which is the
  honest position and the thing that makes the relabel necessary rather than cosmetic.

- **No pinning feature.** Rejected after weighing it against the relabel: with progress universal and
  the section honestly named, "pin" and "tag" become the same gesture. Keeping only the tag avoids a
  second promotion tier stacked above the first, a `games.pinned` column and its migration, and a
  third flag for `SteamSyncWorker.persistPoll` to preserve (the failure mode that would have
  silently unpinned everything on the next sync).
  *Why this reversal:* an earlier draft added Pinned as a third section above Goal games. That put two
  promotion concepts adjacent with no clear division of labor, and forced a pinned tagged game to
  either lose its section heading or appear twice. Collapsing to one concept removes the problem
  rather than managing it.

- **"Focus" replaces "Goal games" and "Your games" replaces "Backlog" in user-facing copy; internal
  names are untouched.** The Library headings, the tag/untag dialog copy, and History's "on goals"
  line all move to the new wording. `isGoal`, `goalMinutesPlayed`, `QuestMode.GOAL_ONLY`,
  `observeGoalGames()`, and `observeBacklog()` keep their names.
  *Why (Focus):* "goal" implied a target the user set, which no longer exists; "Focus" describes a
  curated set being actively tracked, which is what it is.
  *Why (Your games):* "backlog" means games awaiting play. That was defensible when the section was
  everything-except-the-goals and only goals showed progress — but with completion progress on every
  row, a 200-hour finished game filed under "Backlog" is actively wrong. "Your games" claims nothing
  about intent, which is right for a list that is simply the rest of the library.
  *Why not rename the code:* renaming the schema and engine surface would need a migration and an
  engine change for zero functional gain, and `GOAL_ONLY` is an engine concept predating this UI
  question. *Risk accepted:* a lasting label/identifier mismatch, mitigated by saying so in one place
  (this document) rather than leaving future readers to guess.

- **Each list gets its own sort control, and the selection persists.** Options: playtime, name,
  recently played (`Game.playtime2Weeks`), and XP contributed. Two independent selections, stored as
  two Preferences DataStore keys in `SettingsDataStore`.
  *Why independent:* the two lists answer different questions — a short curated Focus list is most
  useful alphabetically or by recent activity, while a 300-game library is most useful by playtime. One
  shared control would force a compromise on both.
  *Why persisted, unlike the achievement sort:* the achievement sort is a lens applied within one
  game's detail screen, discarded on the way out. The Library is the screen you return to constantly,
  and re-picking your ordering on every visit is friction. Preferences DataStore means no migration.
  *Sorting happens in the ViewModel, not the DAO.* The XP-contributed key is a read-side derivation
  that SQL cannot express, and the lists are already fully in memory — so all four keys sort in one
  place rather than two of them living in Room queries and two in Kotlin.
  *Defined tie-breaks:* every key falls back to name ascending, so ordering is stable and never
  depends on Room's return order. Games with zero recent playtime sort last under "recently played";
  zero-XP games sort last under "XP contributed".

- **The tagged section stays a separate section.** With progress universal, its remaining purpose is
  to be a short, curated list at the top, whose minutes are accounted separately and which
  `GOAL_ONLY` can scope quests to.
  *Why:* that is a genuine, useful distinction — just not the one the old label advertised.

- **Search filters in the ViewModel over already-loaded state, not via a new Room query.** The
  library is already fully in memory in `LibraryUiState`; filtering is a case-insensitive
  `contains` over `name` applied to both lists.
  *Why:* no DAO changes, instant response, no query-per-keystroke debounce machinery. Section
  headings are retained for whichever sections still have matches, and an empty result shows an
  empty state rather than a bare screen.

- **The XP badge uses the engine's own inputs — tracked + backfill minutes, not `playtimeForever`.**
  This makes the badges sum to the player's actual total XP, which is the only defensible
  definition. It also means a badge will **not** look proportional to the "120h played" text on the
  same row: playtime XP is tapered, and `playtimeForever` includes pre-install hours that only
  count if the player opted into the history import.
  *Why:* a badge that disagreed with the player's total XP would be worse than no badge.
  *Mitigation:* label it as XP contributed (not a per-hour rate); a never-tracked game showing
  `0 XP` is correct, not a bug. Spec'd explicitly so it is not "fixed" later.
  *Cost accepted:* row density — and it rises further now that untagged rows carry a progress bar
  too. If it reads as cluttered, the XP badge is the first thing to demote.

- **`refreshBatch`'s progress callback widens to carry the per-game outcome.** From
  `(done, total)` to `(done, total, name, HltbMatchStatus?)` — `null` status meaning the lookup
  itself failed (`refresh` returned null), which the repository already distinguishes.
  *Why:* the outcome is known exactly where progress is reported; recovering it later would mean
  re-querying the cache per game. The signature keeps a default so existing callers are unaffected.

- **Progress crosses the process boundary via WorkManager `setProgress`; the log is rebuilt in the
  ViewModel.** The worker publishes `(done, total, currentGame, outcome)`. `SyncScheduler` exposes
  `WorkInfo.progress` as a typed flow instead of collapsing it to a boolean. The ViewModel
  accumulates emissions into a rolling log while the screen is observed.
  *Why:* WorkManager progress is the only channel that survives the screen closing, and it carries
  one snapshot rather than a list — accumulation belongs on the observer side. *Accepted:* leaving
  the Library and returning shows correct progress but an empty log, because the history was never
  persisted. Documented trade-off.

- **An explicit selection always forces refresh.** `refreshBatch(force = true)` for a subset,
  bypassing the 2-month freshness window.
  *Why:* selecting three games is an unambiguous statement of intent; silently skipping them
  because they were fetched last month would make the feature feel broken.

- **Multi-select is entered by long-press, exited by clearing.** Tap keeps its current meaning
  (open game detail) and the 3-dot menu keeps its own. While selecting, an action bar shows the
  count and the "HowLongToBeat lookup (N)" action.
  *Why:* always-visible checkboxes would add permanent clutter for a mode used occasionally.
  *Constraint:* selection is transient view state, keyed by `appId` independent of the active
  filter, and clears on navigation away.

## Risks / Trade-offs

- **Label/identifier mismatch** — the UI says Focus and Your games, the code says `isGoal` and
  `observeBacklog()`. A deliberate trade against a migration; recorded here so it reads as a decision,
  not an oversight.
- **Sort keys that can disagree with what a row displays** — "recently played" uses
  `playtime2Weeks` (Steam's own rolling figure), while the XP key uses tracked + backfill minutes. A
  game can sort high on recent activity while badging little XP. Both are correct; the sort labels need
  to name what they order by.
- **Row density** — icon, playtime, HLTB status, achievement badge, XP badge, and now a progress bar
  on every row with a length. Watch this in practice; demote the XP badge first if needed.
- **XP badge confusion** — "0 XP on a 120-hour game" will look wrong to anyone who never imported
  history. Mitigated by copy and called out in the spec deliberately.
- **Search + selection interaction** — a filter that hides a selected game must not silently drop it
  from the pending selection. Keep the selection keyed by `appId` and keep the count visible.
## Migration Plan

**No Room migration.** Universal progress and the XP badge are read-side derivations from data already
stored, the relabels are copy, and search/selection are transient view state. The only persisted
additions are two Preferences DataStore keys for the per-list sort selections, which default to the
current DAO ordering — name for Focus, playtime for Your games — so a fresh install and an upgrade both
render exactly as today until the user changes a sort. No schema version bump, no new columns, no
`SteamSyncWorker.persistPoll` changes.

## Open Questions

- Should `QuestMode.GOAL_ONLY` get a settings control, now that the Focus section has a clear
  meaning? It is implemented but unreachable. Out of scope, but this change makes it coherent enough
  to be worth exposing.
- Should sort direction be togglable per key (playtime ascending to find quick wins), or is one
  sensible direction per key enough? Assuming the latter: descending for playtime, recent, and XP;
  ascending for name.

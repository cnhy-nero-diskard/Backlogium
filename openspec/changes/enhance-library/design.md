# Design — Library navigation, pinning, and targeted HLTB refresh

## Context

`LibraryScreen` renders one `LazyColumn` with two sections built from two independent Room
queries (`gameRepository.goalGames`, `gameRepository.backlog`). There is a live comment noting
that a just-tagged game can momentarily appear in both, and that a duplicate `appId` key crashes
Compose — the backlog list is defensively filtered against goal ids. **Any new section must
preserve that invariant: each `appId` appears exactly once across all sections.**

`HltbRepository.refreshBatch(games, force, onProgress)` already exists and takes an arbitrary
`List<Pair<Long, String>>` — a subset is not a new concept to the repository, only to its callers.
`HltbRefreshWorker` unconditionally builds that list from `gameDao.getAll()`, and its
`setProgress(done, total)` output is dropped by `SyncScheduler`, which maps work info down to a
boolean.

XP per game is fully derivable today with no engine change:

```
gameXp(backfillMinutes + trackedMinutes(appId), hltb.completionistMinutes)
  + achievementXp(this game's unlocked achievements, by snapshotPercent)
```

which is precisely what `GamificationUpdater.recompute` sums across the library.

## Goals / Non-Goals

**Goals:**
- Find a specific game; keep chosen games within reach.
- Show what a game contributed to XP, consistently with the engine.
- Make the batch sweep legible and targetable.

**Non-Goals:**
- Sort controls, non-name filters, persisted batch logs, bulk goal tagging, redefining XP.

## Decisions

- **Pinned is its own section, above Goal games; pin and goal are independent flags.** A pinned
  goal game appears **only** in Pinned, preserving the one-section-per-appId invariant. Section
  order: Pinned → Goal games → Backlog.
  *Why:* the existing sections already encode a priority order, so pins slot in as a higher tier
  rather than a cross-cutting sort. *Alternative rejected:* floating pins to the top within their
  existing section — pins scattered across two sections defeat the "keep it within reach" purpose.
  *Consequence:* a pinned goal game loses its "Goal games" heading context, so its row must still
  carry its goal affordances (completion progress bar, goal-aware 3-dot menu).

- **`pinned` is a column on `Game`, following the `isGoal` precedent.** Same shape, same DAO
  patterns, same `SteamSyncWorker.persistPoll` preservation concern as `backfillMinutes`/`isGoal`.
  *Why:* consistency beats novelty; a separate pins table would need a join for no benefit.
  *Watch:* `SteamSyncWorker` rebuilds `Game` rows on every poll — `pinned` must be preserved there
  exactly as `isGoal` and `backfillMinutes` already are. This is the single most likely bug in the
  change.

- **Search filters in the ViewModel over already-loaded state, not via a new Room query.** The
  library is already fully in memory in `LibraryUiState`; filtering is a case-insensitive
  `contains` over `name` applied to all three lists.
  *Why:* no DAO changes, instant response, no query-per-keystroke debounce machinery. Section
  headers are retained for whichever sections still have matches, and an empty result shows an
  empty state rather than a bare screen.

- **The XP badge uses the engine's own inputs — tracked + backfill minutes, not `playtimeForever`.**
  This makes the badges sum to the player's actual total XP, which is the only defensible
  definition. It also means a badge will **not** look proportional to the "120h played" text on the
  same row: playtime XP is tapered, and `playtimeForever` includes pre-install hours that only
  count if the player opted into the history import.
  *Why:* a badge that disagreed with the player's total XP would be worse than no badge.
  *Mitigation:* the badge must be labelled as XP contributed (not "XP earned per hour"), and a
  never-tracked game showing `0 XP` is correct, not a bug. Document this in the spec so it is not
  "fixed" later.
  *Cost accepted:* row density. The row already carries icon, playtime, HLTB status, achievement
  count, and sometimes a progress bar; the XP badge is one more chip and should sit with the
  achievement badge rather than on its own line.

- **`refreshBatch`'s progress callback widens to carry the per-game outcome.** From
  `(done, total)` to something like `(done, total, name, HltbMatchStatus?)` — `null` status meaning
  the lookup itself failed (`refresh` returned null), which is already distinguishable in the
  repository.
  *Why:* the outcome is known exactly where progress is reported; recovering it later would mean
  re-querying the cache per game. The signature has a default so existing callers are unaffected.

- **Progress crosses the process boundary via WorkManager `setProgress`; the log is rebuilt in the
  ViewModel.** The worker publishes `(done, total, currentGame, outcome)`. `SyncScheduler` exposes
  the `WorkInfo.progress` data as a typed flow instead of collapsing to a boolean. The ViewModel
  accumulates emissions into a rolling log while the screen is observed.
  *Why:* WorkManager progress is the only channel that survives the screen closing, and it carries
  one snapshot rather than a list — accumulation belongs on the observer side. *Accepted:* leaving
  the Library and returning shows a correct progress bar but an empty log, because the accumulated
  history was never persisted. That is the documented trade-off.

- **An explicit selection always forces refresh.** `refreshBatch(force = true)` for a subset,
  bypassing the 2-month freshness window.
  *Why:* selecting three games is an unambiguous statement of intent; silently skipping them
  because they were fetched last month would make the feature feel broken.

- **Multi-select is entered by long-press, exited by clearing.** Tap keeps its current meaning
  (open game detail) and the 3-dot menu keeps its own. While selecting, an action bar shows the
  count and the "HowLongToBeat lookup (N)" action.
  *Why:* always-visible checkboxes would add permanent clutter to a row that is already dense, for
  a mode used occasionally. *Constraint:* selection is transient view state and must clear on
  navigation away — it is not persisted.

## Risks / Trade-offs

- **`SteamSyncWorker` dropping `pinned`** — the known failure mode for this entity; needs an
  explicit test, not just care.
- **Row density** — five signals plus a progress bar on one card. If it reads as cluttered in
  practice, the XP badge is the first thing to demote (e.g. only on pinned/goal rows).
- **XP badge confusion** — mitigated by copy, but "0 XP on a 120-hour game" will look wrong to
  someone who never imported history. Called out in the spec deliberately.
- **Search + selection interaction** — selecting games, then typing a filter that hides them, must
  not silently drop them from the pending selection. Keep the selection keyed by `appId`,
  independent of what is currently visible, and show the count so nothing is invisible.

## Migration Plan

`BacklogiumDatabase` → next version, additive only:

```sql
ALTER TABLE games ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
```

Default 0 = nothing pinned, so existing installs render exactly as today (no Pinned section until
the player pins something). If `add-steam-profile-header` and `enhance-game-detail` ship in the same
release, combine all three columns into one version bump.

## Open Questions

- Should pinning be capped (e.g. 10) to keep the section a *shortlist* rather than a second
  library? Unlimited for now.
- Should the Pinned section collapse when search is active? Currently it filters like the others.

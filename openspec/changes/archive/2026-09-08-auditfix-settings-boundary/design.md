## Context

See `proposal.md` — Why. Four findings, three in `SettingsViewModel.kt`, plus one unfiled
breach found by running `CLAUDE.md`'s own grep.

Current state of that grep on the tree, which is the baseline this change moves:

```
$ grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" \
    app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics

ui/home/HomeViewModel.kt:6     …entity.Collection          ← documented, deferred
ui/home/HomeViewModel.kt:7     …entity.CollectionMember    ← documented, deferred
ui/review/HltbReviewScreen.kt:38    …entity.HltbMatchStatus ← UNFILED
ui/review/HltbReviewViewModel.kt:6  …entity.HltbMatchStatus ← UNFILED
ui/review/SteamGameHeader.kt:26     …entity.HltbMatchStatus ← UNFILED
ui/settings/SettingsViewModel.kt:17 …entity.SteamAssetDownloadState  ← #97
```

Plus, not caught by that grep because they are DAOs rather than entities:

```
ui/settings/SettingsViewModel.kt:15  data.local.dao.GameDao        ← #125
ui/settings/SettingsViewModel.kt:16  data.local.dao.SteamAssetDao  ← #97
```

Worth noting: the documented grep pattern matches entities and `SettingsDataStore`, so the
two DAO imports that #97 and #125 are really about slip past it. Decision 4 addresses that.

## Goals / Non-Goals

**Goals:**

- `SettingsViewModel` depends on repository/domain shapes only.
- Settings' manual sync delivers Reject on failure, attributed to the player's own attempt.
- The three unconfigured empty states name Settings.
- `HltbMatchStatus` stops being an undocumented breach — fixed or recorded, not left ambiguous.

**Non-Goals:**

- The `HomeViewModel` `Collection`/`CollectionMember` breach. `CLAUDE.md` records it as
  deferred pending a `CollectionRepository` boundary fix, and the collections surface is
  broad. It stays reported by the grep after this change.
- Changing what Settings displays. The boundary work is a refactor; the same values arrive
  through a different seam.
- Revisiting `ui/diagnostics/`'s deliberate exception. It is reasoned in `CLAUDE.md` and no
  finding challenges it.

## Decisions

### Decision 1: Map at the repository boundary, do not add a parallel domain model per DAO

Both #97 and #125 are the same shape — Settings needs a *derived* fact and reaches for the
DAO that happens to hold the raw rows.

- **Asset run state (#97).** `SettingsUiState.lastSteamAssetRun` exposes the entity. What the
  screen renders is a summary of the last run; the fix is a domain type carrying exactly that,
  produced by the repository.
- **HLTB coverage (#125).** `SettingsViewModel` observes `gameDao.observeAppIds()` only to
  compute `applied?.coveredAppIds?.let { covered -> ownedAppIds.count { it in covered } }`.
  The ViewModel does not want app ids at all — it wants a count. So the *coverage figure*
  moves behind the boundary, not the app-id list.

That distinction is the decision. The lazy version of this fix exposes
`observeOwnedAppIds()` on a repository and calls it done: the import goes away, the layering
violation does not. `CLAUDE.md`'s rule is that repositories expose domain models, and a
`Set<Long>` of raw app ids pulled up so the UI can do a set intersection is a DAO call
wearing a different hat.

**Alternative considered**: add `SteamAssetDownloadState` and the app-id list to the
documented exception list, as the diagnostics package has. Rejected — `CLAUDE.md` scopes that
exception to a developer-facing debug surface "whose whole purpose is to show the stored rows
as stored", and argues that a parallel model plus an identity mapper would only misrepresent
the thing being debugged. Settings is a product surface and the reasoning does not transfer.

### Decision 2: Copy Home's attribution guard rather than emitting on any failure

The naive fix for #109 is to observe `lastSyncError` in Settings and buzz when it appears.
That would be wrong, and Home already documents why:

> The error card is also used by background syncs. Only a retry initiated from this visible
> card arms Reject, so a background failure never produces an unattributable buzz.

`lastSyncError` is written by the worker regardless of what started the run, so an unattributed
observer would buzz for a scheduled sync that failed while the player happened to be reading
Settings. `app-ui/spec.md:2383-2385` requires the intent for "a sync **the player
initiated**", once.

So Settings adopts Home's shape: arm on the player's tap, watch `isSyncing` transition from
true back to false, and emit Reject once if `lastSyncError` is set when *that* attempt
settles. Same guard, same reasoning, and the two manual-sync entry points then behave alike —
which is the actual complaint in #109.

**Where it lives.** Home keeps this in the composable. Settings could instead expose a
one-shot event from the ViewModel, which is arguably tidier. Matching Home is chosen: the
codebase has one established way to do this, `CLAUDE.md` asks for the surrounding idiom, and
two divergent approaches to one haptic is how the inconsistency arose.

### Decision 3: Resolve `HltbMatchStatus` by deciding, not by widening a grep

Three files in `ui/review/` import `data.local.entity.HltbMatchStatus`. It is on no exception
list, and it is not the documented `HomeViewModel` breach — so `CLAUDE.md` currently describes
a state the tree does not match.

Two legitimate answers, and task 4.1 requires one of them:

1. **Map it out.** If `HltbMatchStatus` is a persistence detail, the review surface should
   receive a domain equivalent, like the two fixes above.
2. **Document it as a third exception.** `HltbCandidate` is already an exception on the
   grounds that it "crosses the boundary as a plain serializable class, because it is exactly
   the shape the review surface needs" — and `HltbMatchStatus` serves the *same* surface. If
   it is a stable enum-like status rather than a storage shape, the `HltbCandidate` reasoning
   plausibly extends to it, and saying so in `CLAUDE.md` is more honest than a mapper that
   converts an enum to an identical enum.

Option 2 is the likely answer, but it must be a recorded decision with its reasoning, not a
silent omission. What is not acceptable is the current state, where the invariant's own
verification command reports a breach the documentation does not mention — because that
teaches readers to ignore the grep's output, which costs more than either fix.

### Decision 4: Extend the documented grep to cover DAO imports

The pattern in `CLAUDE.md` matches `data.local.entity` and `SettingsDataStore`. Both #97 and
#125 are fundamentally DAO dependencies — `SteamAssetDao` at `:16` and `GameDao` at `:15` —
and neither appears in that grep's output. #97 was only caught because it *also* imports an
entity; #125 is invisible to it entirely, which is presumably part of why the README recorded
one and not the other.

Adding `data\.local\.dao` to the alternation makes the check cover what the invariant
actually says ("Nothing under `ui/` imports a storage type"). Task 5.4 updates `CLAUDE.md`
so the command and the rule agree.

This is the part of the change with the longest tail of value: a verification command that
cannot see a whole category of the violation it exists to catch will keep producing findings
like #125.

## Risks / Trade-offs

**A new repository method could leak the entity one layer down** → Decision 1's test is that
the ViewModel receives a *derived* value (a run summary, a coverage count), not a mapped
container of the same rows. Tasks 2.2 and 2.4 name the shape explicitly.

**Settings' Reject could double-buzz with Home's** → Both arm only on their own visible
control, so a player who taps Sync in Settings has not armed Home's card. Task 3.5 tests that
a background failure while Settings is open produces nothing.

**Extending the grep will report new pre-existing breaches** → Likely, and it is a feature
rather than a problem. Task 5.5 requires any newly-surfaced import to be triaged before this
change closes: fixed here if it is in these files, filed as an issue otherwise. **Do not
narrow the pattern to make the output quiet.**

**The `HomeViewModel` breach makes the grep non-empty, so "must produce no output" cannot be
the check** → Correct, and stated in the proposal. Task 5.3's assertion is that the grep
reports *exactly* the documented `HomeViewModel` lines and nothing else. A silent grep would
mean the exclusions were widened.

## Migration Plan

No data or schema change. Order by independence:

1. Empty-state copy (#112) — three string edits, no dependency on anything.
2. Reject haptic (#109) — self-contained in Settings.
3. Boundary refactor (#97, #125) — the largest edit, and last so the smaller fixes are not
   held behind it.
4. `HltbMatchStatus` decision and the `CLAUDE.md`/`README.md` updates, which record the
   resulting state.

Each step is an independent revert.

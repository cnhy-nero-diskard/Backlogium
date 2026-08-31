## Why

Every Backlogium install scrapes HowLongToBeat directly, and the batch sweep is the bulk of it: a
300-game library issues ~300 searches paced 1.5 s apart, once per 60-day freshness window, per
device. Multiplied across installs, that is a recognisable traffic pattern from a spoofed browser
handshake — which is how the search endpoint discovery broke and had to be hotfixed once already.

The waste is structural, not incidental. HowLongToBeat data is **global**: Main Story time for a
given game is the same answer for every user. The app currently re-derives that shared answer on
every device, by searching a name and guessing at the match — which is why `HltbMatcher`,
`candidatesJson`, and the whole match-review surface exist. Thousands of requests are spent solving
one problem that has one right answer.

Sourcing that shared answer from a shared, community-curated dataset removes the sweep entirely,
removes most of the match ambiguity along with it, and leaves live lookups as a deliberate,
one-game act — which is also the behaviour we want other users of the project to inherit by default.

## What Changes

- **BREAKING: the whole-library HowLongToBeat sweep is removed.** `HltbRefreshWorker`,
  `HltbRefreshTimeoutWorker`, the WorkManager-backed batch progress surface, the freshness-gated
  stale-or-missing query, and the force-refresh option all go. There is no longer any code path
  that queries HowLongToBeat for more than an explicitly named set of games.
- **A shared HLTB dataset becomes the primary source.** A compact, numeric, append-mostly dataset
  maps Steam app ids to a HowLongToBeat id and its four completion lengths. The app downloads it
  as a GitHub release asset, verifies it, and merges it locally.
- **Live lookups are demoted, not deleted.** A lookup runs only for one game the user named, or an
  explicit multi-selection — the path `SyncScheduler.refreshHltbNow(appIds)` already serves. Every
  live lookup remains available for the long tail the dataset does not cover, including the
  ambiguity review, broader search, and pasted-link rescue that `expand-hltb-match-discovery`
  introduces.
- **The dataset is separated into a mapping and a set of lengths.** `appId → hltbId` is a
  correspondence table, not HowLongToBeat's content; `hltbId → lengths` is. Keeping the distinction
  explicit in the format lets each half be reasoned about — and redistributed — on its own terms.
- **An HLTB-only export produces a contribution file.** The user exports their resolved rows as the
  dataset format and opens a pull request. The export is deliberately narrow: resolved rows only,
  six numeric columns, no playtime, sessions, achievements, or streaks. Contributions arrive by
  pull request; there is no submission endpoint and no server that learns what anyone owns.
- **A repo-side tool validates and merges contributions** into the canonical dataset
  deterministically, so a pull request diff is exactly the rows it adds and CI can reject a
  malformed one without maintainer attention.
- **The first-run "Fetch completion times" stage becomes a dataset import.** One download replaces
  a paced sweep that could run for minutes.
- The dataset ships on its own release tags, so completion data can be published without cutting an
  app release.

## Capabilities

### New Capabilities

- `hltb-dataset`: the shared completion-length dataset — its format and the mapping/lengths split,
  how it is discovered and verified from GitHub Releases, how it merges against locally held data
  and what never overrides a user's own resolved match, how coverage misses are expressed rather
  than hidden, how staleness is represented, and the narrow contribution export that feeds it.

### Modified Capabilities

- `hltb-data`: removes the batch library refresh, its freshness gate, its force option, its
  explicit-subset variant's whole-library sibling, and the scheduler-outcome requirements that only
  a batch could satisfy. Adds dataset-first resolution, restricts live lookups to explicitly named
  games, and redefines what freshness means for a row that came from the dataset rather than a
  fetch.
- `app-ui`: replaces the batch-refresh progress presentation with a dataset import presentation,
  scopes the completion-times action to a named game or an explicit multi-selection, and keeps the
  match-review surface for the long tail the dataset does not cover.
- `app-settings`: the Sync section's completion-times row becomes a dataset row — when the dataset
  was last updated, what it covers, and a control that checks for a newer one.

## Impact

- **Affected code (removed):** `work/HltbRefreshWorker.kt`, `work/HltbRefreshTimeoutWorker.kt`,
  `work/HltbBatchProgress.kt`, `work/HltbNetworkConnectivity.kt`, `SyncScheduler.refreshHltbNow(force)`
  and its status/progress flows, `HltbRepository.refreshBatch` and `staleOrMissingAppIds`,
  `HltbDataDao.appIdsStaleOrMissing`, `FRESHNESS_WINDOW_MILLIS`, `INTER_REQUEST_DELAY_MS`.
- **Affected code (new):** a dataset source and merge path under `data/hltb/`, an HLTB-only export
  under `data/backup/` or beside it, and the Settings/first-run surfaces that drive them.
- **Affected code (modified):** `HltbRepository` gains dataset-first resolution;
  `work/setup/SetupStageRegistry.kt`'s completion-times stage swaps its runner; `HltbModule`'s
  binding composition.
- **A repo-side tool, not a third build system.** `tools/hltb-dataset/` is a dependency-free Node
  script run as `node tools/hltb-dataset/merge.mjs`. Node 22 is already established by `functions/`.
  It is invisible to Gradle and to the functions build graph, so `CLAUDE.md`'s two-toolchain table
  stays accurate as written.
- **Persistence:** `HltbData` gains provenance — whether a row came from the dataset or a live
  fetch — so precedence and staleness can be reasoned about. `fetchedAt` semantics change for
  dataset rows: they carry the dataset's own timestamp, not the import time, so an imported row is
  not treated as freshly fetched forever.
- **Network:** removes the sweep entirely. Adds one dataset download on a slow cadence, over the
  same GitHub Releases transport `app-updates` already uses. No per-user request is made that
  reveals the user's library to any server.
- **`app-updates` needs no change.** It already considers only tags matching `vX.Y.Z` and ignores
  everything else, so a `hltb-dataset-vN` tag is invisible to update discovery by existing
  behaviour.
- **Interaction with `expand-hltb-match-discovery`:** that change survives the demotion intact. Its
  broader search and pasted-link rescue operate on one named game, which is exactly the shape the
  demoted path allows; the dataset simply makes them needed far less often. Its removal of nothing
  and this change's removal of the batch do not overlap.
- **Legal posture:** contributions arrive as pull requests to a repository, and the maintainer
  operates no scraper and no aggregation service. Every row in the dataset traces back to a single
  game a single user chose to look up; no mass scrape occurs at any point.
- **Risk:** a game absent from the dataset has no completion length until the user looks it up
  deliberately. This is intended — coverage grows by contribution — but it must be visible in the
  UI as "not covered" rather than silently indistinguishable from "no match exists".
- **Risk:** contributing reveals which app ids the contributor owns, in a public diff. The export
  surface must say so plainly rather than let a user discover it after opening a pull request.

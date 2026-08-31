## 1. Dataset format and repo-side tooling

- [x] 1.1 Settle the published serialization (design.md Open Questions) and write
  `tools/hltb-dataset/FORMAT.md` documenting the mapping relation, the lengths relation, the
  dataset-level version and gathered-at metadata, and the plausibility ceiling for a completion
  length; verify by having a hand-written two-row sample file that conforms to it
- [x] 1.2 Implement contribution validation in `tools/hltb-dataset/merge.mjs` — positive integer
  identifiers, non-negative lengths under the documented ceiling, no duplicate app id within one
  file; verify with fixture contributions covering each rejection and one clean file, and that each
  rejection names the offending row
- [x] 1.3 Implement the merge — new rows added, differing lengths for one HLTB id resolved
  newest-wins, a differing HLTB id for one app id blocking with both correspondences reported;
  verify with fixtures for add, length-drift, conflict, and fully-redundant contributions
- [x] 1.4 Make merge output deterministic (stable sort by app id, fixed field order and formatting);
  verify that merging the same inputs twice is byte-identical, that reordering non-conflicting
  contributions is byte-identical, and that a redundant merge leaves the file unchanged
- [x] 1.5 Add a CI workflow running validate-and-regenerate on pull requests touching the dataset,
  failing when the committed output differs from the regenerated one; verify by opening a PR with a
  deliberately unsorted dataset and confirming CI rejects it
- [x] 1.6 Write `tools/hltb-dataset/README.md` covering how to contribute, what the export reveals,
  and how a correspondence conflict gets resolved; verify a reader can follow it end to end without
  reading the script

## 2. Local provenance and age

- [x] 2.1 Add a provenance column to `HltbData` distinguishing dataset, automatic device match, and
  manual resolution, with a Room migration defaulting existing rows to automatic device match;
  verify with a migration test asserting pre-migration rows survive and read as automatic
- [x] 2.2 Set provenance at every write site in `HltbRepository` — `query` writes automatic,
  `resolveMatch` writes manual; verify with repository tests asserting the recorded origin for a
  confident match, a review resolution, and an unmatched result
- [x] 2.3 Stop `BackupMergeEngine.mergeHltbData` stamping `time.nowMillis()` as `fetchedAt` and
  carry the imported row's own gathered-at time instead; verify with a merge test asserting an
  imported row's age is the backup's, not the import's

## 3. Dataset acquisition and application

- [ ] 3.1 Implement dataset discovery against the project's releases on the `hltb-dataset-vN` tag
  series, reusing the download-and-verify path `app-updates` owns; verify with tests covering a
  newer dataset found, already-current, download failure, and verification failure leaving locally
  held data untouched
- [ ] 3.2 Implement dataset parsing into the two relations with the mapping resolvable independently
  of the lengths; verify a correspondence with no lengths yields a matched game with unknown lengths
  rather than an unmatched one
- [ ] 3.3 Implement all-or-nothing application into `hltb_data`, carrying the dataset's gathered-at
  time as each row's age and dataset as each row's provenance; verify an interrupted application
  leaves the previous state intact and that re-applying the same dataset does not change any row's
  age
- [ ] 3.4 Implement merge precedence — dataset supersedes automatic matches, review-flagged games,
  and unmatched games; never replaces the correspondence of a manual resolution; always updates the
  lengths of the HLTB entry a manual resolution chose; verify with a test per precedence scenario in
  the `hltb-dataset` spec
- [ ] 3.5 Add dataset-aware resolution to `HltbRepository` (cache, then dataset, then network) and
  a not-covered state distinct from unmatched; verify goal tagging queries HowLongToBeat only when
  neither the cache nor the dataset has an answer
- [ ] 3.6 Verify the app is fully usable with no dataset ever applied and with no network —
  every HowLongToBeat surface works and no dataset check is attempted offline

## 4. Contribution export

- [ ] 4.1 Implement the HLTB-only export producing a contribution file containing resolved games
  only, carrying app id, HLTB id, and the four lengths and nothing else; verify with a test
  asserting review-flagged and no-match games are absent and that no playtime, session, achievement,
  streak, or account value appears in the output
- [ ] 4.2 Wire the export to the file picker with the disclosure that the file identifies which games
  the user owns, shown before any file is written; verify declining writes no file and that a
  library with no resolved match reports nothing to contribute instead of writing an empty file
- [ ] 4.3 Verify a file produced by the export is accepted by `tools/hltb-dataset/merge.mjs`
  validation without hand-editing — export and merge agree on the format

## 5. App surfaces

- [ ] 5.1 Add the Settings Completion times section — dataset gathered-at, coverage count, check
  control, contribution control, no library-wide lookup control; verify each scenario in the
  `app-settings` delta, including check-in-flight disabling and a failed check leaving the section
  usable
- [ ] 5.2 Present dataset download and application progress and its outcome, including how many games
  gained lengths, with the Library reflecting them without being reopened; verify by applying a
  dataset while the Library is visible
- [ ] 5.3 Add the not-covered per-game state and the Library filter for uncovered games; verify a
  not-covered game is visually distinct from a no-match game and that a completed lookup clears the
  state
- [ ] 5.4 Move the processed-of-total indicator, per-game outcome log, and stop control from the
  batch refresh onto the explicit multi-selection lookup; verify progress, logging, stopping with
  data retained, and completion routing review-flagged games to the review surface
- [ ] 5.5 Verify the match-review surface and its entry-point count still behave per the `app-ui`
  delta, including that a dataset application removes a resolved game from the review list and
  decrements the count

## 6. Removing the sweep

- [ ] 6.1 Delete `HltbRefreshWorker`, `HltbRefreshTimeoutWorker`, `HltbBatchProgress`, and
  `HltbNetworkConnectivity` along with their tests and Hilt bindings; verify `./gradlew assembleDebug`
  succeeds and no reference to the removed types remains
- [ ] 6.2 Remove `SyncScheduler.refreshHltbNow(force)`, `hltbRefreshStatus`, `hltbRefreshInProgress`,
  `hltbRefreshProgress`, `HltbRefreshStatus`, `hltbRefreshStatusFor`, and the offline-wait store,
  keeping `refreshHltbNow(appIds)`; verify the remaining selection path still enqueues and reports
- [ ] 6.3 Remove `HltbRepository.refreshBatch` and `staleOrMissingAppIds`, `HltbDataDao.appIdsStaleOrMissing`,
  `FRESHNESS_WINDOW_MILLIS`, and `INTER_REQUEST_DELAY_MS`'s batch-only usage, keeping request spacing
  for the selection path; verify `:app:testDebugUnitTest` passes and no code path can look up a game
  the user did not name
- [ ] 6.4 Remove the "Refresh HLTB library" Library trigger and its completion reporting; verify no
  remaining control starts a library-wide lookup
- [ ] 6.5 Swap `SetupStageRegistry`'s `STAGE_COMPLETION_TIMES` runner to the dataset download, keeping
  its id and position, and set `defaultOptIn = true`; verify first-run setup offers it selected by
  default and that it completes in one download

## 7. Verification and documentation

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm the full suite passes
- [ ] 7.2 Verify on device: fresh install with no dataset shows every game as not covered; applying
  a dataset fills the library; a not-covered game resolves via a single-game lookup; an explicit
  multi-selection reports progress and can be stopped
- [ ] 7.3 Confirm no code path issues a HowLongToBeat request for a game the user did not name —
  `grep -rn "HltbDataSource\|searchCandidates\|refreshHltbNow" app/src/main/java` and check every
  caller originates in an explicit user action
- [ ] 7.4 Publish `hltb-dataset-v1` seeded from an export of the maintainer's existing `hltb_data`
  table, run through the merge tool; verify a device discovers and applies it, and that app update
  discovery does not offer an app update on its account
- [ ] 7.5 Update `README.md` and, if the tools directory warrants a mention, `CLAUDE.md` — noting
  that `tools/` is a script directory and not a third build system; verify the two-toolchain build
  table remains accurate as written

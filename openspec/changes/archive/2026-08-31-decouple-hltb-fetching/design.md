## Context

See `proposal.md` — Why. The relevant existing structure:

- `HltbDataSource` is a one-method seam (`search(name): List<HltbCandidate>`) that `hltb-data`'s
  spec already anticipated replacing with something non-scraping. `HltbModule` binds it to
  `ScrapingHltbDataSource` in one line.
- `HltbRepository` owns lookup, classification, and the cache. Every consumer goes through it;
  nothing above `data/` touches `HltbDataSource` or `HltbMatchStatus`.
- The sweep is `HltbRepository.refreshBatch` driven by `HltbRefreshWorker`, with
  `HltbRefreshTimeoutWorker`, `HltbBatchProgress`, `HltbNetworkConnectivity`, and a cluster of
  `SyncScheduler` flows around it. `SetupStageRegistry`'s `STAGE_COMPLETION_TIMES` triggers it.
- `SyncScheduler.refreshHltbNow(appIds: Collection<Long>)` already exists — the explicit-selection
  path this change makes the only multi-game path is not new code.
- `app-updates` already downloads and two-stage-verifies assets from the project's GitHub Releases,
  and already ignores any tag that is not `vX.Y.Z`.
- `BackupExportMapper`/`BackupMergeEngine` establish the file-picker export/import pattern and an
  all-or-nothing validated import.

Constraints from `CLAUDE.md` that bind this design: the app must work with no network and no cloud;
Firestore has no client reader and gaining one needs an auth decision first; repositories expose
domain models and nothing under `ui/` imports a storage type.

## Goals / Non-Goals

**Goals:**

- Delete the sweep outright rather than gate or throttle it — a gated sweep is still a sweep.
- Get the dataset in over transport that already exists and is already verified, adding no new
  backend, no new host, and no per-user request that reveals the library.
- Make the dataset's precedence against local rows decidable from data on the row, not from
  inference.
- Keep the demoted live path — and everything `expand-hltb-match-discovery` builds on it — intact.

**Non-Goals:**

- No submission endpoint, no aggregation service, no Firestore reader. Contributions are pull
  requests.
- No incremental/delta dataset format. At the sizes involved, whole-file replacement is correct.
- No attempt to reach full Steam coverage. Coverage grows by contribution; uncovered is a normal,
  visible state.
- No second data source (IGDB or similar). The seam makes that a later change if wanted.

## Decisions

### Publish one file, not a per-user slice

**Decision:** the dataset is a single file every device downloads whole and filters locally.

A tailored response would need a request carrying the user's app ids — which means a server that
learns what each user owns, plus server-side logic, plus network at query time. That is a privacy
regression and a new backend, to save bandwidth that does not need saving:

| rows | raw CSV | gzipped |
|---|---|---|
| ~500 (initial, seeded from the maintainer's own table) | ~14 KB | ~3 KB |
| ~5,000 (realistic steady state) | ~140 KB | ~30 KB |
| ~50,000 (every Steam game HLTB covers) | ~1.4 MB | ~300 KB |

A row is six integers — the app already has names from Steam sync, so no strings are carried.
Even the ceiling is smaller than one cover image. Whole-file wins on every axis that matters.

**Alternatives rejected:** per-user slice (privacy regression + backend); Firestore collection
(spends the first client read on read-mostly static data, needs an auth decision, and violates
"must not come to require the cloud" in spirit); bundling in the APK (couples data cadence to
release cadence, which the separate tag series exists to avoid).

### Distribute as a GitHub release asset on its own tag series

**Decision:** publish as `hltb-dataset-vN` release tags, separate from the app's `vX.Y.Z` series.

`app-updates` already considers only `vX.Y.Z` tags and ignores drafts and pre-releases, so a
`hltb-dataset-vN` tag is invisible to update discovery **by existing behaviour** — no change to
`app-updates` is needed, and the spec delta reflects that. Data ships when a contribution merges;
app releases stay on their own cadence.

Reuse the download-and-verify machinery `app-updates` already owns rather than building a second
one. It is the same operation against the same host.

**Alternative rejected:** attaching the dataset to each app release. Simpler to publish, but a
data-only fix would then require cutting an app version, which is exactly the coupling to avoid.

### Store the mapping and the lengths as two relations, not one flat table

**Decision:** the published file distinguishes `appId → hltbId` from `hltbId → {4 lengths}`.

Two reasons, one practical and one not:

- Practically, several Steam app ids can point at one HowLongToBeat entry (regional SKUs, editions,
  demos). Keying lengths by `hltbId` stores them once and keeps them consistent across those
  entries by construction rather than by discipline.
- Substantively, `appId → hltbId` is a correspondence table — a fact about two catalogues, not
  HowLongToBeat's content. `hltbId → lengths` is their content. Keeping the boundary legible in the
  format means the question "what exactly is being redistributed" has an answer that points at a
  specific part of a specific file.

Locally the two still land in one `hltb_data` row; the split is a property of the published format
and of the merge tool, not of the schema.

**Alternative rejected:** one flat `appId,hltbId,4 lengths` table. Marginally simpler to parse,
duplicates lengths across sibling app ids, and erases the distinction above.

### Precedence is decided by a stored origin, not inferred

**Decision:** `HltbData` gains a provenance field distinguishing at least *dataset*, *automatic
device match*, and *manual resolution*.

The precedence rule the spec states — dataset beats an automatic match, never beats a manual one,
but may always update the lengths of the entry a manual resolution chose — is not derivable from
the existing columns. `matchStatus = RESOLVED` covers both "the matcher was confident" and "the
user picked it", and those must sort differently against the dataset. Inferring from
`candidatesJson == null` would conflate them.

Splitting `RESOLVED` into two statuses was considered and rejected: match *status* and value
*origin* are orthogonal (a dataset row can be resolved; a manual resolution can later be
superseded by a device lookup), and folding them into one enum makes every existing `when` over
`HltbMatchStatus` wrong in a way the compiler will not point at.

**Migration:** rows written before the column exists read as *automatic device match* — the
conservative default, since it lets the dataset correct them, which is the desired direction for
guesses.

### `fetchedAt` on a dataset row is the dataset's timestamp

**Decision:** a dataset row carries the time its values were gathered, not the time it was applied.

`BackupMergeEngine.mergeHltbData` currently stamps `time.nowMillis()` on import, which is the bug
this avoids: with a freshness gate, that makes imported rows look permanently fresh. The gate is
being deleted, so nothing breaks today — but age is still shown to the user and still orders
"dataset vs. device lookup" decisions, and a row claiming to be gathered at import time is simply
false. Note this makes the same fix worth carrying into the backup import path.

### Delete the sweep; do not gate it

**Decision:** remove `refreshBatch`, both workers, `appIdsStaleOrMissing`, `FRESHNESS_WINDOW_MILLIS`,
and `refreshHltbNow(force)` rather than restricting them.

Scoping the sweep to goals and recently-played was considered — it is a real ~10× reduction and
needs no new component. It was rejected as the *primary* mechanism because it leaves a
library-scaled implicit request path in place, so the volume returns as the library grows, and
because it does nothing for the match-ambiguity problem the dataset also solves. Once the dataset
exists the sweep has no remaining job.

Consequence: with no background HLTB work, the WorkManager retry/backoff requirement
(`A batch that accomplished nothing is not reported as successful work`) has nothing to govern and
is removed rather than reworded. A user-initiated lookup fails in front of the user, and retrying
is their choice.

### The setup stage swaps its runner, not its contract

`first-run-setup`'s spec models setup as an extensible registry and states that registering a
further stage requires no change to the surfaces that present it. So `STAGE_COMPLETION_TIMES` keeps
its id and position and swaps `WorkStageRunner(HltbRefreshWorker)` for the dataset download. Its
`defaultOptIn` moves to `true`: the reason it defaults off today is that it is slow and
network-hostile, and one small download is neither. No `first-run-setup` delta is needed.

### The merge tool is a script, not a build system

**Decision:** `tools/hltb-dataset/merge.mjs`, dependency-free, run as `node tools/hltb-dataset/merge.mjs`.

Node 22 is already established by `functions/`. A script with no `package.json` and no install step
is invisible to both Gradle and the functions build graph, so `CLAUDE.md`'s two-toolchain table
stays accurate as written — this deliberately does not repeat what `add-desktop-agent` does in
adding a third row.

Deterministic output (sorted by app id, fixed field order, stable formatting) is what makes review
tractable: a pull request diff is exactly the rows it adds. CI runs validate-and-regenerate on each
pull request and fails if the committed output does not match, so a malformed contribution bounces
without maintainer attention.

Correspondence conflicts block and lengths do not, because they are different kinds of
disagreement: two different `hltbId`s for one `appId` means one contributor is wrong and only a
person can say which, whereas differing lengths for one `hltbId` is HowLongToBeat's own averages
drifting, where newest-wins is correct and uncontroversial.

### The scraping source stays bound, just called differently

`ScrapingHltbDataSource` is unchanged and stays bound to `HltbDataSource`. The dataset is not a
second `HltbDataSource` implementation — it answers a different question (`appId → values`, no
search, no candidates, no name matching) and belongs beside the DAO in `HltbRepository`'s
cache-then-dataset-then-network resolution order.

This is what keeps `expand-hltb-match-discovery` intact: its broader-query search and pasted-link
resolution operate on one named game through the same seam, which the demoted path still permits.

## Risks / Trade-offs

- **Coverage is thin at launch — the dataset starts as one person's library.** → Seeded from the
  maintainer's existing `hltb_data` table at zero new HLTB requests, and uncovered is a designed,
  visible state with a one-tap lookup beside it, not a failure. The not-covered Library filter makes
  building an explicit selection from the gaps a normal action.
- **A wrong correspondence propagates to every user who applies the dataset.** → It cannot overwrite
  a manual resolution, so a user who has already fixed a game stays fixed; a user who has not can
  fix it locally with a single-game lookup, and the fix is a pull request away for everyone else.
  Correspondence conflicts blocking the merge is the upstream half of the same protection.
- **Contributing publishes which app ids the contributor owns**, in a permanent public diff. →
  Disclosed before the file is written and declinable, per the spec. Worth stating plainly rather
  than burying: this is a real disclosure, not a formality.
- **Redistributing completion lengths is a different posture than each user fetching their own.** →
  The mapping/lengths split localises exactly what is redistributed; the volume reaching
  HowLongToBeat falls by orders of magnitude; no mass scrape occurs at any point, since every row
  traces to one game one user chose to look up. This trade is the change's central bet and it is
  made deliberately.
- **`BREAKING`: users lose one-tap whole-library completion times.** → Replaced by one download that
  is faster and covers more. The regression is real only for a library that is largely uncovered,
  where the not-covered filter plus a multi-selection is the intended path.
- **A dataset row's lengths can be staler than a fresh scrape.** → Accepted. HowLongToBeat lengths
  are community averages that drift slowly; a per-game lookup remains available for anyone who wants
  the current number.

## Migration Plan

1. Land the dataset format, the merge tool, and CI first, with the app untouched. Seed
   `hltb-dataset-v1` from the maintainer's exported table. Nothing ships to users yet.
2. Add provenance to `HltbData` (Room migration, existing rows default to *automatic device match*)
   and the dataset apply path, behind no user-visible change.
3. Add the dataset surfaces — Settings section, not-covered filter and state, contribution export.
4. Remove the sweep in one commit: workers, `refreshBatch`, freshness constants, `SyncScheduler`
   flows, the Library trigger, and swap the setup stage's runner.

Ordering matters: the dataset must be able to fill the library before the sweep that fills it today
is removed, so no version ships that can do neither.

**Rollback:** steps 1–3 are additive and safe to leave in place. Step 4 is the irreversible one; if
it has to be undone, it is a revert of a single commit, and the dataset rows already applied remain
valid because provenance lets the restored sweep's freshness gate treat them correctly.

## Open Questions

- Exact serialization of the published file (CSV vs. newline-delimited JSON) and whether the two
  relations are two files in one archive or two sections of one file. All three of the size, the
  determinism requirement, and the mapping/lengths split are satisfied by any of these; this is a
  formatting choice that can be settled during implementation without touching the specs.
- The plausibility ceiling for a completion length used by validation. Needs a number; the choice
  does not affect any other decision.
- Whether the not-covered Library filter is its own filter or a value in an existing
  HowLongToBeat-status filter. A presentation detail the spec deliberately leaves open.

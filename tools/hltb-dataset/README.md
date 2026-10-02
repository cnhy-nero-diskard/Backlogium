# HLTB dataset tooling

This directory owns Backlogium's shared Steam-to-HowLongToBeat correspondence and completion
length dataset. The tool requires Node 22 and has no install step or third-party dependencies.

Read [FORMAT.md](FORMAT.md) for the exact schema, units, release asset names, and canonical
serialization.

## Contribute an export

1. In Backlogium, choose the completion-times contribution export and read its disclosure.
2. Save the file as `backlogium-hltb-contribution.json` outside this directory.
3. Validate and merge it into the repository copy from the repository root:

   ```text
   node tools/hltb-dataset/merge.mjs tools/hltb-dataset/dataset.json path/to/backlogium-hltb-contribution.json
   ```

4. Run the same local gate as CI:

   ```text
   node --test tools/hltb-dataset/test/*.test.mjs
   node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/dataset.json
   ```

5. Review and submit the resulting `tools/hltb-dataset/dataset.json` diff. A normal contribution
   changes only the tuples it adds or refreshes plus the dataset metadata.

`--check` validates every value, regenerates the canonical bytes in memory, and fails if those
bytes differ from the committed file. Running the merge command without a contribution also
canonicalizes the repository copy. `--output <path>` writes the result elsewhere instead.

## What the export reveals

The export identifies the Steam app ids whose HLTB matches are resolved in your library. Sharing
it therefore reveals which of those Steam apps you own, and a merged pull request publishes that
list. It also contains the corresponding HLTB ids and four completion-length fields.

It does not contain a Steam account id, username, game names, playtime, sessions, achievements,
streaks, or unresolved/review-flagged matches. Inspect the JSON before contributing if you want to
confirm the exact rows you are publishing.

## Provenance of the seed data

The canonical dataset's initial 273 mappings were seeded from the maintainer's own Steam library
across two commits (`a843ed4`, `ea37d49`) whose messages say so explicitly, which makes the
library-ownership disclosure described above recoverable from public git history for those rows
specifically, not just from a fresh export. **This was intended and is accepted** — those commits
are not a mistake to remediate, and no dataset or history change follows from this note.

The norm for future contributions stays what "What the export reveals" already states: contributing
publishes the ownership disclosure for the rows you add. Say so in the pull request if you'd rather
not have your own contribution attributed to you by name in the commit message — the dataset itself
never needs your identity, only the commit history of *this* seed does.

## Resolve a correspondence conflict

The tool never guesses when the canonical dataset and a contribution map one Steam app id to two
different HLTB ids. It exits before writing and reports the app id plus both correspondences.

Check the Steam store entry and both HLTB entries manually. If the canonical mapping is right,
remove or correct that row in the contribution and rerun the merge. If the contribution is right,
make a deliberate reviewed correction to the canonical mapping, then rerun the contribution so it
becomes redundant. Differing lengths for the same HLTB id need no manual resolution: the file with
the later `gatheredAt` wins automatically.

## Maintainer commands

```text
# Show CLI help
node tools/hltb-dataset/merge.mjs --help

# Validate, regenerate in memory, and compare bytes
node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/dataset.json

# Run all dependency-free tests
node --test tools/hltb-dataset/test/*.test.mjs
```

Published releases use tag `hltb-dataset-vN`, asset `hltb-dataset.json`, and checksum asset
`hltb-dataset.json.sha256`; `N` must match the file's `datasetVersion`.

## Refresh selected libraries

The dependency-free Node 22 `refresh.mjs` CLI resolves the current owned libraries of explicitly
selected SteamID64s. Set `STEAM_WEB_API_KEY` privately in the process environment. The key is
never accepted as a command argument or written to state. Target IDs are validated as individual
Steam account IDs; public output contains aggregate library counts, never account attribution.

```text
node tools/hltb-dataset/refresh.mjs --help
node tools/hltb-dataset/refresh.mjs --steam-id <id64> --steam-id <other-id64> --dry-run
node tools/hltb-dataset/refresh.mjs --steam-id <id64> --mode stale --max-age-days 30 --app-id 620
node tools/hltb-dataset/refresh.mjs --steam-id <id64> --mode refresh-all --max-requests 8 --max-run-seconds 90 --app-id 620 --output tools/hltb-dataset/.local/candidate.json --report tools/hltb-dataset/.local/report.json
node tools/hltb-dataset/refresh.mjs --steam-id <id64> --reviewed-mappings <reviewed-schema-v1-file>
```

IDs may be repeated; libraries are unioned and deduplicated. Repeated `--app-id` restrictions
intersect that union and never add unowned games. Missing-only (the default) fetches mapped
entries without lengths. Stale also fetches unknown/incompatible local evidence or evidence at
least the requested age. Refresh-all fetches every reviewed unique entry within selected scope.
An explicitly readable empty library is valid; private, missing, malformed or inaccessible library
responses are unavailable. Mixed readable/unavailable scope is partial; no readable library
blocks. The CLI never falls back to the entire canonical dataset.

Reviewed mapping input uses the existing contribution format. Only new correspondences inside
selected scope can be admitted. Its length values are ignored as fetch evidence. Any disagreement
with an existing correspondence blocks output, including disagreements outside the selected
scope. Unmapped games remain in the report for human review; the tool does not guess matches.
Publishing new mappings or game-specific report gaps discloses selected owned app IDs. Reports
may include public game titles, but never account names, IDs, profiles or user-to-game attribution.

Requests run serially, at least one second between starts, with a 15-second timeout covering the
body and a 5 MiB body ceiling. Each transient transport/5xx/429 failure permits at most two retries
with bounded exponential backoff and jitter. Valid Retry-After delays are respected if they fit
the remaining budget. Defaults are 500 attempts including Steam/retries and 900 active seconds.
Access-denied/challenge HLTB responses stop further work. No access-control workaround is used.
Only recognized structured game-page fields with a matching entry ID are accepted. `comp_*`
seconds are rounded to nearest integer minutes; zero/absent/null means unknown, and malformed,
negative or excessive known values fail. Positive sub-minute values can round to zero minutes.

Default candidates, reports, observation history and frozen runs live in ignored `.local/`.
Custom state must be under that directory or outside the repository. Outputs must differ from
source, reviewed mappings, history and checkpoint paths, including symlink/hard-link aliases.
The CLI leaves `dataset.json` untouched. Inspect and validate the separate candidate before any
deliberate contribution; running the tool grants no Git or PR publication by itself.

Resume with `--resume <runId>` from an execution report, repeating the original Steam IDs,
app restriction, mode/threshold, reviewed input, request/runtime limits and state directory.
Baseline bytes, mapping input hash, options and state version must match; output/report locations
can change. The frozen scope is retained without re-reading Steam. Saved terminal observations
(including failures/no-lengths) are not re-fetched. Remaining work is fetched with original
attempt/active-time budgets; exhaustion requires a new run. Actual saved observation times never
change. A crash between candidate/report writes is recoverable from a compatible checkpoint.
Consumers must require both files with matching hashes; one file alone is not a publishable pair.
Missing or incompatible history makes entries unknown/stale. Applicable history binds the HLTB
mapping identity, observed values and resulting candidate values; it becomes usable against a
baseline only when those canonical values match. Successful unchanged reads advance evidence;
failed or all-unknown reads preserve previous values and successful times.

A dry run reads Steam and existing evidence, reports eligible `plannedHltbIds`, and performs no
HLTB requests or candidate/checkpoint/history writes. Only an explicitly requested `--report`
is written. Exit codes are 0 for complete/clean plan, 2 for usable partial/partial plan, and 1
for blocked/invalid. Always inspect report outcome and coverage before consuming output.

### Report version 1

Public reports use allowlisted fields: `toolVersion`, `reportVersion`, opaque `runId`, `mode`,
`outcome` (plan-only/complete/partial/blocked), `partialCoverage`, `changed`, baseline/candidate
SHA-256/version/global timestamp, aggregate libraries and scope, planned HLTB IDs, added mappings,
changed tuple IDs, affected apps with four old/new integer-or-null styles and direct-target flags,
checked/untouched totals, unmapped games, mapping conflicts, no-lengths/failures/unattempted IDs,
attempt/retry/backoff/budget summaries and fixed limitations. No raw exceptions, responses, keys,
account attribution or private paths are serialized. Candidate fields are null for plans/blocks.

`checked.unchangedEntries` counts successfully observed selected unique entries whose four
values equal the baseline, including shared entries once. `unchangedTargetedApps` counts targeted
app aliases of those entries. `untouched.canonicalApps` and `canonicalLengthEntries` count baseline
rows with no actual relation change; those totals can include checked unchanged or failed rows.
New mappings and every app alias affected by a changed shared tuple are listed, including indirect
aliases outside requested scope; this expands reporting only, never fetch scope. No-lengths,
unmapped, failed and unattempted outcomes are separate. They make execution partial, as do
unavailable libraries and exhausted budgets. Dry runs distinguish plan-only from partial coverage.

Schema-v1 `gatheredAt` remains global merge metadata. Existing Android consumers may assign that
age to imported failed/untouched rows. Local entry history and this report do not migrate that
consumer behavior or claim that every row was refreshed. See [FORMAT.md](FORMAT.md).

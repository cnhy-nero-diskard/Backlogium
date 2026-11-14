## Why

Issue [#162](https://github.com/cnhy-nero-diskard/Backlogium/issues/162), ordered by bullet 7 of [#174](https://github.com/cnhy-nero-diskard/Backlogium/issues/174), needs a manually runnable way to refresh completion times for selected Steam users. Existing tooling validates and merges contributions but cannot resolve those users' current libraries or fetch fresh HLTB observations.

## What Changes

- Add a Node 22 CLI under `tools/hltb-dataset/` accepting one or more SteamID64s and targeting the deduplicated union of their readable owned libraries.
- Provide missing-only, stale with an age threshold, and refresh-all modes within that scope, optional app-ID intersection, a planning-only dry run, and resumable runs.
- Fetch completion lengths by reviewed HLTB ID. Accept explicitly reviewed mapping contributions; report unmapped titles and correspondence conflicts without automatically guessing or replacing mappings.
- Reuse schema v1, the canonical serializer, validator, and merge/version rules. Retain existing rows after failed reads and produce a machine-readable report for the dependent PR skill.
- Bound requests, retries, and backoff; persist private per-entry observation timestamps and resumable state. Explain the global `gatheredAt` limitation and exclude credentials/account identities from public output.
- Validate the CLI independently before implementing `2d-add-hltb-refresh-pr-skill`.

## Capabilities

### New Capabilities

- `hltb-dataset-refresh`: Selected-library targeting, reviewed mapping admission, bounded fetching, resumable observations, canonical output, and a sanitized result/report contract.

### Modified Capabilities

None. The existing `hltb-dataset` format, Android consumption, contribution merge rules, and release series remain the compatibility contract.

## Impact

- New refresh modules and tests alongside `tools/hltb-dataset/merge.mjs`; reuse its exported validation/merge/serialization functions.
- Extend `tools/hltb-dataset/README.md`, document freshness limitations in `FORMAT.md`, and ignore private local run artifacts.
- Extend the existing PR validation job to include offline refresh tests; live network tests remain manually bounded.
- Steam owned-library reads and direct HLTB game-page reads occur only during a maintainer invocation. No scheduled crawl or Android app change.
- Planning order prefix `1d` is part of the change name; schema artifact filenames keep their standard names. This proposal authorizes no fetch, dataset edit, PR publication, or release.

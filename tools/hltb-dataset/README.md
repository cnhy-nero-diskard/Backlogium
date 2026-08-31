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
   node --test tools/hltb-dataset/test/merge.test.mjs
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
node --test tools/hltb-dataset/test/merge.test.mjs
```

Published releases use tag `hltb-dataset-vN`, asset `hltb-dataset.json`, and checksum asset
`hltb-dataset.json.sha256`; `N` must match the file's `datasetVersion`.

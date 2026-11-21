# Proposal

## Why

The selected-library refresh can fetch known or explicitly reviewed HLTB IDs, but it cannot discover candidates for uncovered Steam games. Maintainers need a bounded search-to-review handoff that grows dataset coverage without turning title similarity into an unreviewed public correspondence.

## What Changes

- Add an explicit discovery stage restricted to unmapped apps in selected readable Steam libraries and any app-ID intersection; never enumerate the HLTB catalog.
- Reuse the Android search protocol, title normalization, candidate ranking, and optional bounded broader-query behavior where practical, but require human approval even for exact-title results.
- Save versioned review artifacts under ignored `.local/`, including Steam/HLTB identities and titles, four-style length previews, discovery outcomes, and pending/approved/rejected decisions.
- Allow the existing refresh command to consume reviewed candidate artifacts alongside the unchanged schema-v1 `--reviewed-mappings` route. Only approved new pairs may enter the plan; conflicting correspondences block candidate output.
- Require a direct-page observation for newly approved mappings, including aliases of HLTB IDs that already have lengths. Search previews never become fetched values or successful-fetch evidence.
- Preserve the complete baseline dataset, canonical merge semantics, failure preservation, shared-ID deduplication, bounded requests, private state, and separate apply/publication gates.
- Report discovery, decisions, mapping gaps, and fetch outcomes separately; document the complete workflow in the dataset README and refresh skill with offline fixture acceptance checks.

## Capabilities

### New Capabilities

- `hltb-dataset-discovery`: Library-scoped candidate discovery, private review artifacts, explicit approval, and a bounded handoff to dataset fetching.

### Modified Capabilities

- `hltb-dataset-refresh`: Admit approved review artifacts, fetch new correspondences independently of existing shared lengths, bind review input to resumable plans, and report review dispositions separately from observations.

## Impact

- Dependency-free Node 22 tooling in `tools/hltb-dataset/`, especially targeting, request transport, options, planning, storage, and reporting; a separate discovery CLI and fixture tests.
- `.claude/skills/hltb-dataset-refresh/` instructions, command reference, integrity-check compatibility, and workflow tests; `tools/hltb-dataset/README.md` and format documentation for the private artifact.
- Android HLTB code/tests are behavior references, not implementation targets. No Android matching-policy, UI, Room, public dataset schema, or release-workflow changes.
- No live network acceptance is implied by fixture tests. This planning session does not implement the change, apply a candidate, edit main specs, commit, push, or open a PR. Later implementation must retain those apply/publication restrictions.

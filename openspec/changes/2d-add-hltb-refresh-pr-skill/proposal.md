## Why

Issue #162's refresh CLI needs an agent workflow that can enter at fetching, resume,
inspection or publication without mistaking one stage's permission for every later step.
Users need a truthful report and explicit control over requests, canonical writes and PRs.

## What Changes

- Add a discoverable `hltb-dataset-refresh` repository skill with stage-aware routing.
- Gate credentials, fetch scope/budgets, recovery, mappings, application and publication
  on user authority; honor explicit stacked PR bases.
- Add a read-only integrity checker using the existing canonical schema and report-v1 contract.
- Add offline integrity tests, skill scenario cases and documentation.

## Capabilities

### New Capabilities
- `hltb-refresh-assistance`: consent-aware agent orchestration and candidate inspection.

### Modified Capabilities
None. The fetch CLI, report format and published dataset schema remain unchanged.

## Impact

Instruction files, a dependency-free offline checker, tests and HLTB CI coverage only.
No live fetch, canonical dataset edit, account disclosure, data PR or release is part
of creating this skill. This change is stacked on `feat/hltb-targeted-dataset-refresh`
at the user's explicit request, with the supported tool available on that base.

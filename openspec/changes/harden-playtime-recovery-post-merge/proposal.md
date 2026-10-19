## Why

[Issue #183](https://github.com/cnhy-nero-diskard/Backlogium/issues/183) makes post-merge stabilization of [PR #182](https://github.com/cnhy-nero-diskard/Backlogium/pull/182) a P0 project-priority blocker before unrelated development. Preserve the accepted recovery model while resolving the verified backup-validation mismatch, investigating accounting/concurrency risks, and measuring repeated database work; breadth and line count alone do not justify a rewrite.

## What Changes

- Inventory the complete recovery pipeline and maintain a risk-ranked register that separates verified defects, unverified risks, observed performance patterns, and optional cleanup. Each high-risk item needs a reproduction and fix or evidence proving it safe.
- Align v3 backup preflight with positive baseline-epoch requirements before any writes; audit adjacent representability, identity, reference and quantity checks without claiming untested cases are defects.
- Specify coherent canonical read snapshots and operation-isolated corrective/earned finalization, then use controlled concurrent-reader/worker fixtures to determine whether the current implementation meets those contracts. Make only evidence-supported fixes.
- Measure normal sync, delayed reconciliation, restore and History/Analytics reads on realistic history; reduce repeated history scans and write amplification where justified, retaining conservation and bounded date expansion.
- Verify the full quantity, persistence, migration, backup, account, progression, UI and Personal Pace matrix and publish a final audit note with explicit evidence, retained complexity and unresolved blockers. Isolated reruns do not substitute for full stabilized-head gates.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `playtime-recovery`: explicitly require coherent canonical projections across committed recovery revisions, so valid concurrent updates cannot expose duplicate or inconsistent credited activity.
- `backup-restore`: explicitly require positive recovery epochs and preflight compatibility with the live recovery writer, including rejection before mutation.
- `progress-events`: explicitly preserve earned-event eligibility across overlapping recovery operations and interrupted corrective/earned finalization.

Existing Steam authority, rounding, uncertainty, allocation dates, cloud evidence precedence, UI disclosures, confidence thresholds and backup v1/v2/v3 compatibility remain normative. Other capabilities receive regression verification, not policy changes.

## Impact

Depends on the completed, archived [`recover-missed-playtime`](../archive/2026-10-17-recover-missed-playtime/proposal.md) design and current canonical specs, not on reapplying or editing that archive. Planning starts from current `master` at `96af1b72af83a6c5349374710d3e4691fa326b22` on new branch `fix/harden-playtime-recovery-post-merge`.

Likely implementation seams are the shared observation committer/adapters, recovery DAO, attribution/replacement planners, canonical projections/repositories, finalizer and worker coordination, backup validation/merge, and directly necessary regression fixtures. No new API, dependency, cloud deployment or database version is assumed. Any justified schema/index change needs an additive migration and compatibility evidence first.

Implementation will use small reviewable commits/PRs linked to #183 and this single change; inseparable fixes may share a focused PR. The issue's full A-E checklist and seven acceptance gates are mapped in design.md/tasks.md. No unrelated feature, routine fix or implementation is bundled; only an independently urgent security/data-loss/outage fix may supersede this priority.

## Non-goals

- Implement application code during this proposal or claim implementation/audit acceptance is complete.
- Rewrite #182 on principle, add a second detector/poller/XP author, fabricate session times, or change accepted recovery semantics without an explicit spec revision.
- Overhaul unrelated UI, finish unrelated OpenSpec changes, impose class-size targets, or extract wrappers merely to hide complexity.
- Delete, rename, reset, force-push, repurpose or otherwise modify retained branch `feat/recover-missed-playtime`; rewrite archived #182 artifacts; downgrade schema or discard recovery history.

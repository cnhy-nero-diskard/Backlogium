# Proposal

## Why

HLTB discovery can fail while initializing its shared search session, before sending any title, but attributes that failure to the first app. The CLI also omits the initialization headers used by Android; one explicitly authorized GET with Android's headers succeeded, so aligning the request contract is supported without claiming that a particular header or upstream error value has been proven causal.

## What Changes

- Use a consistent Android-aligned HLTB discovery header profile for initialization, endpoint discovery, and search POSTs; leave Steam and direct-page refresh defaults unchanged.
- Distinguish shared session-setup failures from app search failures, including setup after session expiry; stop setup failures once the existing bounded transport policy is exhausted.
- Mark apps whose search POST never started as unattempted, preserve earlier results, and report a sanitized run-level setup failure in execution and offline inspection.
- Report blocked/exit 1 when setup prevents all searches, and partial/exit 2 when prior search results exist. Keep successful empty search results distinct from failures.
- Preserve existing version-1 reviews and approval bindings while allowing strictly validated optional setup-failure metadata.
- Add offline regression coverage and documentation; do not retry denials or infer a root cause from arbitrary response text.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `hltb-dataset-discovery`: Consistent session request headers, truthful session/app failure attribution, compatible diagnostics, and blocked versus partial outcomes. This capability is introduced by dependent change `add-reviewed-hltb-game-discovery` in PR #191 and is not yet synced to main specs; this follow-up modifies that capability rather than introducing a duplicate.

## Impact

- `tools/hltb-dataset/discovery-search.mjs`, `discover.mjs`, `review.mjs`, and narrowly scoped transport error metadata in `refresh-network.mjs`/`refresh-options.mjs` as needed.
- Dataset fixture tests, README/FORMAT/help, and HLTB assistance instructions where outcome meanings change.
- No Android, canonical dataset, main spec, release workflow, credential, or existing-worktree changes.
- Deliver from `fix/hltb-discovery-tooling-failonrequest` against `feat/hltb-new-game-discovery`, so #191 can be tested before landing. Do not merge #191 to unblock the fix or open a feature-sized direct mainline patch. If #191 lands first, explicitly re-evaluate the fix base before retargeting to `master`.

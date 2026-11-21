# Tasks

All automated requests below use fixtures. The single authorized live initialization GET is already complete; these tasks do not authorize further live requests or merging PR #191.

## 1. Discovery request contract

- [x] 1.1 Add a shared discovery-only Android-aligned header profile across initialization, endpoint-discovery GETs, and search POSTs. Verify exact header parity and token-only/honeypot sessions in `discovery-network.test.mjs`, unchanged Steam/direct-page defaults in transport tests, and document the contract beside the helper and in the dataset README.
- [x] 1.2 Preserve validated HTTP status on internal categorized request errors where needed and cancel unread denied bodies safely. Verify 401/403 stop without retry/fallback, streaming cleanup, existing timeout/body limits, and sanitized category/status behavior in network fixtures; document that raw upstream error values are neither persisted nor inferred.

## 2. Compatible session diagnostics

- [x] 2.1 Extend strict review-v1 validation with optional exact `sessionFailure` metadata and expose it through review summaries. Verify legacy review bytes remain consumable without mutation, malformed phases/categories/statuses/unknown fields fail, and approved-pair refresh/hash behavior remains intact in review and refresh tests; document the exact optional shape and old-reader limitation in FORMAT.md.
- [x] 2.2 Separate session readiness failures from app POST attempts, stop exhausted setup once per run, preserve earlier outcomes, and return blocked/1 versus partial/2 as specified. Verify initial/renewal/endpoint-discovery failures, transient/malformed/budget stops, empty scope, unsafe titles, denied POSTs, and broader-search preservation with injected requests and clocks. Update CLI help and README outcome meanings with the behavior.
- [x] 2.3 Persist allowlisted setup metadata in failed reviews and expose it in execution and credential-free offline inspection. Verify zero-network inspection, correct per-app unattempted accounting, earlier candidate decisions and unmatched outcomes, and no keys/account IDs/tokens/raw bodies/URLs/private paths in persisted output. Update assistance instructions for blocked setup versus per-game failure and separate live-search approval.

## 3. Integration acceptance

- [x] 3.1 Run Node 22 dataset tests, skill tests, canonical dataset check, strict validation of this and the dependent discovery change, and `git diff --check`. Record actual commands/results and the distinction between fixture evidence and the earlier initialization-only live observation in this change's validation record.
- [x] 3.2 Inspect the diff against `origin/feat/hltb-new-game-discovery` and verify only scoped fix code/tests/docs/artifacts change, with no canonical dataset, Android, main spec, predecessor artifact, or release-workflow edits; verify the occupied discovery worktree remains untouched and record intended base/head for publication.

## Workflow follow-up

- Publish scoped fix commits to `origin/fix/hltb-discovery-tooling-failonrequest` when implementation/publication is authorized; a fix PR targets `feat/hltb-new-game-discovery`, not `master` while #191 remains open. Creating or merging a PR is a separate requested workflow.
- After reviewed integration into #191, obtain an explicit bounded grant for live end-to-end search testing. Do not merge #191 merely to unblock testing.
- Sync/archive after the introducing `add-reviewed-hltb-game-discovery` delta has been synced; if #191 lands first, verify the new base before retargeting to `master`.

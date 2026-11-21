# Implementation overlap

Reconciled against `master` at `a17cadb8`. [#188](https://github.com/cnhy-nero-diskard/Backlogium/pull/188) is the shipped first-run implementation. [#199](https://github.com/cnhy-nero-diskard/Backlogium/pull/199) collected this change's October artifacts for later reconciliation.

| Area | Landed behavior to preserve | Scope of the follow-up |
| --- | --- | --- |
| Setup and onboarding | Exact-ID recovery for the current stage, truthful backoff, Retry, editable selection, Continue, and an optional history choice before Home. | Durable attribution per stage, later admission while an earlier stage is pending, and account-bound import consent and dismissal. |
| Steam sync and presence | Distinct manual and periodic work names, KEEP reuse, transaction-time quantity baselines, post-play targeted sync, and placement-only presence attribution. | Attach results and baseline proof to the accepted poll. Extend the current transaction without changing activity authorship, scheduling, or timing policy. |
| Playtime recovery | The canonical per-game credit includes sessions, dated unknown-time allocations, and pending date-unknown quantities. | Use that canonical quantity for frozen-offset subtraction and cover both dated and undated recovered play in import tests. |
| History and cloud re-filing | One shared import operation, atomic offset capture, recoverable derived state, and a reversal-before-reset guard for transferred imported minutes. | Add durable confirmation and account/request/source provenance. Preserve cloud receipts, applied quantities, and placement/reversal behavior. |
| Persistence | Current Room version 45 includes missed-playtime recovery and achievement schemas. | Append any migration from the current schema in the implementation branch and keep the existing migration chain. |

The delta modifies the existing requirement blocks for setup recovery, first-run history interruption, import readiness and integrity, and Settings. The new Steam sync and import requirements cover poll attribution, confirmation, consent ownership, and pending administrative provenance. The existing presence placement, cloud re-filing, and achievement contracts continue to apply.

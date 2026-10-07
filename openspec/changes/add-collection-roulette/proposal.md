## Why

PR #84 contains a small optional way to choose among a custom collection's games. Preserve it separately from the 45 feedback items, with its eligibility and fairness contradictions resolved, so closing the old umbrella does not lose the idea.

## What Changes

- Offer roulette only on an operable custom collection overview with at least two eligible visible members.
- Choose uniformly from the actual per-spin pool; exclude the immediately previous result when another eligible game exists.
- Explain persistent exclusions separately from the current spin's no-repeat exclusion, and show the count actually sampled.
- Keep the result/history in memory for the current visit only.
- Offer game detail and, for ordered queues, an explicit move-next action using normal collection mutation.
- Honor reduced motion; spinning remains silent and writes nothing.

## Capabilities

### New Capabilities

- `collection-roulette`: eligibility, sampled-pool fairness, no-repeat behavior, transient state, and explicit actions.

### Modified Capabilities

- `app-ui`: optional overview entry/result surface with accessible motion treatment.

## Impact

Planning only and optional. No schema, persisted pick history, network request, permission, desktop agent, or launch integration. Existing custom-collection ordering and global hidden-game policy remain authoritative.

Recovered from #84 at `5c1296e`, reconciled against master `d46a711c`. Preserved separately through #174; not a solution to #168 and not one of the 45 requested feedback items. If irreversible collection archival is added later, archived collections must remain inoperable here without coupling this proposal to that implementation.

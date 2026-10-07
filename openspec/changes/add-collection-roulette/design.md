## Context

See proposal.md. Custom collections already provide overview/detail and ordered-queue mutation. Derived collections have their own read-only behavior. Hidden games already leave ordinary surfaces, and completed achievements do not necessarily mean a player considers a queue member done.

The old review identified a contradictory fairness claim: the persistent eligible pool and the actual re-spin pool were described as if every member had equal probability even when the last result was deliberately excluded.

## Goals / Non-Goals

**Goals:** an optional local decision action with honest probabilities and explicit consequences.

**Non-Goals:** recommendations, whole-library roulette, persisted pick history/preferences, automatically changing goals/completion, launching games, or implementing collection archival.

## Decisions

### Define two pools

Persistent eligible set E is the distinct current visible/operable custom members minus members explicitly marked done by an ordered queue. Enforce the global hidden/unavailable-member policy first. Do not exclude by playtime, HLTB length, achievement completion, recency, rarity, or popularity.

For each spin, P = E minus the previous result if that result is in E and E has alternatives; otherwise P = E. Uniform means equal probability for every member of P, not every member of E during a no-repeat re-spin. Disclose “chosen from N” using |P| at that selection, with persistent done/hidden/unavailable exclusions separated from the transient last-result exclusion. Do not reveal hidden titles.

Two eligible members alternate on re-spin. A three-member set can return to the first pick after an intervening pick. If E shrinks to one while the surface is open, keep a still-valid result inspectable but disable re-spin; if it becomes empty or the result becomes invalid, invalidate the result/commit actions and explain that the collection changed. Initial entry requires at least two eligible members.

### Selection is deterministic under an injected random index

Use a pure domain selector receiving the actual candidate list and an injected uniform index source. Deduplicate by app ID with stable ordering before sampling. Test every index over concrete pools and the no-repeat logic rather than statistical frequency tests. UI owns current-visit state, never DAO/DataStore.

Membership can change while decoration is running. Bind a result to the sampled revision/pool, revalidate on reveal and before action, and invalidate stale results instead of silently committing a removed/hidden/done game. Cancel/leave discards previous pick state.

### The result offers explicit existing actions

The result identifies its game and sampled count, offers Open game and Spin again when alternatives exist. An ordered queue additionally offers Move next using the collection's existing mutation boundary. Preserve done-member positions and relative order of all other members; move the pick to the first not-done slot. Re-read/validate current membership and queue state at commit and never overwrite intervening changes with a stale list. Basic/deadline lists expose no queue mutation.

Opening/spinning/dismissing never writes. Move next is the only committing action in scope and gets the existing success intent only after persistence succeeds. Do not add remote-launch placeholders to the UI or couple this local picker to desktop-agent proposals.

### Motion is decorative

Use existing collection/theme styling, clear text/icon hierarchy, reachable labels, and a result readable without animation. Reduced motion reveals directly; navigation/spin/reveal produces no new haptic. Do not use milestone gold for arbitrary selection or imply a recommended “best” result.

Only custom overview owns the action: no Home teaser, management form, derived collection, or future archived collection entry. Do not change archived status or history.

## Risks / Trade-offs

- No-repeat selection is not uniform over all eligible members → state the actual sampled pool and transient exclusion.
- Eligibility changes during animation/action → revision validation, explicit invalidation, current atomic mutation.
- A selection can look like a recommendation → plain random-choice wording without ranking or predictive copy.
- Irreversible archived collections may arrive separately → consult their operability gate later; no archival implementation in this draft.

## Migration Plan

No storage or data migration. Implement selector and ephemeral state first, then overview/result actions and reduced-motion presentation. Keep this optional draft separately reviewable; it has no prerequisite on the reported feedback fixes.

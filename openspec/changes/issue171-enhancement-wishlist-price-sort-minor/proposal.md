# Proposal

## Why

Players checking their wishlist cannot quickly find what they can afford. [Issue #171](https://github.com/cnhy-nero-diskard/Backlogium/issues/171) also asks when a deal ends, which requires verified offer-level data beyond the price currently displayed.

## What Changes

- Add price low to high and price high to low alongside the existing deals-first order in the expanded wishlist.
- Sort by Steam's recorded payable amount within each currency. Keep dated retained prices available offline, and distinguish an explicit zero price from an unavailable amount.
- Evaluate sale-deadline sources against storefront, offer identity, support, and request-volume requirements. Ship sorting independently and use the feasibility result to decide whether to propose a countdown follow-up.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `wishlist` adds section-local ordering by observed payable price and defines how freshness, multiple currencies, and missing values affect that ordering.

## Impact

- Affects wishlist price observations, the repository-to-UI model, and the Library wishlist in all three densities.
- Sorting uses the existing cache and price refresh path. The countdown feasibility work uses a bounded sample of public Steam offers.
- No prerequisite OpenSpec changes. Library search already provides a separate relevance order for cached wishlist matches.

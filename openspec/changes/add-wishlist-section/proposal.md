# Wishlist Section

## Why

The app knows everything about what the player owns and nothing about what they want. A wishlist is
the other half of a backlog — the queue in front of the queue — and today it lives entirely in
Steam, where checking a price means leaving the app that knows what the price would be competing
with.

The retrieval cost turns out to be small, and that is what makes this worth doing now rather than
treating it as a whole subsystem. Two probes against a live profile settled the design:

- **Prices batch.** `appdetails?appids=440,570,730&filters=price_overview` returns three separately
  keyed entries in one request. A wishlist of any realistic size is a handful of requests, not one
  per game — so no background worker, no batch cap, no progress UI, and no conflict with the
  serial-issuance position `optimize-steam-sync` established for fan-out request paths.
- **Absent prices are explicit.** An app with no price returns `"data": []`. Free-to-play,
  unreleased, and region-restricted titles are therefore distinguishable from a failed lookup
  rather than needing to be inferred.

That second result also exposes a hazard worth naming up front: the existing `StoreAppDetails` DTO
types `data` as an object, and deserializing `[]` into it throws. One free-to-play game in a batch
would fail the entire response, not merely its own entry.

## What Changes

- **A wishlist section** listing the player's wishlisted games in Steam's own priority order, with
  artwork, name, current price, and any active discount.
- **Prices refreshed when the section is viewed**, batched into as few requests as the endpoint
  allows, and cached so the section works offline.
- **Prices are always dated.** A cached price states when it was observed. The app never presents a
  stored price as the current one.
- **No price is a first-class state**, not an empty field: free-to-play, unreleased, and
  unavailable-in-region are shown as what they are.
- **Prices are requested without deriving a store region from the player's public profile location.**
  When an explicit store-country setting exists, it is passed as `cc`; otherwise `cc` is omitted and
  Steam resolves the region for the request.
- **Every entry links to its Steam store page**, which opens the Steam app directly when installed.
- **Price observations are recorded over time.** No alerting and no history UI in this change — the
  point is that history is cheap to start accumulating and impossible to backfill, so recording
  begins now and the surfaces that need it can arrive later.
- **A wishlisted game the player already owns is reconciled** rather than shown as still wanted.
- **Wishlist unavailability degrades to an empty state**, never to a broken section: a private
  wishlist, a withdrawn endpoint, or no network each leave the rest of the app untouched.

## Capabilities

### New Capabilities
- `wishlist`: how the wishlist is retrieved and ordered, how prices are requested, dated, and
  cached, what the absence of a price means, how observations accumulate over time, how an owned
  wishlist entry is reconciled, and how the feature degrades when Steam will not answer.

### Modified Capabilities
- `steam-sync`: the sync additionally persists the player's persona identity and retains a
  `storeRegion` value only for a future explicit store-country setting; until one exists, price
  requests omit `cc` rather than using the public profile location.
- `app-ui`: the Library gains a wishlist section, with its entries, ordering, states, and store
  links.

## Impact

- **Reuses the existing store client.** `SteamStoreApi` already exists, credential-free and wired
  through `NetworkModule` for genre enrichment. This adds a batched, price-filtered call beside it.
- **A separate DTO for price lookups.** `StoreAppDetails.data` is typed as an object and cannot
  represent the `[]` that a price-filtered request returns for an app with no price. The price path
  gets a DTO tolerant of both shapes; the genre path is left untouched.
- **Affected code (new):** a wishlist retrieval call on `SteamApi`; a batched price call and DTO on
  `SteamStoreApi`; `wishlist_items` and `wishlist_price_observations` tables with their migration; a
  repository exposing wishlist entries as domain models; the section and its entry composables.
- **Affected code (modified):** `PlayerSummariesDto` and `PlayerProfile` to carry persona name and
  avatar, plus a `storeRegion` column reserved for a future explicit store-country setting — the
  response's `loccountrycode` is the public profile location and is never used as one; the Library
  screen to host the section.
- **Wishlist entries are deliberately not rows in `games`.** They are not owned, have no sessions,
  and must not enter library counts, XP denominators, completion percentages, or any analytic. A
  want is not a have.
- **`GameGenreCache` cannot be reused** for wishlist enrichment: it carries a foreign key to
  `games`, which a wishlisted app id has no row in. This change stores only what it needs rather
  than generalising that cache, leaving the broader question for when a third consumer exists.
- **One new periodic job, small and quiet**, recording price observations. It follows the
  established pattern for non-interactive work — off the interactive path, charger and unmetered —
  and issues the same handful of batched requests the section does.
- **Two undocumented endpoints.** The wishlist list and the store price data are both unversioned
  and have changed before; Steam withdrew the previous wishlist JSON endpoint in its 2024 revamp.
  Every failure path therefore degrades to an empty or stale section rather than an error.
- **No new permission, no cloud, no Firestore.** The app remains fully functional with the section
  empty.
- **Purchase-decision framing is deliberately out of scope.** Cost per hour, backlog-depth
  comparisons, and "you already own three unstarted games in this genre" are the strongest reasons
  this app's wishlist could differ from Steam's, but they depend on HowLongToBeat data for unowned
  games — a separate fetch burden with its own rate limits. This change delivers the section and
  its prices; that layer is worth its own decision.

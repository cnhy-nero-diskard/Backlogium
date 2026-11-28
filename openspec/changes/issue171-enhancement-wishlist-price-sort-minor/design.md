# Design

## Context

`WishlistPriceObservation` already stores Steam's formatted price, initial and final amounts,
currency, and observation time. `WishlistRepository` projects the latest observation and uses the
existing local expiry ticker to move prices out of the six-hour freshness window. The numeric
amount and currency are currently dropped before the wishlist presentation.

## Goals / Non-Goals

**Goals**

- Support sorting from current and retained observations on the same wishlist in every density.
- Define the evidence and refresh bounds a countdown follow-up would need.

**Non-Goals**

- Currency conversion, Steam wishlist editing, price alerts, and history UI.
- Production deadline ingestion, a new store-country setting, or countdown UI in this change.

## Decisions

### Carry the observed amount across the repository boundary

Expose the final amount and currency on the wishlist price model alongside the formatted amount,
freshness, and observation time. Steam's final price gives the payable amount for full-price and
discounted entries. Continue displaying Steam's formatted price. Sorting uses the numeric
observation, which avoids locale-dependent parsing and keeps an offline list useful.

Decode missing numeric amounts as missing instead of defaulting to zero. The old decoder could
persist zero for an absent final field, so legacy zero rows lack provenance. Use an additive
observation flag with a false default to identify explicitly reported zeros in new observations.
Existing positive amounts and their currency remain usable immediately. Legacy ambiguous zeros
still display their existing dated observation if present and enter the unrankable group until
refreshed. Preserve append-only price history through the migration.

### Keep the sort local to the expanded wishlist

Hold the chosen sort in wishlist presentation state. The expanded section owns a single labeled
control for list, grid, and compact grid. Group current and retained observations separately,
then group by currency code within each. Apply the direction to amounts alone. Keep group order
and Steam priority, date-added, and app-ID tie-breaks fixed. This makes flipping the sort
predictable even when a price ages, a chunk fails, or the cache contains two currencies.
Library text search continues to order its wishlist matches by relevance. Sort changes read the
cache and share the existing refresh path when observations update.

### Verify a source before adding countdown data

A November 28, 2026 probe of public apps in the US and Philippine storefronts found
`active_discounts[].discount_end_date` on StoreBrowse purchase options.
The Outer Wilds package matched the displayed app price in both storefronts and returned
`2026-12-05T18:00:00Z`. Divinity: Original Sin 2 returned a bundle as its best purchase option,
and the matching package appeared only in the full options. StoreBrowse returned no items with
the country omitted. The artwork lookup's US fallback serves a different purpose and provides
no evidence of the price observation's storefront.

The existing artwork endpoint is a candidate for the investigation. It is absent from
Valve's published Web API reference. A documented price provider such as IsThereAnyDeal exposes
a nullable expiry, but its Steam offer matching, regional coverage, and freshness remain unverified.
[Steam store listing](https://store.steampowered.com/app/753640/Outer_Wilds/)
[Valve Web API reference](https://partner.steamgames.com/doc/webapi)
[StoreBrowse protocol](https://github.com/SteamTracking/Protobufs/blob/master/steam/steammessages_storebrowse.steamclient.proto)
[IsThereAnyDeal API schema](https://github.com/IsThereAnyDeal/API/blob/master/dist/openapi.yaml)

A follow-up requires a supported source, a verified storefront, and an unambiguous deadline
for the same discounted purchase option. Verify support through published documentation or
provider confirmation. Compare a bounded sample across two storefronts, including a bundle
decoy and a discount that expires, against the matching Steam offers. Record offer identity,
published freshness and limits where available, timestamp semantics, and observed coverage.
If the evidence is insufficient, record the gap in the implementation PR for #171. Bounded batches
of discounted wishlist entries on the existing refresh cadence are the intended request budget.

### Define countdown behavior for a qualifying follow-up

Persist the UTC end instant, deadline source, validation time, storefront, and package or offer
identity alongside the corresponding observation. Derive remaining time from the local clock and
refresh the display only when its shown value, urgency, freshness, or the expiry status changes.
Show days and hours, with minutes below one hour. Use orange for 24 to 72 hours inclusive with
“Ends soon” and red below 24 hours with a short time-left label. The urgency label accompanies
the color in every density and is available to assistive technology.

A fresh discounted price without a verified deadline shows “Sale end unknown” when countdown
support is present. A stale deadline carries no countdown or urgency badge. At a verified
instant of expiry, the matching observation becomes a dated last-seen price and loses its
deals-first promotion. A fresh price for a different or newly repriced offer needs its own
deadline. A countdown never extends price freshness or implies today's regular price once the
observed discount ends.

## Risks / Trade-offs

- Currency and freshness grouping can place a cheaper cached entry lower in the list → name the
  groups so the scope of each comparison is visible.
- Locale, time zones, or bundle offers can make dates look comparable when the offers differ →
  require exact offer and storefront evidence, retain UTC instants, and present ambiguous dates
  as unknown in any countdown follow-up.

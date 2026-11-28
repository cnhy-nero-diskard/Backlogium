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

### Keep display evidence separate from ranking evidence

`StorePrice.Amount` carries Steam's nonblank `final_formatted` unchanged for display, with optional
validated minor units and currency for sorting. Accept `final` as ranking evidence only when its
JSON token is an integer number in the range 0 through `Long.MAX_VALUE`. Persist explicit zero
provenance in an additive observation flag. Missing fields, JSON null, strings (including quoted
numbers), other wrong types, fractional values, negative numbers, and overflow leave the amount
unknown. Normalize string currencies by trimming and uppercasing with `Locale.ROOT`, then accept
only recognized three-letter ISO 4217 currency codes. A displayable response with either piece
of ranking evidence missing is still recorded and shown.

Use this response mapping at the DTO/data-source boundary:

- `success: true` with `data: []` becomes `StorePrice.None` and appends a dated observation with no
  price fields. It is definitive unavailable, not zero, and supersedes older prices.
- A failed request, missing entry, `success: false`, missing/null/malformed `data` or
  `price_overview`, or blank/whitespace `final_formatted` is unresolved. Append nothing and leave
  the prior observation untouched. In particular, numeric-only zero is neither a successful
  absence nor a displayable price.
- A `price_overview` with nonblank formatted text becomes `StorePrice.Amount` even if its final or
  currency cannot be ranked. Store only validated sort evidence (invalid values become null),
  append that displayable observation, and project the latest observation only.

Read the response as a JSON object keyed by app ID, then decode each requested entry independently.
Read the ranking fields as JSON values within the overview and validate them separately from the
formatted price, so a wrong-type final still yields a displayable `Amount` when text is usable.
An unrecognizable entry becomes unresolved for that app. An unreadable response or invalid
top-level object leaves the chunk unresolved.

The repository maps the latest `None` to the existing dated unavailable state and the latest
displayable `Amount` to an observed dated price with optional rank evidence. Legacy positive
amounts remain rankable when their currency and formatted price validate. The migration's new
zero-provenance flag defaults to false, so ambiguous legacy zeros remain dated/displayable if they
have formatted text but are unrankable until refreshed. Preserve append-only price history.

### Keep the sort local to the expanded wishlist

Hold the selected mode in `WishlistViewModel` presentation state, defaulting to Deals first. It
survives collapse/reopen, density changes, and entry into or exit from Library search while that
ViewModel exists; it is not persisted across ViewModel recreation. Search temporarily uses its
existing relevance order without changing the selected wishlist mode, which resumes when search is
cleared.

The expanded section owns one accessible sort control for list, grid, and compact grid. Keep the
bucket order fixed: current rankable observations, retained rankable observations, then
not-comparable entries. Within each freshness bucket, currency groups stay in ascending normalized
currency-code order and the chosen direction applies only to amounts inside a currency group.
Label each nonempty group `Current prices — <currency code>`, `Previously observed prices — <currency code>`,
or `Not comparable`, even when there is only one currency. Preserve Steam priority,
date-added, and app-ID tie-breaks, and keep each row's current/retained and unavailable/never-seen
display state distinct. Sort changes read the cache and share the existing refresh path.

### Verify a source before adding countdown data

#### StoreBrowse source-check record (2026-11-28)

This durable, redacted evidence record is linked to [issue #171](https://github.com/cnhy-nero-diskard/Backlogium/issues/171). The requests sent no API key, Steam ID, or account-specific data. Reproduce the StoreBrowse requests with `GET https://api.steampowered.com/IStoreBrowseService/GetItems/v1/` and these URL-encoded `input_json` bodies; repeat with each listed country, then omit `country_code` for the final case:

```json
{
  "ids": [{"appid": 753640}, {"appid": 435150}],
  "context": {"language": "english", "country_code": "US"},
  "data_request": {
    "include_all_purchase_options": true,
    "include_best_purchase_option": true
  }
}
```

The second request uses `"country_code": "PH"`; the third uses `{"language": "english"}` with no country field. Each returned HTTP 200. To compare displayed app prices, the credential-free requests were `https://store.steampowered.com/api/appdetails?appids=753640%2C435150&filters=price_overview&cc=us` and the same URL with `cc=ph`.

Response excerpts retain app and offer IDs, prices, discounts, and one copy of each expiry value.
StoreBrowse returned `final_price_in_cents` as JSON strings. Appdetails returned numeric minor
units and Steam-formatted strings.

| Country | App and StoreBrowse purchase option | StoreBrowse response excerpt | Appdetails price overview |
| --- | --- | --- | --- |
| US | Outer Wilds (753640), package 221672, best option | `{"packageid":221672,"final_price_in_cents":"1249","formatted_final_price":"$12.49","discount_pct":50,"active_discounts":[{"discount_end_date":1796493600}]}` | `{"currency":"USD","initial":2499,"final":1249,"discount_percent":50,"initial_formatted":"$24.99","final_formatted":"$12.49"}` |
| PH | Outer Wilds (753640), package 221672, best option | `{"packageid":221672,"final_price_in_cents":"36247","formatted_final_price":"₱362.47","discount_pct":50,"active_discounts":[{"discount_end_date":1796493600}]}` | `{"currency":"PHP","initial":72495,"final":36247,"discount_percent":50,"initial_formatted":"P724.95","final_formatted":"P362.47"}` |
| US | Divinity: Original Sin 2 (435150), best option is bundle 14169 | `{"bundleid":14169,"final_price_in_cents":"1066","formatted_final_price":"$10.66","discount_pct":82,"active_discounts":[{"discount_end_date":1796061600}]}` | `{"currency":"USD","initial":4499,"final":1124,"discount_percent":75,"initial_formatted":"$44.99","final_formatted":"$11.24"}` |
| PH | Divinity: Original Sin 2 (435150), best option is bundle 14169 | `{"bundleid":14169,"final_price_in_cents":"28698","formatted_final_price":"₱286.98","discount_pct":82,"active_discounts":[{"discount_end_date":1796061600}]}` | `{"currency":"PHP","initial":123995,"final":30998,"discount_percent":75,"initial_formatted":"P1,239.95","final_formatted":"P309.98"}` |

For Divinity, the full `purchase_options` included package 207600 at USD 1124 and PHP 30998
(75% off), matching appdetails, with `discount_end_date` of 1796061600 in both regions.
With `country_code` omitted, the response had an empty `store_items` list. The observed expiry
integers 1796493600 and 1796061600 correspond to `2026-12-05T18:00:00Z` and
`2026-11-30T18:00:00Z` when interpreted as Unix seconds.

The appdetails response lacks a package or bundle ID. The matching prices establish price
agreement only, leaving the offer identity required for a countdown unverified.

StoreBrowse is absent from Valve's [published Web API reference](https://partner.steamgames.com/doc/webapi);
the field is described in the community-maintained [StoreBrowse protocol](https://github.com/SteamTracking/Protobufs/blob/master/steam/steammessages_storebrowse.steamclient.proto).
The [IsThereAnyDeal API schema](https://github.com/IsThereAnyDeal/API/blob/master/dist/openapi.yaml)
is only a candidate; its exact offer matching, regional coverage, freshness, and timestamp semantics
were not verified. Any countdown follow-up requires supported-source evidence, verified storefront
and purchase-option identity, an unambiguous deadline for that same option, and recorded
freshness and coverage bounds. Verify the deadline against the same offer through its expiry,
and include a bundle decoy and two storefronts in the bounded sample. This probe shows coverage for these
apps only. Published freshness, request limits, and coverage for StoreBrowse remain unverified.
If the conditions are unmet, preserve the gap in #171. Bounded batches on the existing refresh
cadence remain the intended request budget.

The artwork lookup's US fallback serves a different purpose and provides no evidence of the price
observation's storefront. See the [Outer Wilds Steam listing](https://store.steampowered.com/app/753640/Outer_Wilds/).

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

- Currency and freshness grouping can place a cheaper cached entry lower in the list → always name
  freshness, currency, and not-comparable groups, including for a single currency.
- Locale, time zones, or bundle offers can make dates look comparable when the offers differ →
  treat price equality as insufficient identity evidence, require the exact offer and storefront,
  retain UTC instants, and present ambiguous dates as unknown in any countdown follow-up.

## Context

Two live probes against a real profile decided most of this, and both are worth preserving because
they invalidate the design that would otherwise have been reasonable.

**Prices batch.**

```
$ curl -s "…/api/appdetails?appids=440,570,730&cc=PH&filters=price_overview"
{"440":{"success":true,"data":[]},"570":{"success":…
```

Three appids, one request, three separately keyed entries. The design this replaces — one request
per app, a `MAX_APPS_PER_BATCH` cap, `MIN_REQUEST_SPACING_MILLIS`, a resumable worker and progress
UI, all modelled on `GameGenreRepository` — is unnecessary.

**Absent prices are represented, not omitted.** `data` came back as `[]` for every one of those
three, because 440, 570, and 730 are all free-to-play. That is the encoding for "this app has no
price," and it is unambiguous.

Relevant existing positions:

- `optimize-steam-sync` established **serial issuance** for Steam requests and reverted a semaphore
  rather than fix it, because "the Steam client has no retry, no backoff, and no 429 handling."
  Pinned by `AchievementRepositoryTest.fetches are issued one at a time`.
- `GameGenreRepository` is the existing store-host pattern: 30-day freshness, 25-app batches, 500ms
  spacing, serial.
- `GameGenreCache` carries a foreign key to `games` with `ON DELETE CASCADE`.
- `SteamStoreApi` exists, credential-free, single-appid, no `cc`.

## Goals / Non-Goals

**Goals:**

- The player can see what they want and what it costs without leaving the app.
- The section works offline, showing what was last observed and when.
- A price the app is unsure about is never presented as current.
- Price history begins accumulating immediately, whatever consumes it later.
- Nothing about wishlisted games leaks into owned-library statistics.

**Non-Goals:**

- Price-drop alerting, and any history UI. Recording only.
- Cost-per-hour, backlog-depth comparison, or any purchase-decision framing.
- HowLongToBeat data for wishlisted games.
- Editing the wishlist. Steam owns it; the app reads it.
- Bundles, packages, DLC pricing, or regional price comparison.
- Generalising `GameGenreCache` to serve non-owned app ids.

## Decisions

### 1. Refresh on view, with no worker for current prices

Because a whole wishlist is a handful of requests, prices can simply be refreshed when the section
is opened, subject to a short freshness window so that repeated navigation does not re-request.

This is the opposite of the genre path's tuning, deliberately. Genres are effectively immutable and
get a 30-day window; a 30-day-old price is worthless. Same host, opposite volatility, so the same
machinery would have been the wrong answer even before batching made it unnecessary.

The serial-issuance position from `optimize-steam-sync` is not engaged here: that requirement
governs fan-out over many per-item requests. This path issues few requests with long query strings,
which is the shape that position was steering *toward*.

### 2. A separate DTO for price lookups, leaving the genre path alone

`StoreAppDetails.data` is `StoreAppData?` — an object. A price-filtered request returns `"data": []`
for any app without a price, and deserializing an array into that type throws. Because the response
is a single JSON document, **one free-to-play game in a batch fails the entire response**, not just
its own entry.

The price path therefore gets its own DTO whose `data` tolerates both shapes, mapping `[]` to "no
price." The genre path is untouched: it requests unfiltered `appdetails`, where `data` is always an
object, so widening the shared DTO would add a branch to a caller that can never take it.

This is the single most likely thing for a future reader to get wrong, because reusing
`StoreAppDetails` looks obviously correct and fails only once a free game enters the wishlist.

### 3. "No price" is a state with three meanings, and the app does not guess between them

`data: []` says a price does not exist. It does not say why: free-to-play, unreleased, or not sold
in the player's region are all possible and Steam does not distinguish them in this response.

The app shows that a price is unavailable rather than inventing a reason, and never renders a
missing price as zero, blank, or a dash — each of which reads as a price. Where the wishlist entry
itself carries enough information to be more specific, it may be, but the price lookup alone is not
grounds for a claim about *why*.

### 4. No store region is asserted from the player's profile

An earlier version of this design read `loccountrycode` from `GetPlayerSummaries` as the player's
store region. Review corrected that: the field is the public/community profile location, while
Steam Store Country is a separate account and payment-derived setting. Pricing from it can force
the wrong region and currency, so the assumption is removed rather than refined.

Until an explicit store-country setting exists, `cc` is omitted entirely and Steam resolves the
region from the request itself. That is a better fallback than a derived or hardcoded region,
which would confidently show the wrong currency. The `storeRegion` column remains as the future
home of such a setting; the profile location is never written to it.

Displayed prices use Steam's own formatted string, which places currency symbols correctly per
region. Any arithmetic uses the integer minor-unit fields. Formatting money from raw integers
across regions is a well-known source of wrong output and there is no reason to attempt it when the
API supplies the rendered form.

### 5. Observations are recorded from day one, with nothing consuming them yet

Each time prices are fetched, the observed value is appended to a history table.

The asymmetry justifies this on its own: history is cheap to accumulate — one row per app per
observation — and impossible to reconstruct afterwards. A drop-alerting feature built later would
otherwise begin with no past, and "is this actually the lowest it has been?" is the one thing a
wishlist tracker can say that Steam's own does not, since Steam only ever compares against list
price.

A small periodic job samples prices independently of the section being viewed, because on-view
refresh alone records history only for the days the player happened to look. It follows the
established pattern for non-interactive work — off the interactive path, charger and unmetered —
and issues the same few batched requests.

*Deliberately excluded:* alerting, notifications, and any history surface. Those are real features
with real UI decisions, and bundling them here would make a small change large while the recording
half, which must start now, is the only part that cannot wait.

### 6. Wishlist entries are their own tables, not rows in `games`

A wishlisted game is not owned, has no sessions, and must not appear in library counts, XP
denominators, completion percentages, or analytics. Giving it a `games` row — even behind a source
flag — would put it in front of every existing query and make each one responsible for excluding
it.

`GameGenreCache` cannot be reused either: its foreign key to `games` cannot be satisfied by an app
id the player does not own. Generalising that cache to be appid-keyed without the key is defensible
and probably right eventually — owned, family-shared, wishlisted, and later non-Steam games all
want store enrichment — but doing it here would be a schema change to existing tables for a new
feature's benefit, with only one caller to justify it. This change stores what it needs; the
generalisation is worth doing when a third consumer makes the case.

### 7. Owned wishlist entries are reconciled rather than trusted away

Steam removes a game from the wishlist on purchase, so the list should self-correct. It does not
always: gifts, keys, and family additions all produce windows where a game is owned and still
listed.

Rather than depend on Steam being prompt, the app checks each wishlist entry against the owned
library and treats an owned entry as no longer wanted. That is one set intersection over data
already in Room, and it prevents the app from asking the player to buy something they own.

### 8. Every failure degrades to empty or stale, never to an error

Both endpoints are undocumented and unversioned, and Steam withdrew the previous wishlist JSON
endpoint in its 2024 revamp. A wishlist can also simply be private.

So: an unavailable list yields an empty section with an explanation, an unavailable price yields
the last observed price with its date, and neither condition surfaces as an app error or affects
anything outside the section. The feature is additive and its absence must be survivable, in the
same spirit as the standing requirement that the app work with no network and no cloud.

## Risks / Trade-offs

- **Both endpoints could disappear.** → The most likely long-term failure, and the reason every
  path degrades rather than errors. Cached prices and their dates remain useful even if refreshing
  stops working permanently.

- **The batch ceiling is unverified.** Three appids worked; a wishlist may be eighty. → The
  implementation chunks conservatively and treats a failed chunk as a failed chunk rather than a
  failed refresh, so an unexpectedly low ceiling degrades to partial data rather than nothing. Worth
  probing with a realistic id count during implementation.

- **Recording history for a feature that does not exist yet could prove wasted.** → The volume is
  trivial and the alternative is a future feature with no past. If drop alerting is never built, the
  cost is a small table nobody reads.

- **With `cc` omitted, Steam resolves the region from the request itself**, which may not be the
  account's actual Steam Store Country. The profile's public location is deliberately not consulted
  (decision 4), and no explicit store-country setting exists yet. → Prices are recorded as what was
  observed and dated, never presented as more than that, so a request-resolved region renders at
  most a mislabelled price — the same exposure the un-regioned request already carries. The
  `storeRegion` column is the future home of an explicit store-country setting; that override is a
  later decision if the request-resolved region ever bites.

- **A wishlist section invites the purchase-decision features it explicitly excludes.** → Named as
  a non-goal rather than left implicit, so the next change makes that case on its own terms instead
  of arriving as scope creep here.

## Observed responses

Recorded during implementation (tasks 1.1–1.4). Every shape below was returned by a live request,
not inferred, because both endpoints are undocumented and the failure paths are built against what
they actually answer.

### The wishlist list

`GET https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=<id>`, no API key — the
endpoint answers for any publicly readable wishlist, so the app does not spend a credential on it.

```json
{"response":{"items":[
  {"appid":1620,"priority":0,"date_added":1572456900},
  {"appid":34180,"priority":879,"date_added":1549370695}
]}}
```

Three fields and no more: `appid`, `priority` (0 for an unprioritized entry), and `date_added` in
epoch **seconds**. **No name and no artwork** — those have to come from somewhere else, which is
what fixes the shape of section 3 below.

An unreadable wishlist answers `HTTP 200` with `{"response":{}}` — the `items` key is *absent*,
not an empty array. That is the only signal available, so the app reads a missing `items` as
"cannot be read" and an `items: []` as "empty". Conflating them is what the app must not do;
erring towards "unavailable" is the direction the spec asks for, since an unreadable wishlist
appearing empty is the failure worth preventing.

*Coverage note:* probed against public Steam IDs, not against a configured account — this checkout
has no Steam credentials in `local.properties`. The authenticated case (does a key let the owner
read their own private wishlist?) is unprobed, and the app assumes it does not.

### Prices

`GET https://store.steampowered.com/api/appdetails?appids=<csv>&cc=<code>&filters=price_overview`.

A paid app, and the same app discounted:

```json
"292030":{"success":true,"data":{"price_overview":{
  "currency":"PHP","initial":209900,"final":209900,"discount_percent":0,
  "initial_formatted":"","final_formatted":"P2,099.00"}}}

"1174180":{"success":true,"data":{"price_overview":{
  "currency":"PHP","initial":339900,"final":84975,"discount_percent":75,
  "initial_formatted":"P3,399.00","final_formatted":"P849.75"}}}
```

`initial_formatted` is **empty at full price** and carries the struck-through list price only while
a discount is active. Reading it as "the price before discount" unconditionally would render an
empty string where a price belongs, so `final_formatted` is the one field always safe to show.

`cc` behaves as the design assumed: `cc=US` returns `USD`/`$49.99` for the same app that `cc=PH`
prices at `PHP`/`P2,099.00`, and **omitting `cc` entirely** returns a region Steam resolves from
the request rather than an error — which is exactly what decision 4's fallback needs.

Three distinct per-app outcomes, all inside one `HTTP 200`:

| Response | Meaning |
|---|---|
| `{"success":true,"data":{"price_overview":{…}}}` | a price |
| `{"success":true,"data":[]}` | the app has no price (free, unreleased, not sold here) |
| `{"success":false}` | Steam will not describe this app id at all — no `data` key |

The third was not anticipated by the original design and matters: `success:false` is not the same
answer as `data: []`. It says nothing about whether a price exists, so it is retained-not-observed
rather than a recorded "no price".

### Batch ceiling

Probed at 391 ids and again at **1191 ids** in a single request: both returned `HTTP 200` with an
entry for every id requested. There is no low ceiling to design around — `filters=price_overview`
is what makes the endpoint answer for a whole list at once.

The chunk size is nonetheless **100**, well under anything observed to fail. The limit that bites
first is URL length rather than a documented app cap, and a wishlist of any realistic size is one
or two requests either way; a conservative chunk buys a failed chunk that costs a hundred prices
instead of all of them, for no extra requests in the common case.

`filters` is also what makes batching work at all: `filters=basic` and `filters=price_overview,basic`
over several ids both return a bare `null`. **Only the price filter batches.** That is why names
and artwork cannot ride along on this request.

### Names and artwork

Since the wishlist carries no names and `appdetails` will not batch anything but prices, entry
details come from `IStoreBrowseService/GetItems/v1` — the same endpoint Steam's own wishlist page
uses. Credential-free, batched, and it returns `name` plus an `assets` block:

```json
{"response":{"store_items":[{"id":440,"success":1,"visible":true,"name":"Team Fortress 2",
  "assets":{"asset_url_format":"steam/apps/440/${FILENAME}?t=1757348372","header":"header.jpg",…}}]}}
```

`asset_url_format` is the store's own answer for where an app's art lives, cache-busting timestamp
included, so the stored artwork URL is observed rather than a guessed CDN path — with
`SteamIconMapper.headerUrl` as the fallback when an entry carries no assets.

This is one added request shape, not a per-game fan-out: names and prices are each one batched call
per chunk.

## Later decisions

### 9. Discounts come first, and only while they are actually running

The original position — present entries in Steam's priority order and re-sort on nothing — held
until the section existed to look at. Checking a wishlist is overwhelmingly checking whether
anything is on sale, and a sale at position forty is one the player hears about from somewhere
else. So discounted entries lead.

The player's ranking is not discarded for it. The sort is stable and applied over Steam's order, so
within both groups the priority they set is exactly what they see: the change is which of two
questions the list answers first, not the abandonment of the second.

Only a *live* discount floats — one observed inside the freshness window. A retained discount is an
observation about a day that has passed, and promoting it would put a price the app is explicitly
unsure of at the top of the list, presented as the most urgent thing on it. That is the same
mistake as rendering a retained price as current, arrived at from a different direction.

### 10. What the compact grid gives up

Entries follow the Library's density control rather than keeping a presentation of their own. The
ladder is not the owned one, though: what drops as the grid tightens is the *wishlisted label*, not
the price. The price is why the section exists, and at three columns it also does the label's job —
an owned tile at that density carries a name and nothing else, so a money capsule, or the words "No
price available", separates a want from a have by structure rather than by colour.

At that density the discount percentage and the struck-through list price go too, and the capsule's
fill carries the discount alone. This is a real reduction and worth naming rather than glossing: a
reader who cannot separate the two fills loses the discount at this one density. Three things
temper it — the percentage is spelled out at both other densities, the discounted *price* itself
never leaves the tile, and the capsule's accessibility label speaks the percentage whether or not
it is drawn. It is the same bargain the compact grid already makes with playtime and achievements.

The observed date is the one thing that does **not** drop, at any density. Cutting it to save a
line on a narrow tile would turn a remembered price into a claim about the price right now.

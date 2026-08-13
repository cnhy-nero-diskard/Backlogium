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

### 4. Region comes from the player's profile

`GetPlayerSummaries` already returns `loccountrycode`; the app simply does not deserialize it.
Adding it to the DTO and persisting it beside the identity the sync already stores costs one field
each and means prices arrive in the player's own currency with no setting to configure.

Where the profile exposes no country, `cc` is omitted entirely and Steam resolves the region from
the request itself. That is a better fallback than a hardcoded default, which would confidently
show the wrong currency.

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

- **`cc` is only as good as the player's profile country**, which they may have set to somewhere
  they do not live. → Prices would be right for the region Steam thinks they are in, which is also
  what the store itself would show them. Acceptable, and an override is a later decision if it ever
  bites.

- **A wishlist section invites the purchase-decision features it explicitly excludes.** → Named as
  a non-goal rather than left implicit, so the next change makes that case on its own terms instead
  of arriving as scope creep here.

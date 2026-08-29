## 1. Verify the endpoints before building on them

- [x] 1.1 Confirm `IWishlistService/GetWishlist/v1` answers for the configured Steam ID, and record the response shape — appid, priority, date added — in `design.md`
- [x] 1.2 Confirm a price-filtered `appdetails` request against a **paid** app returns `price_overview` with currency, initial, final, discount percent, and formatted string, and that `cc` yields the expected currency; record it in `design.md`
- [x] 1.3 Probe the batch ceiling with a realistic id count and record the chunk size chosen and why
- [x] 1.4 Record what a private wishlist returns, so the unavailable path is built against an observed response rather than a guess

## 2. Store region

- [x] 2.1 Deserialize `loccountrycode` in `PlayerSummariesDto` — it is already in Steam's response and simply unread
- [x] 2.2 Add a store-region column to `PlayerProfile` with its migration
- [x] 2.3 Persist the region during sync alongside persona name and avatar, leaving a previously stored value intact when the profile exposes none
- [x] 2.4 Omit the region parameter entirely when none is known, rather than defaulting to one

## 3. Remote surface

- [x] 3.1 Add the wishlist call to `SteamApi`, taking the diagnostics `@Tag scope` like every other tracked call
- [x] 3.2 Add a batched, price-filtered `appDetails` call to `SteamStoreApi` taking multiple app ids and a country code
- [x] 3.3 Add a **separate** price DTO whose `data` tolerates both an object and the `[]` that Steam returns for an app with no price, mapping `[]` to "no price"
- [x] 3.4 Leave `StoreAppDetails` and the genre path untouched; add a comment recording why the DTO is not shared
- [x] 3.5 Add unit tests over recorded fixtures: a paid app, a free app returning `data: []`, a mixed batch, and a malformed response
- [x] 3.6 Chunk requests conservatively and treat a failed chunk as a failed chunk, retaining previously observed prices for those apps

## 4. Storage

- [x] 4.1 Add a `wishlist_items` table — app id, name, artwork, priority, date added, last seen — with no foreign key to `games`
- [x] 4.2 Add a `wishlist_price_observations` table appending each observation with its timestamp, never overwriting
- [x] 4.3 Add the migration for both
- [x] 4.4 Add a repository exposing wishlist entries and their latest observed price as domain models, keeping entities inside `data/`
- [x] 4.5 Verify no wishlist row can reach any owned-library query, count, XP denominator, or analytic

## 5. Refresh and reconciliation

- [x] 5.1 Refresh the wishlist and its prices when the section is opened, skipping the request when retained prices are within the freshness window
- [x] 5.2 Choose and document the freshness window in the repository's KDoc, noting explicitly that it inverts the genre path's 30-day window because prices are volatile where genres are not
- [x] 5.3 Append an observation on every successful price fetch
- [x] 5.4 Reconcile against the owned library so an owned entry is not presented as wanted, without waiting for Steam to drop it
- [x] 5.5 Retain entries and prices with their dates when a refresh fails
- [x] 5.6 Add tests for the freshness window, partial-chunk failure, and owned reconciliation

## 6. Periodic sampling

- [x] 6.1 Add a periodic worker that samples wishlist prices independently of the section being viewed
- [x] 6.2 Constrain it to charging and unmetered network, off the interactive path, following the reconciliation worker's precedent
- [x] 6.3 Confirm it issues the same batched requests as the on-view path and adds no per-game fan-out
- [x] 6.4 Confirm it records observations only and surfaces nothing — no alert, no notification

## 7. Surfaces

- [x] 7.1 Add the wishlist section to the Library, reachable without displacing the owned lists
- [x] 7.2 Present entries with artwork, name, price, and discount, in Steam's priority order
- [x] 7.3 Make wishlist entries distinguishable from owned entries without relying on colour alone
- [x] 7.4 Present each price state distinctly: current, retained with its date, unavailable, never observed — none rendered in a way that reads as a price
- [x] 7.5 Distinguish an empty wishlist from an unreadable one, and explain the latter
- [x] 7.6 Add the Steam store link per entry, offered even when no price is available
- [x] 7.7 Hide the section entirely when no Steam credentials are configured
- [x] 7.8 Verify the owned Library's sorting, grouping, density, and search are unchanged

## 8. Verification

- [x] 8.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and `./gradlew assembleDebug`
- [x] 8.2 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [x] 8.3 Confirm no wishlist data reaches library counts, XP, completion figures, or Analytics
- [ ] 8.4 Manually verify prices render in the expected currency for the configured profile region
- [ ] 8.5 Manually verify a wishlist containing at least one free-to-play game refreshes without failing the whole batch
- [ ] 8.6 Manually verify airplane mode: entries and dated prices remain, nothing errors
- [ ] 8.7 Manually verify the store link opens the Steam app when installed
- [ ] 8.8 Manually verify a purchased wishlist game stops being presented as wanted

# Wishlist Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: The wishlist is retrieved, and ordered by what is worth deciding on now### Requirement: The wishlist is retrieved, and ordered by what is worth deciding on now`
- TO: `### Requirement: The wishlist is retrieved, and ordered by what is worth deciding on now`

## MODIFIED Requirements

### Requirement: The wishlist is retrieved, and ordered by what is worth deciding on now
The system SHALL retrieve the player's Steam wishlist. The expanded wishlist SHALL offer Deals
first, Price low to high, and Price high to low in list and both grid densities. By default,
Deals first SHALL put entries currently observed to be discounted ahead of the others and
preserve Steam priority within each group. A retained discount SHALL NOT promote an entry in
Deals first. The app SHALL NOT modify the Steam wishlist.

#### Scenario: Wishlist retrieved
- **WHEN** the player's wishlist is fetched successfully
- **THEN** its entries are available

#### Scenario: Ordering preserved within each group
- **WHEN** the player has assigned priorities in Steam and uses Deals first
- **THEN** Steam priority determines the order within the currently discounted group and within the remaining group

#### Scenario: A running discount comes first
- **WHEN** some entries are currently observed to be discounted and the player uses Deals first
- **THEN** they appear ahead of the rest, in the player's own priority order among themselves

#### Scenario: A discount seen earlier does not come first
- **WHEN** an entry's discount ages outside the freshness window and the player uses Deals first
- **THEN** the entry returns to its place among the remaining entries in the player's priority order

#### Scenario: Wishlist sort is independent
- **WHEN** the player selects a wishlist price order
- **THEN** changing the order uses cached data and leaves owned-game ordering and Library text-search relevance intact

#### Scenario: Sort selection survives temporary presentation changes
- **WHEN** the player selects a wishlist order and collapses/reopens the section, changes density, or enters and exits Library search
- **THEN** the selection is retained for the lifetime of the Library wishlist's presentation state, search results use relevance order while search is active, and the selected wishlist order resumes when search is cleared
- **AND** recreated presentation state starts at Deals first

#### Scenario: No editing
- **WHEN** the player views a wishlist entry
- **THEN** no operation is offered that would add to, reorder, or remove from the Steam wishlist

#### Scenario: Empty wishlist
- **WHEN** the player's wishlist contains no games
- **THEN** the section reports that it is empty, distinctly from being unavailable

## ADDED Requirements

### Requirement: A price is rankable only when its evidence is complete
An entry SHALL be rankable only when its latest observation has a nonblank Steam-formatted price,
a nonnegative final amount encoded as a JSON integer within range, and a recognized three-letter
ISO 4217 currency code after trimming and locale-independent uppercasing. Zero SHALL require
explicit provenance. An unrankable observation SHALL still display its usable Steam-formatted
price. The system SHALL NOT derive an amount from formatted text or synthesize a formatted price.

#### Scenario: Explicit zero with display evidence is rankable
- **WHEN** Steam explicitly reports numeric final zero with a nonblank formatted price and a valid currency
- **THEN** the observation sorts as zero within that currency and freshness group

#### Scenario: Legacy zero has no provenance
- **WHEN** a retained observation contains numeric zero but was written before explicit-zero provenance existed
- **THEN** it keeps its original date and nonblank Steam-formatted display, if present, but is not rankable until a new observation supplies provenance

#### Scenario: Numeric-only zero is not a price or known absence
- **WHEN** Steam reports final zero but the formatted final price is missing, null, empty, or whitespace
- **THEN** the lookup is unresolved, no zero or unavailable price is invented, and the last stored observation is not replaced

#### Scenario: Invalid final remains displayable but unrankable
- **WHEN** Steam supplies a nonblank formatted final price but the final amount is absent, null, a wrong JSON type (including a quoted number), negative, fractional, or outside the supported integer range
- **THEN** that dated formatted observation is displayed in the Not comparable group, with no numeric amount borrowed from an older observation

#### Scenario: Price decoding isolates apps within a batch
- **WHEN** a valid JSON response contains an app with a wrong-type final field and a usable formatted price, and another app with complete price evidence
- **THEN** the first app gets a dated displayable observation with unknown amount and the second app gets its own rankable observation
- **AND** unrecognizable entries leave only their own earlier observations in place

#### Scenario: Invalid currency does not form a currency group
- **WHEN** Steam supplies a nonblank formatted final price but its currency is blank, malformed, or not a valid ISO 4217 code after normalization
- **THEN** the dated formatted observation is displayed in the Not comparable group and is not assigned to a currency group

#### Scenario: A newer displayable observation supersedes an older rankable amount
- **WHEN** a newer observation has a usable formatted price but no rankable amount or currency
- **THEN** the newer observation supplies the displayed price and sort state; the older amount is not used for either the current price or sorting

#### Scenario: Definitive no-price data supersedes an older price
- **WHEN** Steam successfully returns the explicit empty `data: []` no-price result
- **THEN** the result is a dated unavailable observation, not a zero, and no older amount is used for sorting

### Requirement: Price sorting compares observed payable amounts
Both price modes SHALL show rankable current prices first, rankable retained prices next, and
all unrankable entries last, including those with no observation. Within each priced group the system SHALL
compare Steam's observed final payable amounts in numeric minor units within the same normalized
currency. Currency order SHALL be fixed across sort directions, and the system SHALL NOT convert
currencies.

#### Scenario: Freshness and unrankable groups are always labeled
- **WHEN** a price order is shown, including a wishlist with one currency or one priced group
- **THEN** each nonempty group is labeled in every density: `Current prices — <currency code>`, `Previously observed prices — <currency code>`, or `Not comparable`
- **AND** entries in Not comparable retain their distinct displayed states, such as unavailable, never observed, or a dated formatted-only price

#### Scenario: Discounted and regular prices sort by what was payable
- **WHEN** USD 40.00 discounted to USD 8.00 and a full-price USD 9.00 are both current
- **THEN** the USD 8.00 entry comes first in ascending order and the USD 9.00 entry comes first in descending order

#### Scenario: Different currencies form separate groups
- **WHEN** comparable observations carry two or more currency codes
- **THEN** the currency groups appear in ascending currency-code order in both price modes, with amounts ordered only within each currency and freshness group

#### Scenario: Zero and missing amounts sort differently
- **WHEN** an entry has an explicit payable zero and others have no price, have never been checked, or lack a reliable numeric amount or currency
- **THEN** a displayable explicit zero sorts with comparable prices and the other entries follow the priced groups in either direction while retaining their distinct display states

#### Scenario: Retained prices remain usable offline
- **WHEN** a price ages outside the freshness window or the wishlist opens with retained observations offline
- **THEN** the last observed payable amount sorts in the retained group with its original date and retained status visible in every density
- **AND** an earlier discount is presented as historical and the earlier list price is not substituted as today's price

#### Scenario: Equal amounts sort consistently
- **WHEN** two entries share a price within a group, or both lack rankable prices
- **THEN** their relative order is determined by Steam priority with unprioritized entries last, then ascending date added, then ascending Steam app ID in either price direction

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

#### Scenario: No editing
- **WHEN** the player views a wishlist entry
- **THEN** no operation is offered that would add to, reorder, or remove from the Steam wishlist

#### Scenario: Empty wishlist
- **WHEN** the player's wishlist contains no games
- **THEN** the section reports that it is empty, distinctly from being unavailable

## ADDED Requirements

### Requirement: Price sorting compares observed payable amounts
Both price modes SHALL show rankable current prices first, rankable retained prices next, and
entries without a rankable amount or currency last. Within each priced group the system SHALL
compare Steam's observed final payable amounts in numeric minor units within the same currency.
It SHALL use named currency groups in currency-code order when multiple currencies are present
and SHALL NOT perform currency conversion.

#### Scenario: Discounted and regular prices sort by what was payable
- **WHEN** USD 40.00 discounted to USD 8.00 and a full-price USD 9.00 are both current
- **THEN** the USD 8.00 entry comes first in ascending order and the USD 9.00 entry comes first in descending order

#### Scenario: Different currencies form separate groups
- **WHEN** comparable observations carry two or more currency codes
- **THEN** the currency groups appear in the same order in both price modes, with amounts ordered only within each currency and freshness group

#### Scenario: Zero and missing amounts sort differently
- **WHEN** an entry has an explicit payable zero and others have no price, have never been checked, or lack a reliable numeric amount or currency
- **THEN** the explicit zero sorts with comparable prices and the other entries follow the priced groups in either direction while retaining their distinct display states

#### Scenario: Retained prices remain usable offline
- **WHEN** a price ages outside the freshness window or the wishlist opens with retained observations offline
- **THEN** the last observed payable amount sorts in the retained group with its original date and retained status visible in every density
- **AND** an earlier discount is presented as historical and the earlier list price is not substituted as today's price

#### Scenario: Equal amounts sort consistently
- **WHEN** two entries share a price within a group, or both lack rankable prices
- **THEN** their relative order is determined by Steam priority with unprioritized entries last, then ascending date added, then ascending Steam app ID in either price direction

## ADDED Requirements

### Requirement: Wishlist section
The Library SHALL provide a wishlist section presenting the player's wishlisted games with their
artwork, name, current price, and any active discount. It SHALL be reachable without displacing the
owned-library lists, and its entries SHALL be visually distinguishable from owned games so the two
are never mistaken for one another. Its entries SHALL follow the Library's own density control
rather than keeping a presentation of their own.

#### Scenario: Viewing the wishlist
- **WHEN** the player opens the wishlist section
- **THEN** their wishlisted games are listed with artwork, name, and price where one is available

#### Scenario: Discount shown
- **WHEN** a wishlisted game is discounted
- **THEN** the entry shows the discounted price and that a discount is active

#### Scenario: Discount at the densest layout
- **WHEN** the Library is at its most compact density
- **THEN** the discounted price is still shown, and the discount is conveyed by the price's own
  treatment rather than spelled out, while remaining available to assistive technology

#### Scenario: Not mistaken for owned games
- **WHEN** wishlist entries are presented
- **THEN** they are distinguishable from owned-library entries without relying on colour alone

#### Scenario: Density applies to the wishlist too
- **WHEN** the player changes the Library between its list and grid densities
- **THEN** wishlist entries change with it, and remain distinguishable from owned entries at every
  density, by whatever means that density affords

#### Scenario: Owned lists unaffected
- **WHEN** the wishlist section exists
- **THEN** the owned-library lists, their sorting, grouping, density, and search behave exactly as
  they do today

#### Scenario: Empty owned library does not hide the wishlist
- **WHEN** Steam credentials are configured, the wishlist has entries, and the owned library is empty
- **THEN** the wishlist section remains reachable while the owned library still explains that no
  games have been loaded yet

#### Scenario: Wishlist games absent from library statistics
- **WHEN** any library count, completion figure, or analytic is computed
- **THEN** wishlisted games contribute to none of them

### Requirement: Wishlist entry states
Each wishlist entry SHALL convey the state of its price: current, retained from an earlier
observation with its date, unavailable, or not yet observed. Whenever an amount is shown, its
observation date SHALL be shown with it at every Library density. No state SHALL be rendered in a
way that could be read as a price.

#### Scenario: Current price
- **WHEN** prices were just refreshed
- **THEN** the entry shows its price and the date it was observed

#### Scenario: A price is set apart from the entry's other text
- **WHEN** an entry has a price to show
- **THEN** that amount is presented distinctly from the entry's other text, and a state carrying
  no amount is not given the same treatment

#### Scenario: Retained price
- **WHEN** the shown price was observed earlier
- **THEN** the entry states when it was observed

#### Scenario: No price available
- **WHEN** Steam reports no price for the game
- **THEN** the entry says the price is unavailable rather than showing a zero, dash, or blank

#### Scenario: Not yet observed
- **WHEN** no price has ever been observed for the game
- **THEN** the entry claims no price

### Requirement: Wishlist empty and unavailable states
The section SHALL distinguish an empty wishlist from one that could not be read, and SHALL explain
the latter.

#### Scenario: Empty wishlist
- **WHEN** the player's wishlist has no games
- **THEN** the section says so

#### Scenario: Unreadable wishlist
- **WHEN** the wishlist cannot be retrieved
- **THEN** the section says it is unavailable and why, rather than appearing empty

#### Scenario: Retained entries during unavailability
- **WHEN** the wishlist cannot be refreshed but entries were previously retrieved
- **THEN** those entries remain listed with their dated prices

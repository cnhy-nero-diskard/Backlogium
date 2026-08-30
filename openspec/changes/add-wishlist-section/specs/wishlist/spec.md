## ADDED Requirements

### Requirement: The wishlist is retrieved, and ordered by what is worth deciding on now
The system SHALL retrieve the player's Steam wishlist. It SHALL present entries currently observed
to be discounted ahead of those that are not, and SHALL otherwise present entries in the priority
order Steam reports, preserving that order within each group. A retained observation of a discount
SHALL NOT promote an entry. The app SHALL NOT modify the wishlist.

#### Scenario: Wishlist retrieved
- **WHEN** the player's wishlist is fetched successfully
- **THEN** its entries are available

#### Scenario: Ordering preserved within each group
- **WHEN** the player has assigned priorities in Steam
- **THEN** the app presents entries in that order, rather than re-sorting them on anything of its
  own beyond bringing discounted entries forward

#### Scenario: A running discount comes first
- **WHEN** some entries are currently observed to be discounted
- **THEN** they appear ahead of the rest, in the player's own priority order among themselves

#### Scenario: A discount seen earlier does not come first
- **WHEN** an entry's discount was observed longer ago than the freshness window
- **THEN** it keeps its place in the player's priority order, because a sale seen earlier is not
  evidence of one running now

#### Scenario: No editing
- **WHEN** the player views a wishlist entry
- **THEN** no operation is offered that would add to, reorder, or remove from the Steam wishlist

#### Scenario: Empty wishlist
- **WHEN** the player's wishlist contains no games
- **THEN** the section reports that it is empty, distinctly from being unavailable

### Requirement: Prices are requested without asserting an unverified region
The system SHALL NOT derive a store region or currency from the player's public profile location,
which is a community setting and not the payment-derived Steam Store Country. Where an explicit
store-country setting exists, the system SHALL request prices in it; otherwise it SHALL omit the
region from the request and let Steam resolve one.

#### Scenario: An explicit store country is configured
- **WHEN** a store-country setting exists
- **THEN** prices are requested for that region and presented in its currency

#### Scenario: No explicit store country
- **WHEN** no store-country setting exists, whatever the profile's location says
- **THEN** no region is asserted in the request, and whatever region Steam resolves is used

#### Scenario: Prices displayed as Steam formats them
- **WHEN** a price is shown
- **THEN** it uses the formatted representation Steam supplies for that region rather than one the
  app composes

### Requirement: The absence of a price is a distinct state
Where Steam reports no price for an app, the system SHALL present that the price is unavailable and
SHALL NOT render it as zero, blank, or otherwise resembling a price. The system SHALL NOT assert a
reason for the absence that the price data does not establish.

#### Scenario: A free-to-play game
- **WHEN** a wishlisted app has no price data
- **THEN** the entry shows that no price is available rather than a zero or empty value

#### Scenario: Absence is not failure
- **WHEN** an app returns no price data while the request itself succeeded
- **THEN** this is treated as a known absence, not as a lookup failure to be retried

#### Scenario: Mixed results in one request
- **WHEN** prices are requested for several apps together and some have no price
- **THEN** the apps that do have prices are unaffected, and the response is not discarded

#### Scenario: No invented explanation
- **WHEN** a price is unavailable
- **THEN** the app does not claim the game is free, unreleased, or region-locked unless it has
  established which

### Requirement: A shown price is always dated
The system SHALL retain the last observed price for each wishlisted game and SHALL present, with
any price it shows, when that price was observed. A retained price SHALL NOT be presented as
current.

#### Scenario: Freshly refreshed
- **WHEN** prices have just been refreshed
- **THEN** the entries show prices as current and include the date each price was observed

#### Scenario: Offline
- **WHEN** the section is opened with no network
- **THEN** the last observed prices are shown, each with when it was observed

#### Scenario: Refresh fails
- **WHEN** a price refresh fails
- **THEN** previously observed prices are retained with their original dates rather than cleared

#### Scenario: Never observed
- **WHEN** a wishlisted game has no observed price
- **THEN** no price is claimed for it

### Requirement: Prices are refreshed on viewing, within a freshness window
The system SHALL refresh the wishlist and its prices when the section is opened, unless the
wishlist membership and the retained prices are both newer than a documented freshness window, so
that repeated navigation does not re-request. Fresh prices alone SHALL NOT suppress a refresh while
the last membership read did not succeed.

#### Scenario: Opening with stale prices
- **WHEN** the section is opened and the retained prices are older than the freshness window
- **THEN** prices are refreshed

#### Scenario: Opening with fresh prices
- **WHEN** the section is opened again shortly after a refresh in which the wishlist was read
- **THEN** no further request is made and the retained prices are shown as current, each with its
  observation date

#### Scenario: A failed membership read is retried
- **WHEN** the last wishlist read failed but the retained prices were observed successfully
- **THEN** opening the section retries the wishlist read rather than treating the section as fresh

#### Scenario: Requests are batched
- **WHEN** prices are refreshed for a wishlist of many games
- **THEN** they are requested together rather than one request per game

#### Scenario: A partial refresh is partial, not total failure
- **WHEN** prices are requested in several groups and one group fails
- **THEN** the successful groups are retained and only the failed group's prices remain at their
  previous values

### Requirement: Price observations accumulate over time
The system SHALL record each observed price for each wishlisted game, retaining prior observations
rather than overwriting them, and SHALL sample prices periodically so that history does not depend
on the player opening the section.

#### Scenario: An observation is recorded
- **WHEN** a price is observed for a wishlisted game
- **THEN** it is recorded alongside when it was observed, without replacing earlier observations

#### Scenario: Sampling without viewing
- **WHEN** the player has not opened the section for some time
- **THEN** prices have continued to be sampled periodically

#### Scenario: Sampling stays off the interactive path
- **WHEN** prices are sampled periodically
- **THEN** the sampling does not run as part of a foreground sync and does not delay any
  interactive operation

#### Scenario: History is not surfaced yet
- **WHEN** the player views a wishlist entry
- **THEN** recorded history is not presented and no price-drop alert is issued

### Requirement: An owned wishlist entry is not presented as wanted
The system SHALL treat a wishlist entry the player already owns as no longer wanted, without
waiting for Steam to remove it from the wishlist.

#### Scenario: A wishlisted game has been purchased
- **WHEN** a wishlist entry matches a game in the player's owned library
- **THEN** it is not presented among wanted games

#### Scenario: Steam has not yet updated
- **WHEN** the game remains on the Steam wishlist despite being owned
- **THEN** the app's presentation still reflects that it is owned

#### Scenario: Ownership arrives later
- **WHEN** a sync first reports a wishlisted game as owned
- **THEN** the section reflects that without needing a wishlist refresh

### Requirement: Each entry links to its Steam store page
Every wishlist entry SHALL offer a link to that game's page on the Steam store.

#### Scenario: Opening a store page
- **WHEN** the player follows an entry's store link
- **THEN** that game's Steam store page opens, in the Steam app where it is installed and able to
  handle the link

#### Scenario: Link available without a price
- **WHEN** an entry has no price available
- **THEN** its store link is still offered

### Requirement: Wishlist unavailability is survivable
Where the wishlist cannot be retrieved, the system SHALL present an empty section explaining that
it is unavailable, SHALL leave every other part of the app unaffected, and SHALL NOT surface the
condition as an app failure.

#### Scenario: Wishlist is private
- **WHEN** the player's wishlist is not publicly readable
- **THEN** the section explains that it cannot be read, and nothing else in the app is affected

#### Scenario: Endpoint unavailable
- **WHEN** the wishlist endpoint cannot be reached or no longer answers as expected
- **THEN** the section reports it is unavailable, previously retained entries and prices remain
  visible with their dates, and no error is raised elsewhere

#### Scenario: No network
- **WHEN** the app has no network connectivity
- **THEN** the section shows retained entries and prices with their dates, and every other feature
  behaves exactly as it does today

#### Scenario: Never configured
- **WHEN** no Steam credentials are configured
- **THEN** the section is not presented at all

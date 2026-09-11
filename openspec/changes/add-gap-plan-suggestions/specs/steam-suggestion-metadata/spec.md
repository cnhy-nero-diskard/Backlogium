## Purpose

Defines bounded, credential-free acquisition and offline caching of Steam review summaries and
participation categories used to explain and refine gap-plan suggestions.

## ADDED Requirements

### Requirement: Steam review summary metadata
The system SHALL acquire each tracked game's all-language Steam review summary without an API key or
Steam account identifier. It SHALL retain Steam's review description, positive count, negative
count, total count, and check time, and SHALL derive ranking quality from positive and negative
counts using a confidence adjustment that prevents a very small review sample from outranking an
established similarly rated game solely because of raw percentage.

#### Scenario: Review summary is available
- **WHEN** Steam returns a valid review summary for a game
- **THEN** its description and raw positive, negative, and total counts are retained with the check time

#### Scenario: Small perfect sample competes with established rating
- **WHEN** one game has a perfect but very small review sample and another has a slightly lower positive percentage across many reviews
- **THEN** recommendation ranking applies review-volume confidence rather than comparing raw percentages alone

#### Scenario: No credential is available
- **WHEN** suggestion metadata is refreshed without configured Steam credentials
- **THEN** the review request remains usable because no API key or Steam account identifier is required

### Requirement: Store participation categories
The system SHALL retain the Store participation categories needed to distinguish single-player games
from games that advertise multiplayer, online co-op, or massively multiplayer participation. These
categories SHALL remain separate from broad Store genres and SHALL NOT be substituted for genres in
genre affinity or filtering. A category SHALL be recognized by its stable Store identifier and never
by its display label, because the Store request already selects a display language and a
label-matched classification would silently classify nothing if that language changed.

#### Scenario: Multiplayer category is present
- **WHEN** Store metadata identifies a game as multiplayer or online co-op
- **THEN** the cached metadata identifies it as eligible for optional multiplayer live enrichment

#### Scenario: Category and genre differ
- **WHEN** a Store response contains both participation categories and broad genres
- **THEN** each is retained for its own purpose and categories do not become game genres

#### Scenario: Display language changes
- **WHEN** the same game's categories are returned under a different Store display language
- **THEN** its multiplayer classification is unchanged, because recognition depends on the category identifier rather than the translated label

### Requirement: Bounded best-effort enrichment
Suggestion metadata enrichment SHALL run independently of authenticated Steam sync, process only a
bounded and throttled subset of visible tracked games per run, and continue later when more games
remain. It SHALL NOT delay or fail normal Steam sync or gap-plan generation. Because review
acquisition runs as a second chain alongside the existing Store metadata chain, the bound that
matters SHALL be the combined request rate both chains place on the Store host, not the per-chain
figure; a throttling response SHALL be treated as a transient failure that ends the current batch
rather than as a definitive answer about any game.

#### Scenario: Large visible library
- **WHEN** more visible games need suggestion metadata than one run permits
- **THEN** only the bounded subset is attempted and the remainder stays eligible for later continuation

#### Scenario: Enrichment fails
- **WHEN** Steam metadata acquisition fails transiently
- **THEN** normal sync and local gap-plan generation remain successful

#### Scenario: Hidden game
- **WHEN** a game is hidden before an enrichment batch selects work
- **THEN** it is not selected merely to support gap-plan suggestions

#### Scenario: Store throttles the client
- **WHEN** the Store answers an enrichment request with a rate-limit status
- **THEN** the batch ends and is retried later, and no game is recorded as having no metadata

### Requirement: Offline cache and freshness
The system SHALL serve suggestion metadata from local storage, regard a successful check as fresh
for 30 days, and retain the last successful values when a later refresh fails transiently. A
definitive response with no usable review or category data SHALL be cached as a checked unavailable
result so it does not cause immediate repeated requests. A response the Store declines to answer —
a delisted, region-locked, or non-store app id — SHALL NOT be recorded as a definitive absence, so
such a game is never classified single-player on the strength of an answer that was never given.
Missing and unavailable metadata SHALL remain distinct from a zero review count or a single-player
classification.

A cached record that carries no participation-category data at all SHALL be eligible for refresh
regardless of how recently it was checked, so metadata introduced after a record was written is
acquired on the next enrichment run rather than after that record's freshness window expires.

#### Scenario: Cached metadata offline
- **WHEN** the device is offline and cached suggestion metadata exists
- **THEN** gap planning uses the cached review and participation facts

#### Scenario: Transient refresh failure after success
- **WHEN** refresh fails for a game with previously successful cached metadata
- **THEN** the previous metadata remains available and its original check state is not replaced by fabricated empty values

#### Scenario: Definitive unavailable result
- **WHEN** Steam definitively supplies no usable suggestion metadata for a game
- **THEN** the checked unavailable result remains fresh for 30 days

#### Scenario: Store declines to describe the app
- **WHEN** the Store returns an unsuccessful envelope for an app id, as it does for a delisted or region-locked game
- **THEN** its participation-category data stays unknown rather than being recorded as "checked, advertises none", and the game is not classified single-player

#### Scenario: Record predates participation categories
- **WHEN** a record was written before participation categories were retained and its 30-day window has not elapsed
- **THEN** it is still selected for refresh, because a record with no category data at all has not been checked for the data now required

## ADDED Requirements

### Requirement: HowLongToBeat data source seam
The system SHALL read HowLongToBeat completion-length data through an abstraction that hides the transport, so the client-side implementation can be replaced by a server-side proxy without changing any consumer of the data.

#### Scenario: Consumers depend on the abstraction
- **WHEN** goal tagging, the batch refresh, or the match-review flow needs HLTB data
- **THEN** it obtains it through the data-source abstraction rather than issuing HLTB network calls directly

#### Scenario: Transport is swappable
- **WHEN** the client-side implementation is replaced with an alternative (e.g. a proxy-backed one)
- **THEN** no change is required in the goal, gamification, batch, or review code that consumes HLTB data

### Requirement: Client-side HLTB lookup
The system SHALL query HowLongToBeat directly from the device with no application backend and no API key, resolving the site's current search endpoint and any required request token at call time rather than relying on hard-coded values.

#### Scenario: Endpoint or token has rotated
- **WHEN** a lookup is attempted and HowLongToBeat has changed its search endpoint path or request token since the last call
- **THEN** the system re-resolves the current endpoint and token before searching, and does not permanently cache a stale endpoint or token

#### Scenario: Lookup fails
- **WHEN** an HLTB lookup fails (network error, unresolvable endpoint, or empty response)
- **THEN** the failure is surfaced to the caller and no cached HLTB data for the affected game is overwritten or cleared

### Requirement: Per-game HLTB cache with freshness
The system SHALL store HowLongToBeat data per game keyed by the Steam app id, retaining the resolved HLTB id, the Main Story, Main+Extras, Completionist, and All-Styles completion lengths, and the time the data was fetched.

#### Scenario: Storing a resolved game
- **WHEN** a game is successfully matched and its HLTB data fetched
- **THEN** the system stores its HLTB id, all four completion lengths, and the fetch time keyed by the game's Steam app id

#### Scenario: All metrics retained
- **WHEN** HLTB data is stored for a game
- **THEN** all four completion-length metrics are retained so a consumer can later select a different metric without re-fetching

### Requirement: Per-game fetch on goal tagging
The system SHALL fetch a game's HowLongToBeat data when it is tagged as a goal, using cached data when present and only querying HowLongToBeat when the game has no cached data.

#### Scenario: Goal tagged with no cached data
- **WHEN** a game with no cached HLTB data is tagged as a goal
- **THEN** the system queries HowLongToBeat for that game and stores the result

#### Scenario: Goal tagged with cached data present
- **WHEN** a game that already has cached HLTB data is tagged as a goal
- **THEN** the system uses the cached data and does not issue a new HowLongToBeat query

### Requirement: Batch library refresh with freshness gate
The system SHALL provide a manually triggered batch refresh that resolves HowLongToBeat data across the library, skipping games whose cached data is younger than a freshness window, unless a force option is chosen that re-fetches regardless of age.

#### Scenario: Fresh data skipped
- **WHEN** a batch refresh runs without the force option and a game's cached data is younger than the freshness window
- **THEN** that game is skipped and not re-queried

#### Scenario: Stale or missing data refreshed
- **WHEN** a batch refresh runs and a game has no cached data or its cached data is at least as old as the freshness window
- **THEN** that game is queried and its cached data updated

#### Scenario: Force re-fetch
- **WHEN** a batch refresh runs with the force option
- **THEN** every game is queried regardless of the age of its cached data

#### Scenario: Request volume limited during a sweep
- **WHEN** a batch refresh queries multiple games in one run
- **THEN** the system reuses a single resolved endpoint and token for the run and spaces the queries so it does not issue them as an unthrottled burst

### Requirement: Name matching with confidence flagging
The system SHALL match a Steam game name to HowLongToBeat entries and classify the result: a single sufficiently-confident match resolves automatically, while an ambiguous or low-confidence result is flagged for review with its candidate entries retained.

#### Scenario: Confident match resolves automatically
- **WHEN** a search returns a single entry whose confidence meets the threshold
- **THEN** the game is resolved to that entry without requiring user input

#### Scenario: Ambiguous match flagged for review
- **WHEN** a search returns multiple plausible entries or no entry meets the confidence threshold
- **THEN** the game is flagged as needing review and its candidate entries are retained for later selection

#### Scenario: No results
- **WHEN** a search returns no entries for a game
- **THEN** the game is recorded as unmatched and carries no completion lengths

### Requirement: Manual match resolution
The system SHALL allow the user to resolve a game flagged for review by selecting the correct HowLongToBeat entry from its retained candidates, after which the game is treated as resolved.

#### Scenario: User selects the correct candidate
- **WHEN** the user selects a candidate entry for a game flagged for review
- **THEN** the system stores that entry's HLTB id and completion lengths and clears the review flag

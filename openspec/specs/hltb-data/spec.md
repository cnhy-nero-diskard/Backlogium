# hltb-data

## Purpose

Defines how the app sources, matches, caches, and refreshes HowLongToBeat
completion-length data per game, behind a swappable transport seam, and how
ambiguous matches are flagged for and resolved through manual review.

## Requirements

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

### Requirement: Candidate cover art reference
Retained HowLongToBeat candidates SHALL carry a cover-art reference when the search response supplies
one, so it can be presented without an additional lookup.

#### Scenario: Art reference retained
- **WHEN** a search response supplies a cover-art reference for a candidate
- **THEN** that reference is retained with the candidate as a usable image URL

#### Scenario: Art reference absent
- **WHEN** a search response supplies no cover-art reference for a candidate
- **THEN** the candidate is retained without one and remains fully usable

#### Scenario: Previously cached candidates remain readable
- **WHEN** candidates were cached before cover-art references were retained
- **THEN** they are still readable and selectable, without a cover-art reference and without
  requiring a re-fetch

### Requirement: Candidate lookup without classification
The system SHALL provide a HowLongToBeat candidate lookup that returns scored candidates without
classifying them, without recording a match, and without altering any stored HowLongToBeat data.
This supports correcting a match that was already resolved, where re-running the automatic matcher
would reproduce the same wrong result and discard the candidates needed to fix it.

#### Scenario: Candidates for an already-resolved game
- **WHEN** candidates are looked up for a game whose match is already resolved
- **THEN** the candidates are returned for selection, and the game's stored match, completion
  lengths, match status, and freshness timestamp are all left unchanged

#### Scenario: Candidates returned regardless of confidence
- **WHEN** a lookup returns results that the automatic matcher would resolve confidently
- **THEN** the full scored candidate list is still returned, rather than collapsing to the single
  automatic choice

#### Scenario: A previously corrected match is not overwritten
- **WHEN** the user has manually resolved a game's match and later looks up candidates again
- **THEN** their chosen match remains in effect unless and until they select a different candidate

#### Scenario: Lookup fails
- **WHEN** the candidate lookup fails
- **THEN** no candidates are returned and the game's stored HowLongToBeat data is left intact

#### Scenario: Selecting from a fresh lookup
- **WHEN** the user selects a candidate obtained this way
- **THEN** the match is recorded exactly as it is for a review-flagged game, preserving the existing
  freshness timestamp

### Requirement: Manual match resolution
The system SHALL allow the user to resolve a game flagged for review by selecting the correct HowLongToBeat entry from its retained candidates, after which the game is treated as resolved.

#### Scenario: User selects the correct candidate
- **WHEN** the user selects a candidate entry for a game flagged for review
- **THEN** the system stores that entry's HLTB id and completion lengths and clears the review flag

### Requirement: Refresh an explicit subset of games
The batch HowLongToBeat refresh SHALL accept an explicit set of games to refresh, and SHALL refresh
exactly those games regardless of how recently their data was fetched.

#### Scenario: Refreshing a subset
- **WHEN** a refresh is requested for an explicit set of games
- **THEN** only those games are queried

#### Scenario: Freshness window bypassed for an explicit selection
- **WHEN** a game in an explicit selection has data fetched more recently than the freshness window
- **THEN** it is still refreshed, because the explicit selection expresses intent

#### Scenario: Whole-library refresh unchanged
- **WHEN** a refresh is requested without an explicit subset
- **THEN** it behaves as today: stale and missing games only, unless a force refresh was requested

### Requirement: Per-game batch outcomes reported
The batch refresh SHALL report, as it proceeds, which game was just processed and what its outcome
was, so a caller can present a live log.

#### Scenario: Outcome reported per game
- **WHEN** a game in the batch has been processed
- **THEN** the batch reports that game along with whether it resolved, needs review, had no match,
  or failed to look up

#### Scenario: Failed lookup distinguished from no match
- **WHEN** a game's lookup fails for transport reasons
- **THEN** it is reported as a failed lookup, distinct from a successful search that found no
  candidates, and its cached data is left intact

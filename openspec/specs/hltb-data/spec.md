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
The system SHALL query HowLongToBeat directly from the device with no application backend and no
API key, resolving the site's current search endpoint and any required request token at call time
rather than relying on hard-coded values. A lookup SHALL be issued only for games the user named —
a single game, or an explicit selection of games. The system SHALL NOT issue a lookup for a game
the user did not name, and SHALL NOT derive the set of games to look up from the library, from a
freshness threshold, or from any other implicit criterion.

#### Scenario: Endpoint or token has rotated
- **WHEN** a lookup is attempted and HowLongToBeat has changed its search endpoint path or request token since the last call
- **THEN** the system re-resolves the current endpoint and token before searching, and does not permanently cache a stale endpoint or token

#### Scenario: Lookup fails
- **WHEN** an HLTB lookup fails (network error, unresolvable endpoint, or empty response)
- **THEN** the failure is surfaced to the caller and no cached HLTB data for the affected game is overwritten or cleared

#### Scenario: No lookup without an explicit target
- **WHEN** no game has been explicitly named for lookup
- **THEN** no request is issued to HowLongToBeat

#### Scenario: Library size does not imply lookups
- **WHEN** the library grows, whether by sync, import, or restore
- **THEN** no lookup is issued as a consequence

#### Scenario: Age does not imply lookups
- **WHEN** stored HowLongToBeat data becomes old
- **THEN** no lookup is issued as a consequence, and the data remains in use until the user
  looks the game up or a dataset supersedes it

### Requirement: Per-game HLTB cache with freshness
The system SHALL store HowLongToBeat data per game keyed by the Steam app id, retaining the
resolved HLTB id, the Main Story, Main+Extras, Completionist, and All-Styles completion lengths,
the time the data was gathered, and whether it was gathered by a lookup on this device or supplied
by an applied dataset.

#### Scenario: Storing a resolved game
- **WHEN** a game is successfully matched and its HLTB data fetched
- **THEN** the system stores its HLTB id, all four completion lengths, and the fetch time keyed by the game's Steam app id

#### Scenario: All metrics retained
- **WHEN** HLTB data is stored for a game
- **THEN** all four completion-length metrics are retained so a consumer can later select a different metric without re-fetching

#### Scenario: Origin retained
- **WHEN** HLTB data is stored for a game
- **THEN** whether it came from a device lookup or from an applied dataset is retained alongside it

#### Scenario: Rows stored before origin was recorded
- **WHEN** a row was stored before origin was retained
- **THEN** it remains readable and usable, and is treated as having come from a device lookup

### Requirement: Per-game fetch on goal tagging
The system SHALL resolve a game's HowLongToBeat data when it is tagged as a goal, using cached data
when present, then data supplied by an applied dataset, and only querying HowLongToBeat when
neither has an answer for that game.

#### Scenario: Goal tagged with cached data present
- **WHEN** a game that already has cached HLTB data is tagged as a goal
- **THEN** the system uses the cached data and does not issue a new HowLongToBeat query

#### Scenario: Goal tagged with dataset coverage
- **WHEN** a game with no cached HLTB data but covered by an applied dataset is tagged as a goal
- **THEN** the dataset's values are used and no HowLongToBeat query is issued

#### Scenario: Goal tagged with no cached data
- **WHEN** a game with no cached HLTB data and no dataset coverage is tagged as a goal
- **THEN** the system queries HowLongToBeat for that game and stores the result

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
The HowLongToBeat lookup SHALL accept an explicit set of games, and SHALL look up exactly those
games regardless of how recently their data was gathered. An explicit set is the only way to look
up more than one game.

#### Scenario: Refreshing a subset
- **WHEN** a lookup is requested for an explicit set of games
- **THEN** only those games are queried

#### Scenario: Freshness window bypassed for an explicit selection
- **WHEN** a game in an explicit selection has data gathered very recently
- **THEN** it is still looked up, because the explicit selection expresses intent — there is no
  longer any age threshold that could exempt it

#### Scenario: Dataset coverage bypassed for an explicit selection
- **WHEN** a game in an explicit selection is already covered by an applied dataset
- **THEN** it is still looked up, and the result supersedes the dataset's values for that game

#### Scenario: Whole-library refresh unchanged
- **WHEN** a lookup is requested without an explicit set of games
- **THEN** the request is not satisfiable, because no whole-library variant remains — the set of
  games to look up can only be named by the user

#### Scenario: Request volume limited across a selection
- **WHEN** a lookup runs over an explicit selection of several games
- **THEN** the system reuses a single resolved endpoint and token for the run and spaces the
  queries so it does not issue them as an unthrottled burst

### Requirement: Per-game batch outcomes reported
A lookup over an explicit selection SHALL report, as it proceeds, which game was just processed and
what its outcome was, so a caller can present a live log.

#### Scenario: Outcome reported per game
- **WHEN** a game in the selection has been processed
- **THEN** the run reports that game along with whether it resolved, needs review, had no match,
  or failed to look up

#### Scenario: Failed lookup distinguished from no match
- **WHEN** a game's lookup fails for transport reasons
- **THEN** it is reported as a failed lookup, distinct from a successful search that found no
  candidates, and its cached data is left intact

### Requirement: Session re-resolution is triggered only by evidence of rotation or expiry
The system SHALL re-resolve the scraped session — its endpoint and token — only when a failure
indicates that the endpoint rotated or the token expired. Transport failures, server errors,
throttling, and unusable response bodies SHALL NOT trigger re-resolution, so a transient failure
does not multiply requests against the scraped service.

#### Scenario: Rejection indicating a stale session
- **WHEN** a search is rejected in a way that indicates a rotated endpoint or an expired token
- **THEN** the session is re-resolved once and the search retried once

#### Scenario: Transport failure
- **WHEN** a search fails because of a timeout, connection failure, or name-resolution failure
- **THEN** no re-resolution occurs and the failure is reported as transient

#### Scenario: Server error
- **WHEN** a search fails with a server-side error status
- **THEN** no re-resolution occurs and the failure is reported as transient

#### Scenario: Throttling
- **WHEN** a search is throttled
- **THEN** no re-resolution occurs and the failure is reported in a way that allows the caller to
  back off

#### Scenario: Unusable response body
- **WHEN** a search returns a successful response whose body cannot be interpreted
- **THEN** no re-resolution occurs, and the failure is reported as unlikely to succeed on retry

#### Scenario: Re-resolution happens at most once per search
- **WHEN** a search triggers a re-resolution and the retry also fails
- **THEN** no further re-resolution is attempted for that search

#### Scenario: Classification does not depend on message text
- **WHEN** a failure is classified
- **THEN** the classification uses structured information such as the response status, not the
  text of an error message

### Requirement: Batch outcomes reported reflect what actually happened
A lookup over an explicit selection SHALL distinguish games that were resolved, games for which no
match exists, and games whose lookup failed. Progress reported to the user on completion SHALL
state the number actually resolved, and SHALL NOT report attempted work as completed work.

#### Scenario: Every lookup in a batch fails
- **WHEN** all lookups in a selection fail
- **THEN** the completion report states that none were resolved

#### Scenario: Genuine absence of a match
- **WHEN** a lookup succeeds and establishes that no match exists
- **THEN** that game is reported as having no match rather than as a failure

#### Scenario: Progress still advances on failure
- **WHEN** a lookup fails partway through a selection
- **THEN** the attempted-progress count still advances, so a progress indicator continues and
  terminates

#### Scenario: Mixed batch
- **WHEN** a selection contains resolved games, no-match games, and failures
- **THEN** the completion report states the resolved count, not the selection size

#### Scenario: Cancellation is not a failure
- **WHEN** a run over a selection is cancelled
- **THEN** cancellation propagates rather than being classified as a lookup failure

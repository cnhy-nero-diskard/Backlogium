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

### Requirement: User-triggered broader HLTB search
The system SHALL provide a broader candidate search only for a game whose completed primary HLTB
search returned no candidates. Broader search SHALL be triggered explicitly by the user, SHALL issue
no more than three additional distinct title queries, and SHALL preserve the unmatched row unless
candidate results are successfully obtained.

#### Scenario: Broader search becomes eligible
- **WHEN** a game's primary HLTB search completed successfully with no candidates
- **THEN** the game is eligible for an explicitly triggered broader search

#### Scenario: Primary search has candidates
- **WHEN** a primary search resolved a match or retained candidates for review
- **THEN** broader search is not automatically run or offered as a no-match recovery action

#### Scenario: Broader search is triggered
- **WHEN** the user activates broader search for an unmatched game
- **THEN** the system submits at most three ordered, non-empty, distinct title variants
- **AND** spaces the requests rather than issuing an unthrottled burst

#### Scenario: Broader search fails
- **WHEN** any broader lookup ends in a transport, endpoint, or parsing failure and no candidates were obtained
- **THEN** the failure is surfaced distinctly from an exhausted successful search
- **AND** the stored unmatched result is left intact

#### Scenario: Broader search also finds nothing
- **WHEN** every broader query completes successfully without candidates
- **THEN** the game remains unmatched
- **AND** the result is reported as no broader matches rather than as a transport failure

### Requirement: Deterministic relaxed query generation
The system SHALL derive broader queries deterministically from the original Steam title by removing
recognized edition/storefront noise, optionally reducing a subtitle, and optionally normalizing a
leading article or terminal sequel numeral. It SHALL retain meaningful core and sequel identity and
discard variants that are empty, unchanged, or duplicates after normalization.

#### Scenario: Edition-heavy Steam title
- **WHEN** a title contains recognized suffixes such as `Enhanced Edition`, `Definitive Edition`, `Game of the Year`, `GOTY`, or `Remastered`
- **THEN** a broader variant removes that noise while retaining the base title and sequel number

#### Scenario: Subtitled game
- **WHEN** a title contains a subtitle separated from a non-empty core title
- **THEN** a broader variant can search the core title without the subtitle

#### Scenario: Sequel numeral variant
- **WHEN** a title has a terminal Arabic or Roman sequel numeral with a safe equivalent
- **THEN** at most one equivalent numeral variant can be generated

#### Scenario: Generated variants collapse together
- **WHEN** two transformations produce the same normalized query or an empty query
- **THEN** the duplicate or empty query is omitted and does not consume another request

### Requirement: Conservative fuzzy candidate scoring
Candidates found through broader queries SHALL be deduplicated by positive HLTB id and scored
against the original Steam title rather than against the relaxed query. Scoring SHALL consider name
similarity, token overlap, core-title containment, low-value edition terms, and conflicting sequel
numbers. Every broader-search result SHALL require manual review regardless of score.

#### Scenario: Candidate appears in multiple broader searches
- **WHEN** multiple query variants return the same positive HLTB id
- **THEN** the candidate appears once using the richest available payload

#### Scenario: Relaxed query omits title detail
- **WHEN** a candidate was discovered by a shortened query
- **THEN** its confidence is calculated against the original Steam title

#### Scenario: Sequel numbers conflict
- **WHEN** the original title and a candidate contain conflicting sequel numbers
- **THEN** the candidate receives a strong ranking penalty

#### Scenario: Broader result looks highly confident
- **WHEN** a broader-search candidate exceeds the ordinary automatic-match threshold
- **THEN** it is still stored as needing review and is not resolved automatically

#### Scenario: Broader candidates are found
- **WHEN** one or more broader queries return candidates
- **THEN** their deduplicated scored candidates are retained for review
- **AND** the prior unmatched game becomes a needs-review game without changing its original fetch timestamp

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

### Requirement: Candidate discovery provenance remains backward compatible
Retained candidates SHALL identify whether they came from a primary search, broader search, or manual
link lookup, using a serialized field whose absent value defaults to primary search.

#### Scenario: New broader candidate is retained
- **WHEN** a broader search stores candidates for review
- **THEN** each stored candidate identifies broader search as its source

#### Scenario: Manual link candidate is previewed
- **WHEN** a direct game-link lookup returns a candidate
- **THEN** it identifies manual link as its source

#### Scenario: Old candidate JSON is read
- **WHEN** retained candidate JSON was written before discovery provenance existed
- **THEN** it remains readable and defaults to primary-search provenance

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

### Requirement: Canonical HLTB game-link validation
The system SHALL accept a pasted HLTB game link only when it is an absolute HTTPS URL on the exact
HowLongToBeat host, contains no credentials or custom port, and identifies one positive numeric game
id on the supported game route. The system MUST construct its own canonical URL from the parsed id
and MUST NOT request the pasted URL verbatim.

#### Scenario: Canonical game link is pasted
- **WHEN** the user provides `https://howlongtobeat.com/game/{positive-id}` with an optional trailing slash
- **THEN** the system extracts the positive HLTB id and constructs the canonical game URL internally

#### Scenario: Supported www host is pasted
- **WHEN** the user provides the equivalent HTTPS game link on `www.howlongtobeat.com`
- **THEN** it is normalized to the canonical non-www HLTB game URL

#### Scenario: Untrusted or ambiguous link is pasted
- **WHEN** the URL uses another scheme or host, contains user-info, a custom port, query or fragment data, lacks a positive game id, or uses an unsupported path
- **THEN** validation fails locally
- **AND** no network request or stored-data mutation occurs

### Requirement: Direct HLTB game lookup through the data-source seam
The HLTB data-source abstraction SHALL support loading one game entry by validated HLTB id, returning
its id, title, cover reference, and all available completion lengths. The client-side transport SHALL
request only the internally constructed canonical page and parse structured page data through an
isolated, fixture-tested parser.

#### Scenario: Linked game loads successfully
- **WHEN** a validated HLTB id resolves to a readable game page
- **THEN** the data source returns one candidate containing the id, title, cover when supplied, and every available completion length

#### Scenario: Linked game does not exist
- **WHEN** the canonical game page indicates that the id is not found
- **THEN** the caller receives a not-found result distinct from a transport or parse failure

#### Scenario: Linked lookup fails
- **WHEN** the page request fails or its required structured data cannot be parsed
- **THEN** the failure is surfaced to the caller
- **AND** no cached HLTB row is overwritten or cleared

#### Scenario: Transport is replaced
- **WHEN** the client-side page reader is replaced by a proxy-backed data source
- **THEN** link preview, repository, and UI consumers continue to request direct lookup through the same abstraction

### Requirement: Manual HLTB link preview and resolution
The system SHALL resolve a validated pasted link into a non-persisted candidate preview and SHALL
require explicit user confirmation before storing that candidate as the game's resolved HLTB match.
Invalid input, dismissal, not-found, or lookup failure SHALL preserve the game's previous state.

#### Scenario: Valid linked candidate is previewed
- **WHEN** direct lookup succeeds for a validated pasted link
- **THEN** the candidate is returned for preview without changing the stored match

#### Scenario: User confirms the linked candidate
- **WHEN** the user confirms the previewed linked candidate
- **THEN** the system stores its HLTB id and all available lengths through the normal manual-resolution path
- **AND** clears any prior review candidates

#### Scenario: User rejects the linked candidate
- **WHEN** the user dismisses or rejects the preview
- **THEN** the existing resolved, needs-review, or unmatched row remains unchanged

#### Scenario: Link input cannot be resolved
- **WHEN** link validation, direct lookup, or page parsing fails
- **THEN** the failure is reported without modifying stored HLTB data

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

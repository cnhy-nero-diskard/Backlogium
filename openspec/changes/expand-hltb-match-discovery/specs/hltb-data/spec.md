## ADDED Requirements

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

# Spec Delta

## ADDED Requirements

### Requirement: Discovery session requests share a consistent client contract
Discovery SHALL use a consistent client identity and same-origin request context for session initialization, public endpoint discovery, and search POSTs. Header alignment SHALL NOT introduce automatic header cycling, denial retries, or changes to Steam and direct-page refresh request defaults.

#### Scenario: Initialization followed by search
- **WHEN** discovery initializes a session and sends a search
- **THEN** both requests use the same user agent, the HLTB origin and homepage referer, and appropriate accept headers, with session credentials confined to the search request

#### Scenario: Access denial with aligned headers
- **WHEN** an HLTB request returns access denial or a challenge
- **THEN** discovery stops without retrying with different headers, resetting the session, discovering another endpoint, or requesting another app

### Requirement: Session setup failures are not game search failures
Discovery SHALL distinguish session initialization and endpoint-discovery failures from failures of a game search POST. After bounded setup attempts fail, discovery SHALL stop subsequent setup and search work. Apps with no search POST attempt SHALL remain unattempted rather than failed or unmatched; prior per-app search results SHALL be retained.

#### Scenario: Initial setup fails
- **WHEN** initialization fails before any game search POST begins
- **THEN** all otherwise searchable apps remain unattempted and a run-level setup failure identifies the sanitized phase and category

#### Scenario: Session expires during a run
- **WHEN** session renewal fails before the next app's search POST begins
- **THEN** previous candidates and unmatched results remain intact, the next and later searchable apps remain unattempted, and setup failure is recorded separately

#### Scenario: Setup retries are exhausted
- **WHEN** a transient setup failure exhausts existing retry limits or a successful setup response is malformed
- **THEN** later apps do not repeat session initialization and no broader query is attempted

#### Scenario: Search itself is denied
- **WHEN** session initialization succeeds but the app's search POST returns access denial
- **THEN** that app has a search failure, subsequent apps remain unattempted, and the failure is not misreported as initial session setup

### Requirement: Discovery outcomes identify blocked setup
Discovery SHALL report blocked with exit code 1 when session setup fails before any game search POST begins. If earlier game search POSTs began, setup failure SHALL yield partial with exit code 2 and preserve prior outcomes. A successful search with no results SHALL remain unmatched, not a setup failure. No searchable apps SHALL require no session setup.

#### Scenario: No search can begin
- **WHEN** initial session setup fails
- **THEN** the execution report is blocked, the process returns 1, and the private review preserves unattempted app accounting

#### Scenario: Partial results precede renewal failure
- **WHEN** at least one game search POST began before session renewal fails
- **THEN** the execution report is partial and the process returns 2, even if earlier successful searches returned empty lists

#### Scenario: No uncovered searchable apps
- **WHEN** the selected scope contains no uncovered apps with searchable titles
- **THEN** discovery makes no HLTB session request and retains existing empty-scope or invalid-title outcome semantics

### Requirement: Session diagnostics are private compatible and sanitized
New setup-failure diagnostics SHALL contain only an allowlisted phase, fixed failure category, and optional validated HTTP status. Execution reports and offline review inspection SHALL expose these diagnostics without credentials or network access. Existing version-1 reviews SHALL remain readable, and strict validation of unknown fields, approvals, and input-byte bindings SHALL remain effective.

#### Scenario: Inspect a failed setup review
- **WHEN** a saved review contains setup-failure metadata
- **THEN** offline inspection distinguishes the run-level failure from per-app search failures without reading credentials or issuing requests

#### Scenario: Inspect a legacy review
- **WHEN** a version-1 review has no setup-failure field
- **THEN** it retains its prior validation, decision, inspection, and approved-pair refresh behavior without migration or rewritten bytes

#### Scenario: Untrusted error detail
- **WHEN** upstream error text contains a token, account identifier, public IP, URL, private path, or an unknown error value
- **THEN** diagnostics omit that text and retain only validated phase, category, and HTTP status, without inferring an unobserved error value

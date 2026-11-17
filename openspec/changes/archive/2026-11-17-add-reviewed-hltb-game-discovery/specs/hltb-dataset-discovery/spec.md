# Spec Delta

## Purpose

Defines bounded discovery and human review of HLTB candidates for uncovered games in explicitly selected Steam libraries, without treating search suggestions as approved mappings or fresh completion observations.

## ADDED Requirements

### Requirement: Discovery searches only selected uncovered games
Discovery SHALL be an explicit stage targeting only apps absent from the canonical mappings within the union of selected readable Steam libraries intersected with any app-ID restriction. It SHALL NOT crawl a catalog, search covered apps, or add unowned apps from a restriction. Unavailable libraries SHALL remain distinct from readable empty libraries.

#### Scenario: Restricted mixed library
- **WHEN** selected libraries contain covered and uncovered apps and a restriction also names an unowned app
- **THEN** only uncovered apps inside the owned-library intersection are searched, with overlapping app IDs searched once

#### Scenario: No readable library
- **WHEN** no selected library is readable
- **THEN** discovery reports blocked scope and issues no HLTB request or dataset write

#### Scenario: Partially readable libraries
- **WHEN** some selected libraries are unavailable but another is readable
- **THEN** discovery can process readable scope and reports partial coverage using aggregate library counts without account attribution

### Requirement: Discovery plans do not search HLTB
A discovery dry run SHALL report uncovered target apps and finite request limits without issuing HLTB requests, creating review candidates, writing execution checkpoints, changing successful-fetch history, or producing a dataset candidate. Only an explicitly requested plan report SHALL be written.

#### Scenario: Plan only
- **WHEN** discovery is invoked with dry-run enabled
- **THEN** it can read Steam libraries but returns a plan rather than searched candidates and leaves dataset and execution state unchanged

### Requirement: Searches return ranked suggestions without acceptance
Primary discovery SHALL search the Steam title and retain scored candidates without automatic classification. Candidate IDs SHALL be positive safe integers and duplicates SHALL be collapsed per app. Ranking SHALL use the original Steam title. Exact titles and high scores SHALL NOT confer approval.

#### Scenario: One exact result
- **WHEN** a primary search returns one exact-title candidate
- **THEN** it is retained as pending and neither mapped nor scheduled for a direct-page fetch by discovery

#### Scenario: Ambiguous candidates
- **WHEN** a search returns multiple plausible HLTB entries
- **THEN** candidates remain individually reviewable with their identities, titles, available length previews, and ranking guidance

### Requirement: Broader discovery requires an explicit bounded option
Broader queries SHALL run only when explicitly requested and the primary search completed successfully with zero candidates. They SHALL use at most three distinct non-empty title variants preserving core and sequel identity. Results SHALL be deduplicated by HLTB ID, ranked against the original title with conflicting sequel penalties, and remain pending.

#### Scenario: Opted-in broader search
- **WHEN** broader discovery was requested and the primary search succeeds without candidates
- **THEN** at most three additional spaced queries are issued and their candidates remain subject to human approval

#### Scenario: Primary search fails or finds candidates
- **WHEN** the primary search fails or returns any candidates
- **THEN** no broader queries are issued for that app

### Requirement: Search requests preserve bounded access behavior
Discovery SHALL preserve the refresh workflow's request spacing, concurrency, timeout, response-size, retry, and run-budget limits across Steam reads, session setup, endpoint discovery, and searches. Endpoint discovery SHALL remain same-origin and finitely bounded. HLTB access-denied or challenge responses SHALL stop further HLTB work without bypass or session-reset retries.

#### Scenario: Access denial during initialization
- **WHEN** HLTB returns access denial or a challenge during session setup
- **THEN** no alternate endpoint, browser workaround, broader search, or later app request is attempted

#### Scenario: Rate limit exceeds remaining budget
- **WHEN** required Retry-After waiting exceeds the approved runtime budget
- **THEN** discovery stops within the budget and distinguishes failed work from apps never attempted

#### Scenario: Endpoint rotation
- **WHEN** the search fast path is absent without access denial or challenge evidence
- **THEN** only bounded same-origin endpoint discovery is attempted and all requests consume the existing budget

### Requirement: Review artifacts are private and explicit
Discovery SHALL save a versioned review artifact under ignored `.local/` containing baseline identity, scoped Steam app IDs and titles, per-app search outcomes, and candidate HLTB IDs, titles, four-style minute previews, sources, scores, and pending/approved/rejected status. Previews SHALL be labeled non-authoritative. Secrets, tokens, raw responses, and account attribution SHALL NOT be stored in it.

#### Scenario: Candidates saved for review
- **WHEN** a discovery run obtains candidates
- **THEN** an atomic private artifact contains those suggestions with pending status and does not alter the canonical dataset or successful-fetch history

#### Scenario: No candidate returned
- **WHEN** a valid search completes with zero results
- **THEN** that app remains in the artifact as unmatched rather than disappearing or being reported as a request failure

#### Scenario: Unsafe output path
- **WHEN** a review destination escapes ignored `.local/` or aliases an input, source, report, checkpoint, or history path through normal paths, symlinks, or hard links
- **THEN** the operation is rejected before overwriting the protected file

### Requirement: Every new pair requires explicit human approval
Review SHALL allow pending, approved, or rejected decisions for each candidate, with at most one approved HLTB ID per Steam app. Missing or unknown statuses, duplicate identities, multiple approvals, and invalid identifiers SHALL block consumption. Agents SHALL record approval only for the exact pairs the human explicitly approved, never from similarity, search text, or a general request to fetch.

#### Scenario: Human chooses one candidate
- **WHEN** a person approves one candidate for an app with several suggestions
- **THEN** only that app/HLTB pair becomes eligible for the refresh handoff and other pending or rejected candidates are not admitted

#### Scenario: No approval or rejection only
- **WHEN** candidates remain pending or are all rejected
- **THEN** no new mapping or direct-page fetch is authorized for that app by those suggestions

#### Scenario: Conflicting approvals
- **WHEN** two candidates for one app are marked approved
- **THEN** review consumption blocks without choosing a winner or producing a dataset candidate

### Requirement: Discovery and review outcomes remain distinct
Discovery reports SHALL distinguish candidate-bearing apps and candidate pairs, approved and rejected pairs, pending pairs, unmatched apps, search failures, and unattempted apps. Counts SHALL name their units. Unmapped SHALL mean absence of an admitted mapping, not proof no HLTB entry exists. Search previews SHALL NOT count as successful completion-time observations.

#### Scenario: Mixed search and review state
- **WHEN** some apps have pending candidates, another was rejected, another has no results, and another was never attempted
- **THEN** each disposition is separately reported and none is counted as approved, fetched, or successfully refreshed

### Requirement: Review preserves decisions and ownership disclosure
Discovery SHALL NOT overwrite an existing review artifact or silently reset its decisions. Documentation and assistance SHALL explain discovery, exact-pair approval, final direct-page fetching, offline verification, and separate apply/publication gates. They SHALL disclose that publishing mappings or game-specific gaps reveals owned app IDs, while private review files are not publication artifacts.

#### Scenario: Rerun would overwrite decisions
- **WHEN** a discovery destination already exists
- **THEN** discovery refuses replacement and requires a fresh destination rather than resetting approvals or rejections

#### Scenario: Human authorizes discovery only
- **WHEN** the user authorizes a bounded search but not pair approval, final fetching, dataset application, or publication
- **THEN** assistance stops at reviewable suggestions and does not infer authority for those later stages

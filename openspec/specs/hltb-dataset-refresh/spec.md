# hltb-dataset-refresh Specification

## Purpose

Defines maintainer-invoked completion-time refreshes restricted to selected Steam libraries, with reviewed mappings, truthful observations, resumable execution, and compatible dataset/report outputs.

## Requirements

### Requirement: Refresh scope comes from selected readable owned libraries
The tool SHALL accept one or more valid SteamID64 values, resolve their current owned libraries, and deduplicate the union of app IDs. An optional app-ID restriction SHALL intersect that union and SHALL NOT enlarge it. Missing, malformed, or unreadable library responses SHALL be reported as unavailable scope rather than interpreted as empty libraries. The tool SHALL NOT crawl the HLTB catalog or target all canonical mappings by default.

#### Scenario: Overlapping libraries and an app restriction
- **WHEN** two readable libraries overlap and an app restriction includes an unowned app
- **THEN** the overlapping app is targeted once and the unowned app is excluded

#### Scenario: Some libraries are unreadable
- **WHEN** at least one selected library is readable and another is unavailable
- **THEN** readable scope can proceed and the result explicitly reports partial library coverage without account identities

#### Scenario: No readable scope
- **WHEN** no selected library supplies a readable owned-library response
- **THEN** the run reports blocked scope and leaves the dataset unchanged

### Requirement: Refresh modes use entry evidence within selected scope
The tool SHALL provide missing-only, stale with a positive age threshold, and refresh-all modes scoped to selected libraries. An optional app-ID restriction SHALL intersect, never expand, that scope. Missing-only SHALL select mapped entries without a length tuple and report unmapped titles; refresh-all SHALL select every reviewed HLTB ID in scope.

#### Scenario: Default missing-only scope
- **WHEN** missing-only runs against a library containing mapped lengths, mapped missing lengths, and unmapped titles
- **THEN** only the mapped missing lengths are fetched and the unmapped titles are listed for review

#### Scenario: Refresh-all remains targeted
- **WHEN** refresh-all is selected
- **THEN** only reviewed HLTB IDs reached from the selected library/app intersection are fetched

### Requirement: Stale mode uses per-entry successful-fetch evidence
Stale mode SHALL select missing lengths and entries whose successful per-entry fetch evidence is absent, inapplicable, or older than the requested threshold. A dataset-wide timestamp SHALL NOT establish successful fetch evidence for an individual entry.

#### Scenario: Stale mode without trustworthy entry history
- **WHEN** a selected entry has no applicable successful-fetch history but the canonical dataset has a recent global timestamp
- **THEN** the entry is treated as having unknown freshness and is eligible for refresh

### Requirement: Mapping admission requires an explicit reviewed correspondence
The tool SHALL reuse canonical reviewed correspondences and accept explicit reviewed mapping input in the existing contribution format. Only mappings inside selected scope SHALL be admitted. An unmapped or ambiguous title SHALL be reported for human review without automatic title matching. A correspondence disagreement SHALL identify both HLTB IDs and block candidate output rather than replace the canonical mapping.

#### Scenario: Reviewed new mapping inside scope
- **WHEN** an explicit contribution supplies a valid new mapping for a selected app
- **THEN** that mapping is eligible for the candidate and no mappings outside scope are admitted

#### Scenario: Conflicting reviewed correspondence
- **WHEN** an input maps an existing app to a different HLTB ID
- **THEN** the conflict is reported with the app and both HLTB IDs and no publishable candidate is emitted

### Requirement: Fetches validate identity and have bounded request behavior
The tool SHALL fetch each selected HLTB ID at most once per run observation, even when multiple apps share it. It SHALL validate that each response identifies the requested entry and contains recognized completion-time data. Known values SHALL become integer minutes; unknown values SHALL remain null. Invalid identity, units, values, or structure SHALL be reported as failures.

#### Scenario: Shared HLTB entry
- **WHEN** several selected apps map to one HLTB ID
- **THEN** one successful observation supplies their common lengths and the report identifies all affected app mappings

#### Scenario: Redirect or malformed response
- **WHEN** a response describes a different entry or contains an unrecognized payload
- **THEN** it is recorded as a failure and its values are not merged

### Requirement: Request budgets bound retries and waiting
Requests SHALL have finite limits for concurrency, timeout, retries, and total request/wait time. Valid rate-limit instructions SHALL be respected within those limits; exhaustion SHALL be reported explicitly.

#### Scenario: Rate-limit budget exhausted
- **WHEN** backoff would exceed the remaining run budget
- **THEN** the tool stops further requests and reports rate-limited or unattempted entries without marking them successful

### Requirement: Failed observations preserve existing values and fetch evidence
On failed, skipped, or no-usable-length fetches, the tool SHALL retain valid existing mappings and length tuples, SHALL NOT resubmit retained values as newly fetched, and SHALL NOT advance successful-fetch timestamps for those entries.

#### Scenario: One success and one failed read
- **WHEN** one selected entry changes and another fails
- **THEN** the candidate contains the successful change, retains the failed entry's values, and the report and entry history preserve its failure and previous successful-fetch time

### Requirement: Contributions include only validated observations
Contributions SHALL contain only validated observations and explicit reviewed mappings. Validated partial lengths SHALL preserve unknown styles as null; an all-null result SHALL not replace existing values. Reports SHALL distinguish partial from complete refresh.

#### Scenario: No usable lengths
- **WHEN** a valid page supplies no known completion lengths
- **THEN** the result records no usable lengths, omits an all-null contribution tuple, and retains any existing canonical length tuple

#### Scenario: Global metadata after partial success
- **WHEN** the existing merge rules advance the candidate's dataset-wide timestamp after a successful change
- **THEN** the report explains that this is global merge metadata and does not claim that failed or untouched entries were refreshed

### Requirement: Candidate datasets obey the existing canonical contract
The tool SHALL output schema-v1 datasets containing only `schemaVersion`, `datasetVersion`, `gatheredAt`, `mappings`, and `lengths`. It SHALL preserve existing numeric bounds, null representation, canonical ordering, merge precedence, conflict behavior, and version rules. A byte-identical merge SHALL preserve metadata.

#### Scenario: Redundant validated observations
- **WHEN** successful observations add no mappings or changed lengths
- **THEN** candidate bytes and dataset metadata match the source even though private successful-fetch history can advance

### Requirement: Candidate output is separate and atomic
The tool SHALL validate and write candidates atomically to a destination separate from the canonical source. It SHALL leave the source unchanged and verify the source still matches the recorded baseline before finalizing the candidate.

#### Scenario: Source changes during a run
- **WHEN** the canonical source differs from the recorded baseline before output is finalized
- **THEN** candidate publication is blocked and the original observations are retained for explicit review without changing their timestamps

### Requirement: Dry runs preview scope without HLTB observations
A dry run SHALL resolve selected Steam libraries and report the planned mode, mapping gaps, eligible unique HLTB IDs, and request bounds. It SHALL perform no HLTB fetch, produce no dataset candidate, and change no successful-fetch history or resumable execution checkpoint. Its outcome SHALL be marked as a plan rather than a completed refresh.

#### Scenario: Dry-run preview
- **WHEN** the maintainer requests a dry run
- **THEN** Steam targeting can occur but HLTB requests and candidate/state mutations do not occur

### Requirement: Resume preserves the original plan and observation times
The tool SHALL checkpoint a frozen target plan and completed observations so an interrupted run can resume without re-fetching completed entries. Resume SHALL require a compatible mode, mapping scope, source baseline, and state version; incompatible state SHALL block rather than silently broaden scope. Saved observations SHALL retain their actual fetch times. Failed and unattempted entries SHALL remain distinguishable from completed entries.

#### Scenario: Resume after interruption
- **WHEN** a compatible saved run contains completed entries and remaining targets
- **THEN** only remaining eligible work is requested and the saved successful observation times are unchanged

#### Scenario: Resume against a different baseline
- **WHEN** the dataset or reviewed mapping inputs no longer match the saved plan
- **THEN** resume is blocked and a new run or an explicit reviewed reconciliation is required

### Requirement: Results provide a sanitized verifiable report
The tool SHALL emit a versioned machine-readable report covering plan/execution outcome, baseline and candidate hashes when applicable, scope counts, added mappings, per-app old/new four-style values, unchanged totals, mapping gaps/conflicts, and fetch/retry failures.

#### Scenario: Report covers the actual candidate
- **WHEN** a successful observation alters a shared HLTB tuple
- **THEN** the report includes all app mappings affected by that tuple and distinguishes which apps were inside the target scope

### Requirement: Reports account for changes and distinguish coverage
Every candidate mapping or length change SHALL be accounted for. Shared-entry changes SHALL report all affected canonical app aliases, including aliases outside the requested scope, without fetching extra entries. Unavailable library details SHALL be aggregate counts. Reports SHALL distinguish changed, successfully checked unchanged, and not-refreshed entries.

#### Scenario: Partial result
- **WHEN** there are mapping gaps, unavailable libraries, failed reads, or exhausted budgets
- **THEN** these appear as explicit counts/categories alongside successful changes and the run is not reported as complete

### Requirement: Private targeting data is excluded from public artifacts
Steam credentials SHALL be accepted only through a private environment input, SHALL NOT be saved in run state, and SHALL be redacted from errors and request diagnostics. Public dataset/report output SHALL exclude account IDs, names, profile URLs, raw account responses, private paths, and user-to-game attribution.

#### Scenario: Error involving a credentialed request
- **WHEN** a Steam request fails
- **THEN** public output contains a sanitized category without the key, account ID, request query, or raw response

### Requirement: Private refresh state and its limitations are documented
Private resume and observation state SHALL be stored outside tracked artifacts or in an ignored local directory. Documentation SHALL explain unreadable-library limits, account-independent reports, the global timestamp limitation, and that publishing selected mappings reveals owned app IDs.

#### Scenario: Preparing a contribution
- **WHEN** the maintainer inspects candidate and public report artifacts
- **THEN** they contain public game identifiers/values and aggregate scope information, with no account attribution or secret values

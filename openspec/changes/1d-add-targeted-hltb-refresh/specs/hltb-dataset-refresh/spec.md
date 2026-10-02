## Purpose

Defines maintainer-invoked completion-time refreshes restricted to selected Steam libraries, with reviewed mappings, truthful observations, resumable execution, and compatible dataset/report outputs.

## ADDED Requirements

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
The tool SHALL expose missing-only, stale with a positive age threshold, and refresh-all modes. Missing-only SHALL select reviewed mappings without a length tuple and report unmapped titles. Stale SHALL select missing lengths, entries whose last validated fetch is older than the threshold, and entries with unknown or inapplicable per-entry freshness. Refresh-all SHALL select all reviewed HLTB IDs in the chosen scope. A dataset-wide timestamp SHALL NOT be treated as evidence that an individual entry was fetched successfully.

#### Scenario: Default missing-only scope
- **WHEN** missing-only runs against a library containing mapped lengths, mapped missing lengths, and unmapped titles
- **THEN** only the mapped missing lengths are fetched and the unmapped titles are listed for review

#### Scenario: Stale mode without trustworthy entry history
- **WHEN** a selected entry has no applicable successful-fetch history but the canonical dataset has a recent global timestamp
- **THEN** the entry is treated as having unknown freshness and is eligible for refresh

#### Scenario: Refresh-all remains targeted
- **WHEN** refresh-all is selected
- **THEN** only reviewed HLTB IDs reached from the selected library/app intersection are fetched

### Requirement: Mapping admission requires an explicit reviewed correspondence
The tool SHALL reuse canonical reviewed correspondences and accept explicit reviewed mapping input in the existing contribution format. Only mappings inside selected scope SHALL be admitted. An unmapped or ambiguous title SHALL be reported for human review without automatic title matching. A correspondence disagreement SHALL identify both HLTB IDs and block candidate output rather than replace the canonical mapping.

#### Scenario: Reviewed new mapping inside scope
- **WHEN** an explicit contribution supplies a valid new mapping for a selected app
- **THEN** that mapping is eligible for the candidate and no mappings outside scope are admitted

#### Scenario: Conflicting reviewed correspondence
- **WHEN** an input maps an existing app to a different HLTB ID
- **THEN** the conflict is reported with the app and both HLTB IDs and no publishable candidate is emitted

### Requirement: Fetches validate identity and have bounded request behavior
The tool SHALL fetch each selected HLTB ID once per successful run observation even when several apps share it. It SHALL validate that the response describes the requested entry and contains a recognized completion-time representation. Known values SHALL become integer minutes and unknown values SHALL remain null. Invalid identity, units, values, or response structure SHALL be reported as failures. Requests SHALL have finite concurrency, timeout, retry, and total request/wait budgets; valid rate-limit instructions SHALL be respected within those budgets, with exhaustion reported explicitly.

#### Scenario: Shared HLTB entry
- **WHEN** several selected apps map to one HLTB ID
- **THEN** one successful observation supplies their common lengths and the report identifies all affected app mappings

#### Scenario: Redirect or malformed response
- **WHEN** a response describes a different entry or contains an unrecognized payload
- **THEN** it is recorded as a failure and its values are not merged

#### Scenario: Rate-limit budget exhausted
- **WHEN** backoff would exceed the remaining run budget
- **THEN** the tool stops further requests and reports rate-limited or unattempted entries without marking them successful

### Requirement: Failed observations preserve existing values and fetch evidence
The tool SHALL retain valid existing mappings and length tuples when a fetch fails, is skipped, or yields no usable lengths. It SHALL contribute only validated observations and explicit reviewed mappings, SHALL NOT resubmit retained failed values as newly fetched observations, and SHALL NOT advance those entries' successful-fetch timestamps. Complete validated observations with some unknown styles SHALL preserve those nulls in the contribution. Partial success SHALL be distinguishable from a complete refresh.

#### Scenario: One success and one failed read
- **WHEN** one selected entry changes and another fails
- **THEN** the candidate contains the successful change, retains the failed entry's values, and the report and entry history preserve its failure and previous successful-fetch time

#### Scenario: No usable lengths
- **WHEN** a valid page supplies no known completion lengths
- **THEN** the result records no usable lengths, omits an all-null contribution tuple, and retains any existing canonical length tuple

#### Scenario: Global metadata after partial success
- **WHEN** the existing merge rules advance the candidate's dataset-wide timestamp after a successful change
- **THEN** the report explains that this is global merge metadata and does not claim that failed or untouched entries were refreshed

### Requirement: Candidate datasets obey the existing canonical contract
The tool SHALL output a candidate using only schemaVersion, datasetVersion, gatheredAt, mappings, and lengths under schema v1. It SHALL apply the existing numeric bounds, null representation, canonical ordering, merge precedence, conflict behavior, and version rules. A byte-identical merge SHALL preserve metadata. A run SHALL write a validated candidate atomically to a separate output destination and SHALL leave the canonical source unchanged. It SHALL verify that the source still matches the run baseline before finalizing output.

#### Scenario: Redundant validated observations
- **WHEN** successful observations add no mappings or changed lengths
- **THEN** candidate bytes and dataset metadata match the source even though private successful-fetch history can advance

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
The tool SHALL emit a versioned machine-readable report describing plan versus execution, complete/partial/blocked outcome, baseline and candidate hashes when applicable, scope counts, added mappings, affected per-app old/new four-style values, unchanged totals, mapping gaps/conflicts, and fetch/retry failures. Every changed mapping or length tuple in the candidate SHALL be accounted for; shared-entry changes SHALL include affected canonical app aliases outside the target set without fetching extra entries. Unavailable-library details SHALL be aggregate counts. Report consumers SHALL be able to distinguish changed values, successfully checked unchanged entries, and entries that were not refreshed.

#### Scenario: Report covers the actual candidate
- **WHEN** a successful observation alters a shared HLTB tuple
- **THEN** the report includes all app mappings affected by that tuple and distinguishes which apps were inside the target scope

#### Scenario: Partial result
- **WHEN** there are mapping gaps, unavailable libraries, failed reads, or exhausted budgets
- **THEN** these appear as explicit counts/categories alongside successful changes and the run is not reported as complete

### Requirement: Private targeting data is excluded from public artifacts
Steam credentials SHALL be accepted through a private environment input, SHALL NOT be saved in run state, and SHALL be redacted from errors and request diagnostics. Account IDs, account names, profile URLs, raw account responses, and private state locations SHALL NOT appear in dataset or public report output. Private resume/observation state SHALL be stored outside tracked artifacts or in an ignored local directory. Documentation SHALL explain unreadable-library limits, account-independent reports, the global timestamp limitation, and that publishing selected mappings reveals owned app IDs.

#### Scenario: Error involving a credentialed request
- **WHEN** a Steam request fails
- **THEN** public output contains a sanitized category without the key, account ID, request query, or raw response

#### Scenario: Preparing a contribution
- **WHEN** the maintainer inspects candidate and public report artifacts
- **THEN** they contain public game identifiers/values and aggregate scope information, with no account attribution or secret values

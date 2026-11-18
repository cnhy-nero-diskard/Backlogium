# Spec Delta

## MODIFIED Requirements

### Requirement: Refresh modes use entry evidence within selected scope
The tool SHALL provide missing-only, stale with a positive age threshold, and refresh-all modes scoped to selected libraries. An optional app-ID restriction SHALL intersect, never expand, that scope. Existing mappings SHALL retain their mode selection rules. Newly admitted reviewed mappings SHALL schedule a fresh direct-page observation even when their HLTB ID already has canonical lengths. Unmapped titles SHALL remain reported.

#### Scenario: Default missing-only scope
- **WHEN** missing-only runs against a library containing mapped lengths, mapped missing lengths, and unmapped titles without new reviewed mappings
- **THEN** only the mapped missing lengths are fetched and the unmapped titles are listed for review

#### Scenario: Refresh-all remains targeted
- **WHEN** refresh-all is selected
- **THEN** only reviewed HLTB IDs reached from the selected library/app intersection are fetched

#### Scenario: New correspondence shares an existing length entry
- **WHEN** an approved new app mapping points to an HLTB ID already carrying lengths, including in missing-only or stale mode
- **THEN** that HLTB ID is eligible for a fresh direct-page observation and shared aliases do not multiply the observation

### Requirement: Mapping admission requires an explicit reviewed correspondence
The tool SHALL reuse canonical reviewed correspondences and accept explicit reviewed mapping input in the existing contribution format or an approved candidate-review artifact. Only mappings inside selected scope SHALL be admitted. An unmapped or ambiguous title SHALL remain unmapped until its exact pair is explicitly approved. Search scores SHALL NOT grant approval. A correspondence disagreement SHALL identify both HLTB IDs and block candidate output rather than replace the canonical mapping.

#### Scenario: Reviewed new mapping inside scope
- **WHEN** an explicit contribution supplies a valid new mapping for a selected app
- **THEN** that mapping is eligible for the candidate and no mappings outside scope are admitted

#### Scenario: Conflicting reviewed correspondence
- **WHEN** an input maps an existing app to a different HLTB ID
- **THEN** the conflict is reported with the app and both HLTB IDs and no publishable candidate is emitted

#### Scenario: Review artifact contains mixed decisions
- **WHEN** a valid review artifact contains approved, pending, and rejected candidates
- **THEN** only approved pairs inside selected scope are admitted, and pending or rejected pairs do not authorize mapping or fetching

#### Scenario: Conflicting approval outside current scope
- **WHEN** an approved pair disagrees with a canonical mapping even outside the selected app intersection
- **THEN** candidate output is blocked and both public correspondences are reported

### Requirement: Resume preserves the original plan and observation times
The tool SHALL checkpoint a frozen target plan and completed observations so an interrupted run can resume without re-fetching completed entries. Resume SHALL require a compatible mode, mapping scope, source baseline, review-input content, and state version; incompatible state SHALL block rather than silently broaden scope. Saved observations SHALL retain their actual fetch times. Failed and unattempted entries SHALL remain distinguishable from completed entries.

#### Scenario: Resume after interruption
- **WHEN** a compatible saved run contains completed entries and remaining targets
- **THEN** only remaining eligible work is requested and the saved successful observation times are unchanged

#### Scenario: Resume against a different baseline
- **WHEN** the dataset or reviewed mapping inputs no longer match the saved plan
- **THEN** resume is blocked and a new run or an explicit reviewed reconciliation is required

#### Scenario: Approval changes during a paused run
- **WHEN** a candidate-review artifact changes after its plan was frozen, including withdrawal of an approval
- **THEN** resume blocks before network requests or candidate replacement rather than using the former approval

## ADDED Requirements

### Requirement: Search previews never supply final completion evidence
The refresh SHALL ignore review preview lengths as observation data and SHALL use the existing identity-checked direct-page validation path for final values. Failed, empty, or unattempted reads SHALL preserve baseline tuples and successful-fetch evidence. An explicitly approved new correspondence can remain mapping-only without usable new lengths; its fetch outcome SHALL remain visible.

#### Scenario: Preview and final lengths differ
- **WHEN** an approved suggestion previews one set of lengths and the validated direct page returns another
- **THEN** only the direct-page observation is submitted to the canonical merge and successful-fetch history

#### Scenario: Approved entry has no usable lengths
- **WHEN** the approved direct page contains no usable lengths
- **THEN** the approval can add its correspondence, no preview or all-null tuple is submitted as fresh data, existing tuples are retained, and no-lengths is reported

#### Scenario: Approved read fails or is never attempted
- **WHEN** a new approved entry fails validation or cannot be attempted within the budget
- **THEN** any approved correspondence addition is distinguished from fetch success, baseline lengths remain unchanged, and the report records failure or unattempted status respectively

### Requirement: Review inputs are validated and cannot expand ownership scope
Review consumption SHALL validate artifact version, baseline identity, unique app/candidate identities, decision values, and at most one approval per app before fetching. Admitted apps SHALL belong to both artifact scope and current selected-library scope. Invalid input or changed baseline/review bytes SHALL block final output. Supplying both review-artifact and legacy reviewed-mapping options SHALL fail rather than imply precedence.

#### Scenario: Library or app restriction narrows
- **WHEN** a valid artifact approves a game absent from the current owned-library/app intersection
- **THEN** its mapping is excluded and no request is authorized by that pair, with the exclusion reported separately

#### Scenario: Baseline moved after discovery
- **WHEN** the canonical baseline hash differs from the review artifact's recorded baseline
- **THEN** consumption blocks and requires fresh discovery or explicitly reviewed reconciliation rather than silently remapping

#### Scenario: Two input mechanisms supplied
- **WHEN** both a legacy reviewed contribution and a candidate-review artifact are supplied
- **THEN** option validation fails before requests or output mutation

#### Scenario: Review changes during active fetching
- **WHEN** the review file changes after the approved plan was frozen but before final output
- **THEN** candidate finalization blocks while retaining completed observations without changing their actual fetch times

### Requirement: Review decisions and fetch outcomes have separate accounting
Refresh reports consuming review artifacts SHALL separately account for candidates, approvals, rejections, pending decisions, scope exclusions, and remaining unmapped apps. No-lengths, failures, and unattempted observations SHALL remain separate unique-HLTB categories. Shared observations SHALL identify all affected app aliases without admitting unapproved mappings or expanding fetch scope.

#### Scenario: Shared ID has approved and rejected suggestions
- **WHEN** approved apps share one HLTB ID and another app's suggestion of that ID is rejected
- **THEN** the ID is observed once for the approved targets, rejected correspondence is not added, and the report preserves both review decisions and all actually affected canonical app aliases

#### Scenario: Partial candidate preserves the full baseline
- **WHEN** approved observations produce additions alongside failures, rejections, or unresolved mapping gaps
- **THEN** the candidate contains every original mapping and length row plus admissible additions and validated updates, the report accounts for the actual diff and outstanding gaps, and dataset.json remains untouched

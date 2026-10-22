## ADDED Requirements

### Requirement: Canonical recovery projections expose coherent committed activity
Every canonical credited-minute or dated-activity result SHALL represent a coherent committed state of its sessions, funded contributions, active placement revisions and allocation recipes for the admitted account. Valid concurrent recovery commits, placement replacements and recipe materialization SHALL NOT expose duplicate quantity, lose credited activity, or cause a projection to fail solely because related inputs were observed at different revisions. Each emitted result SHALL satisfy the existing conservation and mixed-legacy-remainder rules; eventual convergence after an inconsistent emission is insufficient. These guarantees SHALL apply to continuously observed and one-time reads while retaining bounded date-window behavior. Genuinely inconsistent persisted graphs SHALL remain rejected rather than silently clamped or reclassified.

#### Scenario: A funded session changes while totals are observed
- **WHEN** a consumer continuously observes credited totals while a valid transaction extends a session and commits its corresponding funded contribution and placement
- **THEN** each result describes a coherent committed quantity without counting the new funding alongside an obsolete unlinked remainder or rejecting the valid update as overfunded

#### Scenario: Evidence replaces an estimate during dated reads
- **WHEN** validated timing replaces an existing estimate while History or Analytics observes the affected dates
- **THEN** each result uses coherent active placement revisions, preserves the authorized total, and does not combine superseded allocation credit with its replacement

#### Scenario: A recipe is materialized during observation
- **WHEN** a residual allocation recipe advances its materialized prefix while a consumer observes its date window
- **THEN** materialized shares and the remaining recipe tail are combined exactly once in every result, including positive and zero-share boundary dates

#### Scenario: Account reset retargets a running observation
- **WHEN** an account reset or change occurs while canonical recovery activity is being observed
- **THEN** each result belongs to a coherent admitted account state and contains no combination of the previous account's funding with the new account's sessions or placements

#### Scenario: Mixed legacy remainder retains its own date
- **WHEN** a session containing both legacy remainder and funded shares is observed across a placement revision or device timezone change
- **THEN** each result retains the funded shares' frozen attribution dates and accounts for the unlinked remainder once under its existing date policy

#### Scenario: Persisted corruption is distinguishable from a valid update
- **WHEN** a coherent stored graph actually funds more minutes than its linked session holds
- **THEN** the invalid mapping is rejected and is not hidden by suppressing shares, truncating quantity or treating it as a transient update

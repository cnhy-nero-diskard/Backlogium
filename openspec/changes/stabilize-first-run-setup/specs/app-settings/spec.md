## MODIFIED Requirements

### Requirement: Run setup section
The Settings screen SHALL present an entry that opens first-run setup, showing each registered stage and its latest reconciled operation state, and letting the user select and run any available stage. Stages SHALL default to unselected for a new deliberate run. Pending work, terminal outcomes, and editable next-run selections SHALL remain distinct; Settings SHALL apply the same non-destructive per-stage recovery semantics as onboarding.

#### Scenario: Opening setup from Settings
- **WHEN** the user activates the setup entry
- **THEN** the staged checklist is presented, listing every registered stage

#### Scenario: Last outcome shown per stage
- **WHEN** the checklist is presented from Settings
- **THEN** each stage shows its latest reconciled state, distinguishing never run, pending work, succeeded, failed, cancelled, and skipped

#### Scenario: Nothing selected by default
- **WHEN** the user opens setup for a new deliberate run
- **THEN** no stage is selected until the user selects one

#### Scenario: Running selected stages
- **WHEN** the user selects one or more stages and starts them
- **THEN** those stages are requested, only their attempt records are replaced, and unrelated recorded results remain intact

#### Scenario: Setup never run
- **WHEN** setup has never been run
- **THEN** the entry is still present and every stage shows as never run

#### Scenario: Credentials not configured
- **WHEN** no credentials are configured
- **THEN** the checklist explains that Steam must be connected first and does not admit stage work

#### Scenario: Edit selections after settlement
- **WHEN** a foreground attempt settles and the user edits the next selection
- **THEN** the visible selection updates immediately and matches the next request

#### Scenario: Work continues after onboarding
- **WHEN** the user left onboarding with a stage queued or scheduled for retry and later opens setup from Settings
- **THEN** the existing operation is reconciled rather than mislabelled failed or duplicated

### Requirement: Data section
The Settings screen SHALL present the historical-playtime import and its reset as data controls in Data & privacy, separately from the rule-configuration controls. It SHALL use the same baseline eligibility, explicit consent, one-time operation, and interruption recovery as onboarding. Missing baseline readiness SHALL have an actionable library-sync explanation rather than consume the import. Existing reset behavior, including safeguards for an applied cloud imported-play transfer, SHALL remain unchanged.

#### Scenario: Import presented in Settings
- **WHEN** the Settings screen is shown
- **THEN** the Steam history import control is presented in Data & privacy, retaining its confirmation and one-time behavior

#### Scenario: Import not presented on Home
- **WHEN** the Home screen is shown
- **THEN** it does not present the Steam history import or its reset

#### Scenario: History was skipped during onboarding
- **WHEN** the user deferred the first-run history choice
- **THEN** the existing Settings import remains available subject to baseline readiness without reopening onboarding

#### Scenario: Baseline needed
- **WHEN** history is not imported and the active account lacks a confirmed baseline
- **THEN** Settings explains that library sync is needed and does not submit a premature import

#### Scenario: Raw import awaits recomputation
- **WHEN** the imported offsets are committed but derived recomputation remains unfinished
- **THEN** Settings shows recovery pending and offers the shared recovery path rather than displaying an untouched or fully completed import

#### Scenario: Existing reset safeguards
- **WHEN** the user requests import reset while an imported-play cloud transfer remains applied
- **THEN** the existing reversal-before-reset safeguard remains enforced

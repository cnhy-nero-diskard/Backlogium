## MODIFIED Requirements

### Requirement: Run setup section
Settings SHALL open the registered setup checklist, with each stage's latest reconciled state and support for deliberate reruns. A fresh rerun selection SHALL start empty.

#### Scenario: Opening setup from Settings
- **WHEN** the user activates the setup entry
- **THEN** the staged checklist is presented, listing every registered stage

#### Scenario: Last outcome shown per stage
- **WHEN** the checklist is presented from Settings
- **THEN** each stage shows its latest state, including pending work, cancellation, or recovery required

#### Scenario: Nothing selected by default
- **WHEN** the checklist is presented from Settings
- **THEN** no stage is selected until the user selects one

#### Scenario: Running selected stages
- **WHEN** the user selects one or more stages and starts them
- **THEN** those stages run and their outcomes replace the previously recorded ones

#### Scenario: Setup never run
- **WHEN** setup has never been run
- **THEN** the entry is still present and every stage shows as never run

#### Scenario: Credentials not configured
- **WHEN** no credentials are configured
- **THEN** the entry explains that credentials are required rather than starting stages that cannot
  succeed

#### Scenario: Return while work remains pending
- **WHEN** the user opens setup in Settings after continuing from onboarding with pending stages
- **THEN** Settings shows each pending stage's latest observable state and any actions available for that stage

### Requirement: Data section
Settings SHALL present history import and reset separately from rule configuration. Its import control SHALL reflect the shared operation's baseline readiness, pending work, and completion state.

#### Scenario: Import presented in Settings
- **WHEN** the Settings screen is shown
- **THEN** the Steam history import control is presented there, retaining the confirmation and
  one-time behavior already specified for that control

#### Scenario: Import not presented on Home
- **WHEN** the Home screen is shown
- **THEN** it does not present the Steam history import or its reset

#### Scenario: Confirmed baseline needed
- **WHEN** the user opens Settings without a confirmed baseline for an unimported account
- **THEN** Settings explains the prerequisite and offers access to library-sync recovery

#### Scenario: Import or recomputation pending
- **WHEN** an admitted request is pending or a committed import still needs recomputation
- **THEN** Settings shows the pending state and available recovery without offering a second crediting import

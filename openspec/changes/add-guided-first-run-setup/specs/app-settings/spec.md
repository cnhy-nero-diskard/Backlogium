## ADDED Requirements

### Requirement: Run setup section
The Settings screen SHALL present an entry that opens first-run setup, showing each registered
stage, its last recorded outcome, and letting the user select and run any of them. Stages SHALL
default to unselected, so a re-run is deliberate.

#### Scenario: Opening setup from Settings
- **WHEN** the user activates the setup entry
- **THEN** the staged checklist is presented, listing every registered stage

#### Scenario: Last outcome shown per stage
- **WHEN** the checklist is presented from Settings
- **THEN** each stage shows whether it last succeeded, failed, was skipped, or has never run

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

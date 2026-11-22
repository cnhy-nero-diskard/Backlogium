# App Settings Delta

## ADDED Requirements

### Requirement: Sync cards show their own progress and results
Settings SHALL render Steam sync and achievement refresh progress in their respective operation rows or cards. In-flight and terminal summaries SHALL refer to the associated operation and provide relevant existing recovery actions.

#### Scenario: Steam and achievements overlap
- **WHEN** one Steam poll and one eligible achievement refresh are pending
- **THEN** each card shows its own phase and outcomes and only applicable actions

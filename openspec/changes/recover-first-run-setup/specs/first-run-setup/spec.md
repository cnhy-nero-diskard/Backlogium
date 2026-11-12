# Spec Delta

## ADDED Requirements

### Requirement: Ongoing work is not a failed stage
Setup SHALL distinguish waiting, running, and retry backoff from terminal succeeded, failed, and skipped outcomes. Waiting for scheduling, constraints, or another operation, and an automatic retry pending after a transient error, SHALL NOT alone fail a stage. An actual failure SHALL identify the affected stage and its reason.

#### Scenario: Work waits before its first attempt
- **WHEN** a selected stage's work is queued or blocked before execution
- **THEN** the stage is shown as waiting, not failed or completed

#### Scenario: Transient error enters backoff
- **WHEN** a stage's work schedules another attempt after a transient error
- **THEN** setup shows retry backoff and explains that another attempt is pending
- **AND** the stage remains outstanding and may subsequently become running or completed

#### Scenario: Existing work is reused
- **WHEN** setup attaches to work already admitted by the underlying operation
- **THEN** setup observes that work's current state without replacing it or reporting the overlap as a failure

#### Scenario: Live monitoring overlaps setup
- **WHEN** setup runs while live monitoring and a periodic or manual sync are active
- **THEN** overlap alone does not fail setup, stop monitoring, or duplicate credited playtime
- **AND** each stage reports the outcome of the operation it actually observes

#### Scenario: Actual terminal failure
- **WHEN** observed work fails or is cancelled before completion
- **THEN** setup reports the affected stage's failure and reason, and the remaining selected stages can still run

### Requirement: Onboarding recovery preserves successful work
After a setup attempt settles, onboarding SHALL allow retrying a failed stage and editing the selection for another attempt. Recovery SHALL preserve unrelated outcomes and SHALL NOT rerun successful stages automatically. Duplicate requests during an active attempt SHALL NOT queue an additional setup run. Explicit stage reruns from Settings SHALL remain available.

#### Scenario: Retry one failed onboarding stage
- **WHEN** a user retries a failed stage after the setup attempt settles
- **THEN** only that stage is requested again and its outcome is updated
- **AND** other stages' successful outcomes and data are retained

#### Scenario: Edit selections after a settled attempt
- **WHEN** a user returns to selection editing after a setup attempt settles
- **THEN** successful stages are not selected automatically and the user can choose failed, skipped, or never-run stages
- **AND** starting the revised selection does not relabel unrelated successful stages as skipped

#### Scenario: Repeated start or retry taps
- **WHEN** another start or retry request arrives while a setup attempt is active
- **THEN** the active attempt remains authoritative and no second setup run is queued behind it

### Requirement: Leaving setup preserves durable recovery
Setup SHALL offer a clear exit or continue action before and during execution, including waiting and retry backoff. Leaving an active run SHALL NOT cancel its work or relabel it skipped. After process death, outstanding work SHALL be reconciled by its recorded identity before any replacement is requested, and successful outcomes SHALL remain intact.

#### Scenario: Continue during backoff
- **WHEN** the user continues while a stage is waiting for an automatic retry
- **THEN** onboarding can advance without waiting for that retry
- **AND** the work remains queued and its outstanding state remains available from Settings

#### Scenario: Cold start during outstanding work
- **WHEN** the process ends with a stage waiting, running, or in retry backoff
- **THEN** the next observation recovers the exact outstanding work rather than enqueuing a duplicate
- **AND** its eventual terminal outcome is reflected in setup

#### Scenario: Detached completion times survive interruption
- **WHEN** the user leaves setup and the process ends during the completion-times stage
- **THEN** its durable work and outcome remain recoverable without rerunning successful earlier stages

#### Scenario: Completed or declined onboarding stays dismissed
- **WHEN** the user has explicitly completed or declined the first-run flow and later starts the app
- **THEN** pending background setup work does not reopen the onboarding takeover

#### Scenario: Missing durable work
- **WHEN** recovery cannot find the recorded outstanding work
- **THEN** it reconciles the missing attempt through the underlying operation's idempotent start path or exposes an actionable stage-specific failure
- **AND** it neither waits forever on a missing identity nor reports completion without evidence

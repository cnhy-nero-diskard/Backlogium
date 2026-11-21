## MODIFIED Requirements

### Requirement: Ongoing work is not a failed stage
Setup SHALL show waiting, running, and retry backoff separately from terminal succeeded, failed, cancelled, and skipped outcomes. Scheduler activity alone SHALL NOT establish failure or success. Actual failures and cancellations SHALL identify the affected stage and its reason.

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
- **THEN** setup identifies the affected stage, its failure or cancellation, and the reason, and the remaining selected stages can still run

### Requirement: Leaving setup preserves durable recovery
Setup SHALL offer Continue before and during execution without cancelling or relabelling admitted work. Independent selected stages SHALL be admitted in registry order as observation settles. Pending work SHALL remain observable by exact identity after Continue and restart. Recovery SHALL preserve recorded outcomes and offer explicit retry when admission cannot be established.

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
- **THEN** it explains that the stage needs a new request and offers explicit recovery without assuming completion or automatically triggering replacement work

#### Scenario: An earlier stage remains pending
- **WHEN** one stage is queued, in retry backoff, detached after durable admission, or still running at the end of the foreground wait
- **THEN** setup proceeds with later independent selected stages in registry order while retaining the earlier stage's pending state

#### Scenario: User continues while admission is in progress
- **WHEN** the user chooses Continue during an active attempt
- **THEN** the surface advances, admitted work remains active, and any remaining selected stages are still admitted in order

#### Scenario: Several stages survive a restart
- **WHEN** the process ends with several admitted stages pending
- **THEN** each stage reconciles against its own work and remaining selected stages continue admission without duplicating work

#### Scenario: Crash between admission and association
- **WHEN** a saved request may have reached the scheduler before its exact work association was persisted
- **THEN** recovery resolves that request by attributable evidence or offers explicit recovery if admission cannot be established

#### Scenario: An older attempt reports late
- **WHEN** an older attempt or a previous account reports progress or completion
- **THEN** its result cannot change the replacement attempt or the active account's stage state

#### Scenario: Legacy setup records upgrade
- **WHEN** an upgrade finds historical outcomes or a legacy active work ID
- **THEN** known outcomes remain visible and any attributable active work is reconciled by identity

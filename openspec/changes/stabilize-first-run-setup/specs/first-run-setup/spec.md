## MODIFIED Requirements

### Requirement: Setup is an ordered registry of named stages
The system SHALL model first-run setup as an ordered list of named stages rather than as a fixed sequence of steps. Each stage SHALL declare a stable identifier, a title, a description of what it will do, whether it is selected by default, and whether it runs while the setup surface is shown or detached from it. Every surface that presents setup SHALL derive its contents from the registered stages, so that registering a further stage requires no change to those surfaces. Selected stages SHALL be admitted in registered order; independently admitted work MAY overlap after an earlier foreground observation has settled without a terminal work outcome.

#### Scenario: Stages presented in registered order
- **WHEN** setup is presented
- **THEN** the registered stages are listed in their registered order, each with its title and description

#### Scenario: A stage is added
- **WHEN** a further stage is registered
- **THEN** it appears in the checklist, in the admission order, in the progress reporting, and in the completion summary, without those surfaces being changed

#### Scenario: Identifiers are stable
- **WHEN** a stage's stored opt-in or outcome is read
- **THEN** it is keyed by that stage's identifier, and an unrecognized identifier is ignored rather than failing to render setup

#### Scenario: A stage whose prerequisite is absent
- **WHEN** a registered stage cannot run because the capability it depends on is not present in the build
- **THEN** it is presented as unavailable with the reason, cannot be selected, and does not prevent the other stages from running

#### Scenario: An earlier stage remains pending
- **WHEN** a stage is admitted but its foreground observation settles while the work remains queued, running, or scheduled for retry
- **THEN** later independent selected stages are admitted in registered order without relabelling the earlier work completed or failed

### Requirement: Later stages continue without the setup surface
Stages declared as detached SHALL continue after the user leaves the setup surface, reporting their own progress in their own ongoing notification. The user SHALL be able to continue onboarding once every selected non-detached stage's foreground observation has settled, even if some underlying work remains pending. An explicit continue/do-later path SHALL also remain available while foreground observation is active. Leaving the surface SHALL NOT cancel admitted work or erase its recorded results.

#### Scenario: Entering the app while detached stages run
- **WHEN** every non-detached stage's foreground observation has settled and detached stages are still running
- **THEN** the user can continue toward the app, including the optional onboarding history decision, and those stages continue

#### Scenario: Detached stage reports its own progress
- **WHEN** a detached stage runs
- **THEN** it presents its own progress in its own notification, separate from any other stage's

#### Scenario: Detached stage survives process death
- **WHEN** the app's process ends while a detached stage is running
- **THEN** the stage continues and its progress remains observable

#### Scenario: Notification permission not granted
- **WHEN** the notification permission has not been granted
- **THEN** detached stages still run and their progress remains observable in the app, without the absent permission being treated as a failure

#### Scenario: Permission requested before detaching
- **WHEN** setup is about to start a detached stage and the notification permission has not been requested
- **THEN** it is requested, and setup proceeds whichever way the user answers

#### Scenario: Continue during an offline wait
- **WHEN** a selected in-screen operation is waiting for connectivity
- **THEN** the user can continue/do later without waiting for connectivity and without cancelling the queued operation

### Requirement: A failing stage does not affect the others
Each stage SHALL reach its own terminal outcome independently, distinguishing succeeded, failed, cancelled, and skipped from pending work. A failed or cancelled stage SHALL NOT cancel, skip, or discard the results of any other stage, and SHALL NOT cause setup as a whole to fail. Foreground settlement SHALL NOT imply that all underlying work has reached a terminal outcome.

#### Scenario: One stage fails
- **WHEN** a stage fails
- **THEN** the remaining independent selected stages still run

#### Scenario: Results of other stages preserved
- **WHEN** a stage fails while another has already succeeded
- **THEN** the succeeded stage's results are retained

#### Scenario: Setup completes with a failure
- **WHEN** every selected stage has reached a terminal outcome and at least one failed
- **THEN** setup reports which stages succeeded and which failed without reporting a global setup failure

#### Scenario: Failure is attributable
- **WHEN** a stage fails
- **THEN** the reported outcome identifies which stage failed and why, rather than reporting that setup failed

#### Scenario: Retry backoff is not terminal failure
- **WHEN** an operation schedules another attempt after a transient failure
- **THEN** its stage is reported as retry scheduled, not terminally failed, and independent stages remain eligible to run

#### Scenario: A stage is cancelled explicitly
- **WHEN** the user cancels underlying stage work through its existing cancellation control
- **THEN** that stage is reported as cancelled and sibling work and results are preserved

### Requirement: A stage can be retried
The system SHALL let the user recover individual failed, cancelled, or not-admitted stages from onboarding and Settings after the current foreground attempt settles. Retrying a stage SHALL use its existing underlying operation and its concurrency policy, relying on that operation's resumption and idempotence. Successful stages SHALL NOT be restarted by a failed-stage retry or relabelled skipped by a subsequent subset run. Re-running a succeeded stage SHALL require a deliberate run-again action or explicit selection.

#### Scenario: Retrying a failed stage
- **WHEN** the user retries a terminally failed stage from onboarding or Settings
- **THEN** its underlying operation is requested again and only that stage's attempt and recorded result are replaced

#### Scenario: Retrying a succeeded stage
- **WHEN** the user deliberately re-runs a succeeded stage
- **THEN** its underlying work is requested again, behaving exactly as triggering that work directly would

#### Scenario: Retry does not duplicate running work
- **WHEN** a stage is requested again while its underlying work is already running
- **THEN** the existing work's concurrency policy is respected, existing admitted work continues, and no duplicate is enqueued for that stage

#### Scenario: Work is already scheduled for retry
- **WHEN** the user requests a stage whose existing operation is queued in backoff
- **THEN** setup reflects the existing scheduled retry and does not promise immediate execution or replace that work solely to bypass backoff

#### Scenario: Subset retry preserves successful siblings
- **WHEN** the user retries one stage after a run in which another stage succeeded
- **THEN** the successful sibling's data and recorded result remain unchanged

### Requirement: Setup can be run again later
The system SHALL let the user run setup again after onboarding, presenting the same stages with each stage's latest reconciled state and no stage selected for a new run by default. After a foreground attempt settles, pending selections SHALL be editable and SHALL exactly match the selection submitted by the next start action. While a foreground attempt is active, its selection SHALL remain immutable. Setup completion SHALL be informational and SHALL NOT gate access to any part of the app.

#### Scenario: Running setup after declining it
- **WHEN** the user declined setup during onboarding and later runs it
- **THEN** the same checklist is presented and the deliberately selected stages run

#### Scenario: Last outcome shown
- **WHEN** setup is presented again
- **THEN** each stage shows its latest reconciled state, including admitted work that remains pending

#### Scenario: Re-run defaults to nothing selected
- **WHEN** setup is presented for a new deliberate run
- **THEN** no stage is selected by default

#### Scenario: Completion gates nothing
- **WHEN** setup has never been run, or was run and every stage was skipped
- **THEN** every part of the app remains fully usable

#### Scenario: A stage added after a completed setup
- **WHEN** a stage is registered after the user completed setup
- **THEN** that stage has no recorded outcome and is presented as never run, without setup being presented again unprompted

#### Scenario: Editing after a completed attempt
- **WHEN** the user changes the next-run selection after an attempt settles
- **THEN** the visible checkboxes update to those edits and the next start action submits exactly the visible selection

## ADDED Requirements

### Requirement: Setup distinguishes scheduler state from operation outcome
Setup SHALL distinguish never run, queued or waiting, running, retry scheduled, succeeded, failed, cancelled, and skipped. It SHALL show a waiting reason when known and determinate progress only when the underlying operation provides a usable total. Work being busy, constrained, or in retry backoff SHALL NOT by itself produce failure. Scheduler completion without the requested domain effect SHALL NOT be labelled succeeded. A summary with pending work SHALL identify that work as pending rather than claim all operations are complete.

#### Scenario: Existing work is reused
- **WHEN** a selected stage attaches to work admitted by another control
- **THEN** setup reports the actual reused work state instead of failing because another operation is active

#### Scenario: A job is waiting on constraints
- **WHEN** admitted work cannot run until network or storage constraints are met
- **THEN** the stage is shown as waiting, without a fabricated progress percentage or terminal failure

#### Scenario: Scheduler completes without a library commit
- **WHEN** a library poll finishes without confirming and committing a readable owned-library response
- **THEN** setup shows the attributable unsuccessful or skipped operation reason, not a completed library-sync badge

#### Scenario: No artwork is currently available
- **WHEN** an explicitly requested artwork operation completes with zero eligible inventory items while library sync has not populated that inventory
- **THEN** setup reports the zero-available-items result and explains that artwork can be requested again after sync, without claiming a populated library was downloaded or treating the missing inventory as a sibling failure

### Requirement: Admitted stages remain durably attributable and recoverable
The system SHALL retain the identity and ownership of each stage's latest admitted operation separately from the user's foreground journey. It SHALL reconcile pending stages with those exact operations after surface recreation, process restart, or re-entry from Settings, including when the user previously chose to continue. A result from an older stage attempt or another account SHALL NOT overwrite the latest attempt. If a pending operation can no longer be located, the system SHALL explain that recovery needs a new request rather than claim success or wait indefinitely.

#### Scenario: Retry succeeds after setup moves on
- **WHEN** a stage's scheduled retry succeeds after its foreground observation has settled
- **THEN** its reconciled state becomes succeeded and unrelated stage results remain unchanged

#### Scenario: Process dies with more than one admitted stage
- **WHEN** the process ends after multiple stages were admitted and their work is still pending
- **THEN** recovery reconciles each stage with its own admitted operation without enqueueing duplicate work

#### Scenario: Process dies between admission and association
- **WHEN** a stage's request intent is durable and its work may have been admitted, but the process ends before the exact admitted-operation association is recorded
- **THEN** recovery reconciles the recorded request identity before any new request, does not attach an arbitrary historical operation or enqueue duplicate work, and offers explicit recovery if admission cannot be established

#### Scenario: User continued before process death
- **WHEN** the user continues into the app, the process ends, and setup is later opened from Settings
- **THEN** pending stage state is reconciled without forcing onboarding to reopen

#### Scenario: Superseded result arrives late
- **WHEN** an older stage attempt reports progress or completion after a newer attempt owns the stage
- **THEN** that report cannot change the newer attempt's state

#### Scenario: A pending job is unavailable
- **WHEN** a saved pending operation is no longer available for reconciliation
- **THEN** the stage offers an explicit recovery request with an explanation and is not marked completed

#### Scenario: Legacy records are retained
- **WHEN** the app upgrades with historical stage outcomes but no per-stage operation association
- **THEN** those historical outcomes remain visible without fabricating a live operation or automatically rerunning the stages

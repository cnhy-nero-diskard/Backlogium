## ADDED Requirements

### Requirement: Setup is an ordered registry of named stages
The system SHALL model first-run setup as an ordered list of named stages rather than as a fixed
sequence of steps. Each stage SHALL declare a stable identifier, a title, a description of what it
will do, whether it is selected by default, and whether it runs while the setup surface is shown or
detached from it. Every surface that presents setup SHALL derive its contents from the registered
stages, so that registering a further stage requires no change to those surfaces.

#### Scenario: Stages presented in registered order
- **WHEN** setup is presented
- **THEN** the registered stages are listed in their registered order, each with its title and
  description

#### Scenario: A stage is added
- **WHEN** a further stage is registered
- **THEN** it appears in the checklist, in the running order, in the progress reporting, and in the
  completion summary, without those surfaces being changed

#### Scenario: Identifiers are stable
- **WHEN** a stage's stored opt-in or outcome is read
- **THEN** it is keyed by that stage's identifier, and an unrecognized identifier is ignored rather
  than failing to render setup

#### Scenario: A stage whose prerequisite is absent
- **WHEN** a registered stage cannot run because the capability it depends on is not present in the
  build
- **THEN** it is presented as unavailable with the reason, cannot be selected, and does not prevent
  the other stages from running

### Requirement: Each stage is independently opted into
The system SHALL let the user select each stage independently before setup starts, SHALL apply each
stage's declared default selection, and SHALL run only selected stages. The user SHALL be able to
decline setup entirely.

#### Scenario: Defaults applied
- **WHEN** the setup checklist is first presented
- **THEN** each stage is selected or deselected according to its declared default

#### Scenario: Deselecting a stage
- **WHEN** the user deselects a stage and starts setup
- **THEN** that stage does not run and is recorded as skipped

#### Scenario: Selecting an unselected stage
- **WHEN** the user selects a stage that was not selected by default and starts setup
- **THEN** that stage runs in its registered position

#### Scenario: Declining setup
- **WHEN** the user declines setup
- **THEN** no stage runs, all stages are recorded as skipped, and the user proceeds into the app

#### Scenario: Selecting nothing
- **WHEN** the user starts setup with no stage selected
- **THEN** setup completes immediately with every stage recorded as skipped

### Requirement: Setup reports progress for the stage that is running
The system SHALL show, while setup runs, which stage is running, its progress, and the outcome of
each stage that has already finished. Progress SHALL be determinate wherever the underlying work
reports a total.

#### Scenario: Stage in progress
- **WHEN** a stage is running
- **THEN** setup identifies it by title and presents its progress

#### Scenario: Determinate progress
- **WHEN** a running stage's underlying work reports how many items it has processed out of a total
- **THEN** setup presents that as determinate progress

#### Scenario: Indeterminate progress
- **WHEN** a running stage's underlying work reports no total
- **THEN** setup presents indeterminate progress rather than a misleading figure

#### Scenario: Finished stages remain visible
- **WHEN** a stage finishes and the next begins
- **THEN** the finished stage's outcome remains visible

#### Scenario: Progress survives recreation
- **WHEN** the setup surface is recreated while a stage is running
- **THEN** it reflects the current stage and its current progress rather than restarting

### Requirement: Later stages continue without the setup surface
Stages declared as detached SHALL continue after the user leaves the setup surface, reporting their
own progress in their own ongoing notification. The user SHALL be able to enter the app once every
non-detached stage has finished.

#### Scenario: Entering the app while detached stages run
- **WHEN** every non-detached stage has finished and detached stages are still running
- **THEN** the user can enter the app, and those stages continue

#### Scenario: Detached stage reports its own progress
- **WHEN** a detached stage runs
- **THEN** it presents its own progress in its own notification, separate from any other stage's

#### Scenario: Detached stage survives process death
- **WHEN** the app's process ends while a detached stage is running
- **THEN** the stage continues and its progress remains observable

#### Scenario: Notification permission not granted
- **WHEN** the notification permission has not been granted
- **THEN** detached stages still run and their progress remains observable in the app, without the
  absent permission being treated as a failure

#### Scenario: Permission requested before detaching
- **WHEN** setup is about to start a detached stage and the notification permission has not been
  requested
- **THEN** it is requested, and setup proceeds whichever way the user answers

### Requirement: A failing stage does not affect the others
Each stage SHALL reach its own terminal outcome — succeeded, failed, or skipped — independently. A
failed stage SHALL NOT cancel, skip, or discard the results of any other stage, and SHALL NOT cause
setup as a whole to fail.

#### Scenario: One stage fails
- **WHEN** a stage fails
- **THEN** the remaining selected stages still run

#### Scenario: Results of other stages preserved
- **WHEN** a stage fails while another has already succeeded
- **THEN** the succeeded stage's results are retained

#### Scenario: Setup completes with a failure
- **WHEN** every selected stage has reached a terminal outcome and at least one failed
- **THEN** setup completes, reporting which stages succeeded and which failed

#### Scenario: Failure is attributable
- **WHEN** a stage fails
- **THEN** the reported outcome identifies which stage failed and why, rather than reporting that
  setup failed

### Requirement: A stage can be retried
The system SHALL let the user re-run any stage after setup has completed. Re-running a stage SHALL
re-run its underlying work rather than resuming a partial attempt, relying on that work's own
resumption and idempotence.

#### Scenario: Retrying a failed stage
- **WHEN** the user re-runs a stage that failed
- **THEN** its underlying work is started again and its recorded outcome is replaced by the new one

#### Scenario: Retrying a succeeded stage
- **WHEN** the user re-runs a stage that succeeded
- **THEN** its underlying work is started again, behaving exactly as triggering that work directly
  would

#### Scenario: Retry does not duplicate running work
- **WHEN** a stage is re-run while its underlying work is already running
- **THEN** the existing work continues and no duplicate is enqueued

### Requirement: Setup schedules existing work and derives nothing
A stage SHALL start the work the app already performs for that purpose and observe its progress. No
stage SHALL fetch, persist, or derive anything of its own, and running a stage SHALL be
indistinguishable in its effects from triggering that work by its existing control.

#### Scenario: A stage's effects match its existing control
- **WHEN** a stage runs
- **THEN** the records it produces are identical to those produced by triggering the same work from
  its existing control

#### Scenario: The initial sync is the baseline poll
- **WHEN** the library-sync stage runs on a library with no prior sync
- **THEN** it establishes the baseline exactly as a first poll does, creating no historical sessions

#### Scenario: No stage authors a derived value
- **WHEN** any stage runs
- **THEN** derived values are written only by the existing on-device path

#### Scenario: Concurrent work
- **WHEN** a stage starts work that is already running
- **THEN** the existing work's own concurrency policy applies, exactly as it would from that work's
  existing control

### Requirement: Setup can be run again later
The system SHALL let the user run setup again after onboarding, presenting the same stages with each
stage's last recorded outcome and every stage unselected. Setup completion SHALL be informational
and SHALL NOT gate access to any part of the app.

#### Scenario: Running setup after declining it
- **WHEN** the user declined setup during onboarding and later runs it
- **THEN** the same checklist is presented and the selected stages run

#### Scenario: Last outcome shown
- **WHEN** setup is presented again
- **THEN** each stage shows its last recorded outcome

#### Scenario: Re-run defaults to nothing selected
- **WHEN** setup is presented again
- **THEN** no stage is selected by default, so a re-run is deliberate

#### Scenario: Completion gates nothing
- **WHEN** setup has never been run, or was run and every stage was skipped
- **THEN** every part of the app remains fully usable

#### Scenario: A stage added after a completed setup
- **WHEN** a stage is registered after the user completed setup
- **THEN** that stage has no recorded outcome and is presented as never run, without setup being
  presented again unprompted

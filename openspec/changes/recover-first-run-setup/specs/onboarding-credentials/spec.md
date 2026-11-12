# Spec Delta

## ADDED Requirements

### Requirement: First-run onboarding offers an optional history import
Before first arrival at Home, onboarding SHALL offer an explicit Steam history import decision, with no import selected by default, an Import action, and a Skip action. It SHALL explain that the action counts pre-existing Steam playtime toward XP but cannot reconstruct historical session timestamps. Skipping SHALL leave import available later from Settings.

#### Scenario: Setup completes
- **WHEN** a first-run user continues from setup
- **THEN** the history-import decision is offered before Home unless it was already resolved
- **AND** no import runs without an explicit Import action

#### Scenario: Setup is declined or left early
- **WHEN** a first-run user skips setup or continues while setup work is outstanding
- **THEN** the optional history-import decision is still offered
- **AND** Skip allows immediate entry into the app without awaiting setup or importing history

#### Scenario: Lifetime totals disclosure
- **WHEN** the history-import decision is displayed
- **THEN** it explains that imported lifetime totals can contribute XP but do not supply dates or historical session timestamps

#### Scenario: Import is not ready
- **WHEN** no successful library snapshot is available yet
- **THEN** the decision explains that a library sync must finish before import can run
- **AND** Skip remains available and no completed import is recorded

#### Scenario: Import fails
- **WHEN** an explicitly requested import does not finish successfully
- **THEN** onboarding shows an attributable error and offers Retry and Skip rather than trapping the user

### Requirement: The history decision survives first-run interruption
An unresolved first-run history-import decision SHALL survive process death independently of background setup completion. A resolved decision SHALL NOT be presented again unprompted. Existing configured installs and later credential editing SHALL NOT acquire a new first-run takeover solely because this choice was added.

#### Scenario: Setup finishes before the history decision
- **WHEN** setup completes and the process ends before the first-run user resolves the history decision
- **THEN** the next launch restores the unresolved decision rather than silently going to Home

#### Scenario: Import completes just before interruption
- **WHEN** history import completes and the process ends before its UI acknowledges completion
- **THEN** the next launch recognizes the recorded import and does not import it again or require another import decision

#### Scenario: Skip survives restart
- **WHEN** the user skips history import and restarts the app
- **THEN** onboarding stays dismissed and import remains available from Settings

#### Scenario: Existing configured install upgrades
- **WHEN** an install with no outstanding first-run flow upgrades to this behavior
- **THEN** it opens normally without an unsolicited history decision or credential takeover

#### Scenario: Credentials edited later
- **WHEN** a configured user edits credentials outside the first-run flow
- **THEN** the optional history-import decision is not presented again unprompted

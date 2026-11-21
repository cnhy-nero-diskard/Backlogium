## MODIFIED Requirements

### Requirement: The history decision survives first-run interruption
An unresolved first-run history decision SHALL survive process death independently of setup work. The active account's choice and any explicitly admitted import SHALL be durably attributable to that account. A resolved decision SHALL stay dismissed. Previously configured installs without an outstanding first-run flow SHALL open normally.

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

#### Scenario: Process stops after import consent
- **WHEN** the process ends after an explicit import request is recorded but before it finishes
- **THEN** onboarding restores that request and reconciles its outcome without asking for new consent or adding credit again

#### Scenario: Continue while import is pending
- **WHEN** the user chooses Continue/do later after explicitly requesting import
- **THEN** Home becomes available, the admitted request remains recoverable in Settings, and restart leaves onboarding dismissed

#### Scenario: Account changes during onboarding import
- **WHEN** a prior account's request reports a result after account replacement
- **THEN** it cannot resolve the replacement account's first-run decision

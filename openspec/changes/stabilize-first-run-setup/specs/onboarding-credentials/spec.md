## MODIFIED Requirements

### Requirement: Onboarding leads into setup
Completing the credential flow SHALL lead into first-run setup rather than directly into the app, so that a newly configured install can be populated rather than empty. Completing, continuing past, or declining setup SHALL lead to an explicit optional Steam-history import decision before first arrival at Home. Declining setup and skipping history import SHALL leave every part of the app usable. The first-run journey SHALL remain durably owed until the user explicitly completes or skips the history decision; setup worker completion alone SHALL NOT dismiss that journey.

#### Scenario: Setup offered after credentials are saved
- **WHEN** credentials are verified and persisted for the first time
- **THEN** first-run setup is presented

#### Scenario: Declining setup
- **WHEN** the user declines setup
- **THEN** no setup work is required and the optional history decision is presented with Skip/do later before entry into the configured app

#### Scenario: Editing credentials later
- **WHEN** an already-configured user reopens the credential flow to change credentials
- **THEN** the new credentials are verified, and neither setup nor the first-run history decision is presented again unprompted

#### Scenario: Step count reflects the flow
- **WHEN** the credential flow presents its progress through its steps
- **THEN** the count reflects the credential steps it actually has and does not count optional setup or history decisions as credential-verification steps

#### Scenario: Background setup finishes before the decision
- **WHEN** all setup jobs finish while the first-run history decision is still unanswered
- **THEN** the decision remains owed and a cold launch does not bypass it to Home

## ADDED Requirements

### Requirement: First-run history import is an explicit optional decision
The onboarding history surface SHALL explain that the existing one-time operation counts pre-existing Steam lifetime playtime toward XP under the existing rules, but cannot reconstruct historical session timestamps, daily quests, or streaks from those counters. Import SHALL require an explicit user action and SHALL never run because a default setup selection, background sync, or navigation action implies consent. Skip/do later SHALL always remain available before import is requested, including when initial library sync was skipped, failed, or remains pending. The surface SHALL explain unavailable baseline readiness and preserve later access through Settings.

#### Scenario: Ready library
- **WHEN** the user reaches the history decision with a confirmed library baseline
- **THEN** the surface offers Import and Skip/do later with the effect and limitations explained

#### Scenario: No baseline available
- **WHEN** initial sync has not established a confirmed baseline
- **THEN** the surface explains why import is unavailable, offers Skip/do later and a route to library-sync recovery, and does not mark history imported

#### Scenario: User skips the decision
- **WHEN** the user selects Skip/do later
- **THEN** the decision is recorded as deferred, Home becomes available, no history is imported, and the existing Settings import remains available

#### Scenario: Import already exists
- **WHEN** the active account already has a completed import
- **THEN** the surface reflects that state and allows continuation without importing again

### Requirement: First-run history decisions survive interruption without trapping the user
The system SHALL preserve the active account's first-run phase and any explicit import request across process death. An interrupted unanswered choice SHALL resume at that choice without asking for already-persisted credentials. An interrupted import SHALL reconcile the existing operation and its recomputation before claiming success, without importing twice. Import failure SHALL show an actionable reason and permit Retry or Skip/do later; a completed raw import awaiting recomputation SHALL remain identifiable rather than be represented as an untouched account. Explicitly leaving a pending import SHALL defer the onboarding journey without undoing or duplicating that import, and its recovery SHALL remain observable through Settings.

#### Scenario: Process death on the unanswered choice
- **WHEN** the process ends before the user chooses Import or Skip/do later
- **THEN** a cold launch resumes the history choice and does not re-present credential entry

#### Scenario: Process death after consent
- **WHEN** the process ends after the import request was recorded but before import completion
- **THEN** recovery reconciles that request and resumes unfinished work without a second import

#### Scenario: Import cannot complete
- **WHEN** an import request fails or loses its baseline eligibility before its commit
- **THEN** the surface explains the outcome and offers Retry or Skip/do later without false success

#### Scenario: User continues while recovery is pending
- **WHEN** the user explicitly chooses to continue/do later during an admitted import or pending recomputation
- **THEN** Home becomes available, the first-run journey does not reopen automatically, and the admitted operation remains recoverable through the existing import path

#### Scenario: Previously configured install upgrades
- **WHEN** an install without an unfinished first-run journey upgrades
- **THEN** it is not forced through setup or the new history choice

#### Scenario: Steam account changes
- **WHEN** an account change supersedes an unfinished first-run phase or import request
- **THEN** that request cannot import for, or advance onboarding on behalf of, the replacement account

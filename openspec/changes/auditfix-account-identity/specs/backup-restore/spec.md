# backup-restore

## MODIFIED Requirements

### Requirement: Cross-account import is allowed with a warning
When the SteamID64 recorded in an imported backup differs from the currently signed-in account's
SteamID64, the system SHALL present a clear warning identifying the mismatch and stating that the
imported data belongs to a different account and will be merged with the current account's data,
but SHALL NOT block the import. This differs deliberately from changing the configured account:
an import is a considered act on identified data, whereas an account change is a credentials edit
whose data consequences the user has no reason to anticipate.

#### Scenario: Mismatched account detected
- **WHEN** the user attempts to import a file whose recorded SteamID64 differs from the
  currently signed-in account
- **THEN** a warning identifying the mismatch is shown before the import proceeds, stating that
  the data belongs to a different account and will be merged with the current account's

#### Scenario: User proceeds past the warning
- **WHEN** the user confirms the import despite the mismatch warning
- **THEN** the import proceeds using the same merge semantics as a matching-account import

#### Scenario: Import does not change the configured account
- **WHEN** a cross-account import completes
- **THEN** the configured SteamID is unchanged, and no account-change consequence is triggered

#### Scenario: Relationship to changing the configured account
- **WHEN** the user instead changes the configured SteamID to the one recorded in a backup
- **THEN** that follows the account-change requirements rather than these import requirements,
  so the two paths are distinguishable rather than contradictory

# onboarding-credentials

## ADDED Requirements

### Requirement: Changing the configured SteamID has a defined data consequence
Saving a SteamID different from the one currently configured SHALL be treated as an account
change with an explicit, confirmed consequence for stored data. The system SHALL NOT accept a
changed SteamID and leave data recorded under the previous account in place unlabelled, because
subsequent polls would then compare one account's playtime against another's baseline.

#### Scenario: SteamID changed
- **WHEN** the user saves credentials whose SteamID differs from the configured one
- **THEN** the change is not applied until the user confirms it, having been told what happens
  to data recorded under the previous account

#### Scenario: Confirmation declined
- **WHEN** the user declines the confirmation
- **THEN** the configured credentials and all stored data are left exactly as they were

#### Scenario: Export offered before data is discarded
- **WHEN** confirming the change would discard data recorded under the previous account
- **THEN** the user is offered an export of that data before it is discarded

#### Scenario: API key changed alone
- **WHEN** the user saves credentials whose API key differs but whose SteamID is unchanged
- **THEN** the API key is updated with no effect on stored data and no confirmation

#### Scenario: First configuration
- **WHEN** credentials are saved and no SteamID was previously configured
- **THEN** they are stored without confirmation, as there is no previous account

#### Scenario: Account change survives interruption
- **WHEN** a confirmed account change is interrupted at any point
- **THEN** the next start detects the incomplete change and completes it, rather than leaving
  credentials naming one account while data reflects another

#### Scenario: No sync runs against an incomplete account change
- **WHEN** an account change has not finished applying
- **THEN** no playtime poll is permitted to diff against the stored data until it has, so a
  half-applied change cannot produce a baseline from one account and a poll from another

#### Scenario: Account-independent data is retained
- **WHEN** a confirmed account change discards account-specific data
- **THEN** data that is a property of a game rather than of an account, such as external
  completion-time estimates, is retained and is not removed as a side effect of discarding the
  library it was linked to

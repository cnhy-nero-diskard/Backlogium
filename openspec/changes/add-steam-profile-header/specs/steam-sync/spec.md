## ADDED Requirements

### Requirement: Persist player identity on sync
The sync SHALL persist the player's Steam persona name and avatar URL alongside the existing
profile aggregates, so identity is available to the UI without a network call.

#### Scenario: Identity captured during sync
- **WHEN** a sync completes successfully
- **THEN** the player's current persona name and avatar URL are stored locally

#### Scenario: Identity refreshed on change
- **WHEN** a later sync observes a different persona name or avatar
- **THEN** the stored values are updated to the newer ones

#### Scenario: Identity unavailable
- **WHEN** the player summary cannot be retrieved or exposes no identity fields
- **THEN** any previously stored identity is left intact and the sync does not fail

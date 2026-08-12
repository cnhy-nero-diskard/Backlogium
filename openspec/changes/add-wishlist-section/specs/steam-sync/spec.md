## MODIFIED Requirements

### Requirement: Persist player identity on sync
The sync SHALL persist the player's Steam persona name, avatar URL, and store region alongside the
existing profile aggregates, so identity is available to the UI without a network call and prices
can be requested in the player's own currency without a separate lookup.

#### Scenario: Identity captured during sync
- **WHEN** a sync completes successfully
- **THEN** the player's current persona name, avatar URL, and store region are stored locally

#### Scenario: Identity refreshed on change
- **WHEN** a later sync observes a different persona name, avatar, or store region
- **THEN** the stored values are updated to the newer ones

#### Scenario: Identity unavailable
- **WHEN** the player summary cannot be retrieved or exposes no identity fields
- **THEN** any previously stored identity is left intact and the sync does not fail

#### Scenario: Store region absent from the profile
- **WHEN** the player summary exposes no country for the player
- **THEN** no store region is stored, any previously stored region is left intact, and the sync
  does not fail

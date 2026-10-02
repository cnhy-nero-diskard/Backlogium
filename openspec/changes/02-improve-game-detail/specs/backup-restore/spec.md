## ADDED Requirements

### Requirement: Artwork preferences retain explicit selection and reset
Backups and automatic snapshots SHALL carry a tracked game's selected supported Steam artwork variant or an explicit reset, independently of its favorite value. An older backup or favorite-only record without an artwork preference SHALL leave the current selection unchanged. An explicit selection/reset SHALL replace only that field for the represented app ID. No arbitrary URLs, image files, credentials, or inferred favorite changes SHALL be added by this preference. Imports SHALL validate all preference values before the existing all-or-nothing merge and retain warned cross-account behavior.

#### Scenario: Chosen variant round-trips
- **WHEN** a backup containing a selected variant is restored
- **THEN** the same variant remains preferred and the fallback chain handles its current availability

#### Scenario: Reset round-trips
- **WHEN** an explicit reset is imported for a game with a local override
- **THEN** the override is cleared without changing its favorite preference

#### Scenario: Favorite-only backup is imported
- **WHEN** a supported older record contains favorite data but no artwork field
- **THEN** favorite merge proceeds while the existing artwork preference is preserved

#### Scenario: Invalid artwork preference
- **WHEN** a backup contains an unsupported variant or invalid reset representation
- **THEN** import rejects the payload before writing any records

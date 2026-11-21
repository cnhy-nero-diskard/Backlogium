## ADDED Requirements

### Requirement: Artwork preferences retain explicit selection and reset
Backups and snapshots SHALL carry a supported Steam cover variant or explicit reset independently of the favorite value. Omitted artwork SHALL preserve local selection while an explicit choice SHALL replace it. Imports SHALL validate before the atomic, warned merge. Artwork records SHALL contain no arbitrary URLs, image files, credentials, or implied favorite change.

#### Scenario: Chosen variant round-trips
- **WHEN** a backup containing a selected variant is restored
- **THEN** the same variant remains preferred and the fallback chain handles its current availability

#### Scenario: Reset round-trips
- **WHEN** a backup with an explicit artwork reset and the same favorite value as the local game is imported
- **THEN** the override is cleared and the favorite value is preserved

#### Scenario: Favorite-only backup is imported
- **WHEN** a supported older record contains favorite data but no artwork field
- **THEN** favorite merge proceeds while the existing artwork preference is preserved

#### Scenario: Invalid artwork preference
- **WHEN** a backup contains an unsupported variant or invalid reset representation
- **THEN** import rejects the payload before writing any records

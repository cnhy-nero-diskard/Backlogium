## ADDED Requirements

### Requirement: Durable local-first Steam image resolution
Every Steam image URL rendered by Backlogium SHALL first resolve to its valid durable downloaded
copy when one exists and SHALL otherwise retain the existing on-the-fly network-loading path. This
resolution SHALL apply consistently to profile avatars, game icons, game artwork, achievement
icons, history thumbnails, Home imagery, and game-detail accent extraction.

#### Scenario: Durable copy exists
- **WHEN** a UI surface requests a Steam image URL with a valid downloaded file
- **THEN** the image is read from the durable local file without requiring network access

#### Scenario: Durable copy is absent
- **WHEN** a UI surface requests a Steam image URL without a valid downloaded file
- **THEN** the existing on-the-fly loader requests the original URL

#### Scenario: Durable copy cannot be read or decoded
- **WHEN** a supposedly stored local asset fails during image loading
- **THEN** its manifest entry is invalidated
- **AND** the loader retries the original remote URL before presenting the existing failure state

#### Scenario: Device is offline with stored assets
- **WHEN** the device is offline and valid downloaded copies exist for requested Steam images
- **THEN** those images render from local storage

#### Scenario: Device is offline without a stored asset
- **WHEN** the device is offline and no valid local copy exists for a requested Steam image
- **THEN** the current themed loading or failure treatment remains intact

### Requirement: Existing artwork fallback behavior is preserved
Local-first resolution SHALL operate per URL without changing the existing ordered Steam artwork
candidate lists, layout geometry, placeholders, or themed all-candidates-failed states.

#### Scenario: Primary horizontal artwork is unavailable
- **WHEN** `header.jpg` has neither a usable durable copy nor a successful remote response
- **THEN** the loader continues through `library_hero.jpg`, `capsule_616x353.jpg`, `hero_capsule.jpg`, and `library_600x900.jpg` in the existing order

#### Scenario: Primary grid artwork is unavailable
- **WHEN** `hero_capsule.jpg` has neither a usable durable copy nor a successful remote response
- **THEN** the loader continues through `library_hero.jpg`, `library_600x900.jpg`, `header.jpg`, and `capsule_616x353.jpg` in the existing order

#### Scenario: Local fallback succeeds
- **WHEN** a primary artwork candidate fails but a later candidate has a valid durable copy
- **THEN** the later candidate renders from local storage with the existing surface geometry

#### Scenario: Every local and remote candidate fails
- **WHEN** no artwork candidate can be loaded from durable storage or the network
- **THEN** the existing generic game fallback is shown without a broken-image placeholder

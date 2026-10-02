## ADDED Requirements

### Requirement: Selected Steam covers use existing offline asset behavior
The system SHALL resolve a tracked game's selected cover variant from the same supported Steam image families and local storage used by ordinary covers. Chooser previews SHALL prefer valid cached images and use only bounded user-initiated image reads when online. Selecting or resetting a variant SHALL NOT enqueue bulk downloads, trigger Steam metadata discovery, or mutate the integrity-checked asset manifest as though unvalidated files were stored. A missing or failed selected asset SHALL fall back normally without erasing the preference.

#### Scenario: Selected asset is cached
- **WHEN** the user chooses a variant already stored in the offline asset inventory
- **THEN** its cover resolves offline without a new download job

#### Scenario: Selection is not cached offline
- **WHEN** the stored selected asset is unavailable offline
- **THEN** the cover uses an available fallback or themed placeholder and retains the selected preference

#### Scenario: Chooser opened online
- **WHEN** the user opens the chooser with connectivity
- **THEN** only the bounded supported candidates for that app ID are previewed without starting a bulk asset or sync job

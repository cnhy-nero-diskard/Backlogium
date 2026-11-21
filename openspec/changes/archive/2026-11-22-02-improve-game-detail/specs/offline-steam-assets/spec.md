## ADDED Requirements

### Requirement: Selected Steam covers use existing offline asset behavior
Selected covers SHALL use supported Steam image families and the existing offline store. Previews SHALL prefer cache and make bounded user-initiated reads online. Selection and reset SHALL leave bulk jobs, metadata discovery, and the validated manifest alone. A failed or missing asset SHALL use normal fallbacks and preserve the preference.

#### Scenario: Selected asset is cached
- **WHEN** the user chooses a variant already stored in the offline asset inventory
- **THEN** its cover resolves offline without a new download job

#### Scenario: Selection is not cached offline
- **WHEN** the stored selected asset is unavailable offline
- **THEN** the cover uses an available fallback or themed placeholder and retains the selected preference

#### Scenario: Chooser opened online
- **WHEN** the user opens the chooser with connectivity
- **THEN** only the bounded supported candidates for that app ID are previewed without starting a bulk asset or sync job

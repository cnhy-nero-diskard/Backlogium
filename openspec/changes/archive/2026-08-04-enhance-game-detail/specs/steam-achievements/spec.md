## ADDED Requirements

### Requirement: Retain achievement descriptions
The achievement fetch SHALL retain each achievement's description and its hidden flag from Steam's
achievement schema, so the UI can present them without an additional network call.

#### Scenario: Description stored
- **WHEN** a game's achievement schema is fetched
- **THEN** each achievement's description and hidden flag are stored alongside its display name and
  icon

#### Scenario: Description absent from the schema
- **WHEN** Steam supplies no description for an achievement
- **THEN** no description is stored for it and the fetch does not fail

#### Scenario: Existing achievements not force-refreshed
- **WHEN** achievement rows were stored before descriptions were retained
- **THEN** they are not eagerly re-fetched, and their descriptions populate on the game's next
  natural schema fetch

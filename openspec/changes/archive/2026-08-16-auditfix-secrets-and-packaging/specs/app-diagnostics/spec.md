# app-diagnostics

## MODIFIED Requirements

### Requirement: Credentials never reach a log sink
The system SHALL remove credential values from any request identifier before it is written to a log
sink or stored as a diagnostic record. Redaction SHALL be applied where request data is formatted,
so that no call site can emit an unredacted value by omission. The stored identifier SHALL be built
from a normalized endpoint plus an explicitly enumerated set of parameters known to be safe;
parameters outside that set SHALL NOT have their values stored, so that a parameter the app has
never seen before cannot leak by default.

#### Scenario: Request identifier carrying credentials
- **WHEN** a request whose identifier includes the Steam API key or SteamID is recorded
- **THEN** those values are replaced with a redacted placeholder before the record is emitted or
  stored

#### Scenario: SteamID under any parameter name
- **WHEN** a request carries the user's SteamID under any parameter name, whether singular or
  plural
- **THEN** the value is not stored, because only enumerated safe parameters have their values
  retained

#### Scenario: An unrecognized parameter appears
- **WHEN** a request carries a parameter the app does not enumerate as safe
- **THEN** its value is not stored, and the record remains valid and attributable to its endpoint

#### Scenario: Endpoint remains identifiable
- **WHEN** a request is recorded with credentials redacted
- **THEN** the endpoint and any non-credential parameters remain legible, so the request is still
  identifiable

#### Scenario: Redaction is not caller-controlled
- **WHEN** any component records request data
- **THEN** redaction has already been applied by the formatting layer and cannot be bypassed by that
  component

#### Scenario: Stored records contain no credentials
- **WHEN** stored diagnostic records are read back or displayed
- **THEN** no credential value appears in them

#### Scenario: Records written under an earlier scheme
- **WHEN** diagnostic records written before this requirement took effect are still retained
- **THEN** any credential value they contain is removed or the records are discarded, so reading
  history back cannot surface a credential

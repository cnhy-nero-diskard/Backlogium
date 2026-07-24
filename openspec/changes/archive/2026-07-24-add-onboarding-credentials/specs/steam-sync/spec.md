## MODIFIED Requirements

### Requirement: Steam credential configuration
The system SHALL read the Steam Web API key and SteamID64 from an encrypted in-app credential
store (populated by the in-app onboarding flow), expose them through a credentials repository, and
SHALL NOT hardcode or commit these values. On first access, when the store is empty and
`BuildConfig` carries non-blank values, the system SHALL seed the store from `BuildConfig` once;
after seeding the encrypted store is the source of truth. All Steam requests SHALL obtain the key
and SteamID from the credentials repository rather than reading `BuildConfig` directly.

#### Scenario: Credentials present
- **WHEN** the encrypted store holds a Steam API key and SteamID64
- **THEN** the values are exposed through the credentials repository and used for all Steam requests

#### Scenario: Credentials seeded from build config
- **WHEN** the store is empty and `steam.apiKey`/`steam.steamId` were provided at build time
- **THEN** the values are imported into the encrypted store once and used thereafter as the stored
  credentials

#### Scenario: Credentials missing
- **WHEN** no credentials are stored and none can be seeded
- **THEN** the app treats itself as not configured and presents the onboarding flow instead of
  crashing

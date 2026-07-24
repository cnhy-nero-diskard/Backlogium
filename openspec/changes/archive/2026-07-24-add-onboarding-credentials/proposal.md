## Why

Steam credentials (API key + SteamID64) are baked in at build time from `local.properties`
via `BuildConfig`, so a shipped APK can only ever act for whoever compiled it — an end user
cannot supply their own account, and the "Steam not configured" state dead-ends by telling
them to edit `local.properties` and rebuild. Credentials also have no encrypted at-rest home:
`steam_id` sits in plaintext DataStore and the API key has no runtime path at all. This change
lets any user configure their own Steam account from inside the app, storing those secrets
encrypted, with a low-friction way to supply the SteamID64.

## What Changes

- Add a dedicated **onboarding flow** that captures the Steam Web API key and SteamID64 from
  inside the app. It takes over the screen on first run (replacing the dead-end "Steam not
  configured" empty state) and is **repeatable** afterward via a compact "Steam account" card
  on the Home screen that reopens the same flow.
- SteamID entry offers **two paths**: paste a raw SteamID64, or paste a Steam profile URL.
  URLs of the form `.../profiles/<id64>` are parsed locally; `.../id/<vanity>` URLs are
  resolved to a SteamID64 via the Steam Web API (`ResolveVanityURL`, aka "GetSteamID").
- Store credentials as **Keystore-encrypted in-app data** (AES-GCM key from the Android
  Keystore), replacing the plaintext `steam_id` pref and giving the API key a runtime home.
- **First-run seed**: if the encrypted store is empty and `BuildConfig` still carries values,
  import them once as a starting point; thereafter the in-app store is the source of truth.
- Reroute the two direct `BuildConfig.STEAM_API_KEY` reads (`LiveStatusRepository`,
  `SteamSyncWorker`) and the three `configured` checks (Home/Library/History view models)
  through a new credentials repository. **BREAKING** for the spec contract: build-time
  `BuildConfig` credentials are no longer the source of truth, only a one-time seed.

## Capabilities

### New Capabilities
- `onboarding-credentials`: in-app capture, validation, encrypted storage, and repeatable
  editing of Steam credentials, including the dual SteamID entry paths and vanity-URL
  resolution.

### Modified Capabilities
- `steam-sync`: the "Steam credential configuration" requirement changes from build-time
  `BuildConfig` as the source of truth to the encrypted in-app store (with a one-time
  `BuildConfig` seed), and callers read credentials through the new repository.
- `app-ui`: the Home screen gains a credentials/onboarding surface, and the "not configured"
  state changes from a dead-end message to an actionable onboarding takeover.

## Impact

- **New code**: `onboarding-credentials` capability — an `EncryptedCredentialStore`
  (DataStore + Android Keystore AES-GCM), a `CredentialsRepository` exposing credential and
  configured-state flows plus `resolveSteamId`, SteamID64 validation + URL/vanity parsing, and
  onboarding Compose UI (full-screen flow + Home card).
- **Steam API**: add `ResolveVanityURL` (`ISteamUser/ResolveVanityURL/v1/`) to `SteamApi` with
  its response DTO (`success=1` → `steamid`; `success=42` → no match).
- **Rerouted reads**: `LiveStatusRepository:65`, `SteamSyncWorker:49`, and the `configured`
  computation in `HomeViewModel`, `LibraryViewModel`, `HistoryViewModel` move off `BuildConfig`
  onto `CredentialsRepository`; `SettingsDataStore.steamIdFlow`/`setSteamId` are superseded.
- **Build config**: `STEAM_API_KEY` / `STEAM_ID` `BuildConfig` fields and the `local.properties`
  keys remain only as an optional one-time seed; no longer required for a usable build.
- **Dependencies**: adds a credential-encryption mechanism (Android Keystore-backed; exact
  library vs. DIY AES-GCM settled in design).
- **Navigation**: `BacklogiumAppRoot` / `Destination` gain an onboarding route or takeover.

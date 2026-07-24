## 1. Encrypted credential storage

- [x] 1.1 Add an `EncryptedCredentialStore` (DataStore-backed) that encrypts/decrypts values with an `AES/GCM/NoPadding` key from the `AndroidKeyStore` (random IV per value, base64 blob)
- [x] 1.2 Handle decrypt failure / Keystore-key invalidation by treating credentials as absent (return empty) rather than throwing
- [x] 1.3 Expose suspend read/write for `apiKey` and `steamId`, and a "has credentials" check

## 2. Credentials repository and wiring

- [x] 2.1 Add `CredentialsRepository` exposing `apiKeyFlow`, `steamIdFlow`, and derived `credentialsStateFlow` (`Unconfigured` | `Configured(apiKey, steamId)`), plus `save(apiKey, steamId)`
- [x] 2.2 Implement first-run seed: if the store is empty and `BuildConfig.STEAM_API_KEY`/`STEAM_ID` are non-blank, import once; never consult `BuildConfig` afterward
- [x] 2.3 Provide `EncryptedCredentialStore` + `CredentialsRepository` via Hilt in a DI module

## 3. Steam ID parsing, validation, and vanity resolution

- [x] 3.1 Add `ResolveVanityURL` (`ISteamUser/ResolveVanityURL/v1/`) to `SteamApi` and a `ResolveVanityResponse` DTO (`success`, `steamid`)
- [x] 3.2 Add `parseSteamIdInput(raw)` handling bare SteamID64, `/profiles/<id64>` (local extract), and `/id/<vanity>` (returns vanity token)
- [x] 3.3 Add SteamID64 validation (17 digits, begins `7656119`) used before accepting/storing any value
- [x] 3.4 Add `resolveSteamId(input)` on the repository: parse, then call `ResolveVanityURL` for vanity tokens, mapping `success!=1` and network/key errors to typed results
- [x] 3.5 Unit-test the parser/validator across all three shapes and the resolution error cases

## 4. Reroute credential reads off BuildConfig

- [x] 4.1 Change `LiveStatusRepository` to read the API key + SteamID from `CredentialsRepository`
- [x] 4.2 Change `SteamSyncWorker` to read the API key + SteamID from `CredentialsRepository`
- [x] 4.3 Derive `configured` in `HomeViewModel`, `LibraryViewModel`, `HistoryViewModel` from `credentialsStateFlow`
- [x] 4.4 Retire `SettingsDataStore.steamIdFlow`/`setSteamId`; keep `BuildConfig` fields as seed-only

## 5. Onboarding UI

- [x] 5.1 Build the onboarding flow composable: Step 1 API key entry (with help/link to the Steam key page), Step 2 SteamID entry with a raw-ID / profile-URL toggle
- [x] 5.2 Wire Step 2 states: idle / resolving / resolved(id) / error, with inline messages for no-match, invalid ID, and network/key failure
- [x] 5.3 Add an onboarding view model bridging the flow to `CredentialsRepository.save` / `resolveSteamId`, masking the API key in all display
- [x] 5.4 Add a Home "Steam account" card (active SteamID + masked key + Edit action) shown when configured
- [x] 5.5 Replace the "Steam not configured" `EmptyState` in `HomeScreen` with the full-screen onboarding takeover when `Unconfigured`
- [x] 5.6 Add the onboarding route/takeover to `BacklogiumAppRoot` / `Destination` and route the Edit action to it

## 6. Verification

- [x] 6.1 Manually verify first-run seed from an existing configured build, and a fresh build landing in onboarding
- [x] 6.2 Manually verify both SteamID paths (raw ID, `/profiles/` URL, `/id/` vanity URL) and each error state
- [x] 6.3 Confirm the stored credential blob is ciphertext on disk and the API key never appears in logs
- [x] 6.4 Run `openspec validate --change add-onboarding-credentials --strict` and the project build/tests

## Context

Steam credentials are read at build time from `local.properties` into `BuildConfig`
(`STEAM_API_KEY`, `STEAM_ID`). `SettingsDataStore` exposes a runtime override for the SteamID
only (plaintext DataStore pref, falling back to `BuildConfig.STEAM_ID`); the API key has no
runtime path and is read directly from `BuildConfig` in `LiveStatusRepository` and
`SteamSyncWorker`. Three view models (`HomeViewModel`, `LibraryViewModel`, `HistoryViewModel`)
independently compute `configured = BuildConfig.STEAM_API_KEY.isNotBlank() && steamId.isNotBlank()`.
When not configured, `HomeScreen` shows a dead-end `EmptyState` telling the user to edit
`local.properties` and rebuild.

`minSdk = 33`, so the Android Keystore (AES/GCM, `StrongBox` where present) is fully available.
No encryption, credential UI, or vanity-URL resolution exists today. The `HistoryImportCard` on
Home is a good precedent for a self-contained, re-runnable Home card with confirm dialogs.

## Goals / Non-Goals

**Goals:**
- Let any user configure their own Steam API key + SteamID64 from inside the app, with no rebuild.
- Store both secrets encrypted at rest (not readable in plaintext files or plain device backups).
- Two SteamID entry paths — raw ID, or profile URL (`/profiles/<id64>` parsed locally,
  `/id/<vanity>` resolved via the Steam Web API).
- Make onboarding repeatable and reachable from Home after first run.
- Preserve existing dev/CI builds: seed once from `BuildConfig`, then in-app is source of truth.

**Non-Goals:**
- Defeating a rooted/compromised device (a client-held API key is inherently extractable; the
  goal is at-rest hygiene, not DRM).
- OAuth / Steam OpenID sign-in — we still use a user-supplied Web API key.
- Multi-account support — a single active credential set, same as today.
- Server-side proxying of the API key.

## Decisions

### Decision 1: Credential storage — DataStore bytes encrypted with an Android Keystore AES-GCM key

Store the credential blob in a Preferences/`DataStore`, with values encrypted via an
`AES/GCM/NoPadding` key held in the `AndroidKeyStore` (per-value random IV prepended to the
ciphertext, base64-encoded).

- **Alternative — Jetpack Security `EncryptedSharedPreferences`**: familiar, but the
  `androidx.security:security-crypto` library is deprecated (Apr 2024) with no maintained
  successor; adopting new deprecated infra is a poor long-term bet.
- **Alternative — Tink directly**: robust, but a heavier dependency than one small secret pair
  warrants.
- **Alternative — DataStore plaintext (status quo for `steam_id`)**: simplest, but fails the
  "encrypted, not from local data" goal; the API key would leak via backups/root trivially.

Rationale: a Keystore-wrapped AES-GCM value is ~50 lines, adds no deprecated/heavy dependency,
and meets the at-rest goal on `minSdk 33`. Encapsulated behind `EncryptedCredentialStore` so the
mechanism can change later without touching callers.

### Decision 2: `CredentialsRepository` as the single source of truth

Introduce `CredentialsRepository` exposing `apiKeyFlow`, `steamIdFlow`, and a derived
`credentialsStateFlow` (`Unconfigured` | `Configured(apiKey, steamId)`), plus `save(...)` and
`resolveSteamId(input)`. All credential reads route through it: `LiveStatusRepository` and
`SteamSyncWorker` take the key/id from it instead of `BuildConfig`; the three view models derive
`configured` from `credentialsStateFlow` instead of computing it locally.

- **Alternative — extend `SettingsDataStore`**: it mixes gamification `RuleConfig` with
  credentials and is plaintext; credentials deserve their own encrypted, cohesive component.

Rationale: collapses three duplicated `configured` checks into one flow and removes the two
stray `BuildConfig` reads, which is the core of the "not from local data" ask.

### Decision 3: First-run seed from `BuildConfig`, then in-app wins

On first access, if the encrypted store is empty **and** `BuildConfig` carries non-blank values,
import them once into the encrypted store. After that the encrypted store is authoritative and
`BuildConfig` is never consulted again (even if the user later clears credentials).

- **Alternative — `BuildConfig` as a permanent fallback**: keeps working but never lets a user
  fully "own" their credentials and muddies which source is active.
- **Alternative — drop `BuildConfig` entirely**: cleanest, but forces every existing dev/CI
  build to re-onboard manually.

Rationale: zero-friction upgrade for existing builds without leaving a permanent build-time
backdoor.

### Decision 4: SteamID input normalization — one parser, three shapes

A single `parseSteamIdInput(raw)` handles: (a) a bare 17-digit SteamID64; (b) a
`steamcommunity.com/profiles/<id64>` URL → extract the digits; (c) a
`steamcommunity.com/id/<vanity>` URL → return the vanity token for resolution. SteamID64 is
validated as 17 digits beginning `7656119…` before it is accepted or stored, so a typo fails at
onboarding rather than as an empty sync later. Vanity resolution calls
`ISteamUser/ResolveVanityURL/v1/` (`success=1` → `steamid`; `success=42` → "no profile found").

- **Alternative — assume every URL is a vanity URL**: breaks on the very common `/profiles/`
  form and wastes an API call.

### Decision 5: Vanity resolution requires the key first → key-first flow ordering

Because `ResolveVanityURL` needs a valid API key, onboarding captures the API key first, then the
SteamID. The raw-ID and `/profiles/` paths don't strictly need the key, but a single key-first
ordering avoids a "can't resolve — no key yet" dead-end and keeps the flow linear.

### Decision 6: Two surfaces, one flow

`Unconfigured` state renders a full-screen onboarding takeover (replacing today's dead-end
`EmptyState`). Once `Configured`, Home shows a compact "Steam account" card (masked key + active
SteamID) whose "Edit" action reopens the same flow — satisfying "repeatable, on the home screen".
Implemented as a route/takeover in `BacklogiumAppRoot` so it composes with existing navigation.

## Risks / Trade-offs

- **[Client-held API key is extractable]** → Accept as a non-goal; encryption only raises the bar
  for casual/at-rest exposure. Document that the key is the user's own and rate-limited to them.
- **[Keystore key invalidation]** (e.g., some device backup/restore or lock-screen changes can
  drop Keystore keys) → decryption failure is treated as "credentials lost": fall back to
  `Unconfigured` and re-prompt onboarding rather than crashing.
- **[Vanity resolution failure modes]** (private profile, no match `success=42`, network error,
  invalid key) → each maps to a specific inline error in Step 2; never silently store a bad ID.
- **[Migration mid-sync]** removing `BuildConfig` as source of truth could strand a build with no
  in-app creds → the one-time seed covers existing configured builds; fresh builds land in the
  onboarding takeover, which is the intended entry point.
- **[Secret in logs]** the API key flows through Retrofit query params; ensure the key is never
  logged (existing `HttpLoggingInterceptor` is DEBUG-only) and mask it in all UI.

## Migration Plan

1. Add `EncryptedCredentialStore` + `CredentialsRepository`; wire via Hilt.
2. Implement first-run seed from `BuildConfig`.
3. Reroute `LiveStatusRepository`, `SteamSyncWorker`, and the three view models onto the repository.
4. Add `ResolveVanityURL` to `SteamApi` + DTO; add input parsing/validation.
5. Build the onboarding UI (full-screen flow + Home card) and replace the dead-end empty state.
6. Retire `SettingsDataStore.steamIdFlow`/`setSteamId`; keep `BuildConfig` fields as seed-only.
- **Rollback**: the change is additive behind the repository; reverting restores the `BuildConfig`
  reads. No persisted schema migration beyond the new encrypted store (safe to clear).

## Open Questions

- Should clearing credentials from the Home card also purge synced game/session data, or leave it
  intact for re-onboarding to the same account? (Leaning: leave intact.)
- Do we surface `StrongBox` usage when available, or transparently best-effort? (Leaning:
  best-effort, no UI.)

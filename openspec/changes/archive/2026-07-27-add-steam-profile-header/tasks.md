# Tasks — Steam profile header

> No new network calls. `GetPlayerSummaries` is already called by `LiveStatusRepository`; the
> identity fields are simply not deserialized today.

## 1. DTO
- [x] 1.1 `PlayerSummaryDto`: add `@SerialName("personaname") personaName: String = ""`,
  `@SerialName("avatarfull") avatarFull: String? = null`. `personastate` already present.
- [x] 1.2 Confirm the JSON config ignores unknown keys so the additions are safe (they are —
  verify in `NetworkModule`)

## 2. Persistence
- [x] 2.1 `PlayerProfile`: add `personaName: String? = null`, `avatarUrl: String? = null`
- [x] 2.2 Bump `BacklogiumDatabase` to v5; additive migration adding both nullable TEXT columns
- [x] 2.3 Register `MIGRATION_4_5` in `DatabaseModule`

## 3. Sync writes identity
- [x] 3.1 `SteamSyncWorker`: call `getPlayerSummaries` and persist `personaName`/`avatarUrl` onto
  `PlayerProfile`, preserving all existing aggregate fields
- [x] 3.2 A failed/empty summaries response must leave stored identity intact and not fail the sync
- [x] 3.3 `SteamSyncWorkerTest` (or equivalent): identity persisted; failure path preserves prior values

## 4. Live presence
- [x] 4.1 `LiveStatusRepository`: surface persona presence alongside `NowPlaying` (in-game /
  online / offline), derived from `personaState` + `gameId`. Not persisted.
- [x] 4.2 Update stored identity opportunistically when the live poll observes a newer
  persona name or avatar

## 5. UI
- [x] 5.1 New `ui/components/ProfileHeader.kt`: slim row — avatar (`SubcomposeAsyncImage` with the
  themed loading/error fallbacks used by `GameIcon`), persona name, presence label. No level number.
- [x] 5.2 A small shell-scoped ViewModel (or reuse of an existing repository flow) exposing
  persisted identity + live presence + configured state
- [x] 5.3 `BacklogiumAppRoot`: add `topBar = { ProfileHeader(...) }`; render nothing when
  unconfigured or still loading
- [x] 5.4 Verify `innerPadding` already applied to the `NavHost` correctly offsets every screen
  (no per-screen padding changes should be needed)
- [x] 5.5 Fallback presentation when no identity is stored yet: themed avatar glyph + a neutral label

## 6. Docs & specs
- [x] 6.1 Update `docs/ui-screens-descriptor.md` with the header
- [x] 6.2 Verify the `app-ui` and `steam-sync` spec deltas match the built behavior

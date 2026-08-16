## Why

An adversarial audit found five defects that share one shape: personal data or
credentials crossing a boundary they were never meant to cross, plus release
metadata that cannot distinguish one build from another. All five are confirmed in
the code, none require touching runtime logic, and three of them leak continuously
today rather than under some rare interleaving.

They are grouped into one change because the fixes are almost entirely
declarative — Gradle configuration, the manifest, two XML resource files, and one
small Kotlin object — and because shipping them together means one review of the
app's data-egress surface rather than five.

## What Changes

- **Android Auto Backup is given an explicit policy.** `allowBackup="true"` is
  currently paired with `backup_rules.xml` and `data_extraction_rules.xml` that are
  unmodified IDE templates — every rule inside them is commented out. The Room
  database, DataStore preferences, and the app's own JSON snapshots are therefore
  eligible for Google-hosted cloud backup and device-to-device transfer. Replace both
  templates with a deliberate policy.
- **Automatic snapshots move out of the platform-backup path.** `SnapshotStore.kt:25`
  writes unencrypted JSON containing the user's full playtime history to
  `context.filesDir`, which Auto Backup treats as eligible. Move the snapshot
  directory to `noBackupFilesDir` so one Backlogium backup cannot be silently copied
  into a second, platform-managed backup lifecycle with a different retention policy
  and a different owner.
- **Diagnostic redaction switches from a denylist to endpoint normalization.**
  `DiagnosticRedaction` (`Diagnostics.kt:12`) redacts `key` and `steamids` but not the
  singular `steamid`, which is the parameter `GetOwnedGames`,
  `GetPlayerAchievements`, and `GetRecentlyPlayedGames` actually send. The user's
  17-digit SteamID is being persisted into diagnostic records right now, in violation
  of an existing `app-diagnostics` requirement. A denylist of secret parameter names
  fails silently every time Steam adds a parameter; storing a normalized endpoint
  name plus a known set of safe parameters cannot.
- **Steam credentials are confined to debug builds.** `app/build.gradle.kts:53-54`
  emits `STEAM_API_KEY` and `STEAM_ID` via `buildConfigField` in `defaultConfig`,
  which applies to every variant. A developer who builds or signs a release locally
  while `local.properties` is populated embeds their own Steam API key and SteamID in
  the APK. CI happens to leave these blank today, which makes this latent rather than
  active — but it is latent by accident, not by construction.
- **Release version metadata is derived from the release tag.** `versionCode = 1` and
  `versionName = "1.0"` are hardcoded, and neither `scripts/bump-tag.sh` nor
  `scripts/bump-tag.ps1` references Gradle at all. Every tagged APK ships as the same
  Android package version, which breaks upgrade semantics and would be rejected
  outright by any Play-style distribution channel.

No user-visible behaviour changes. No database migration. No new dependencies.

## Capabilities

### New Capabilities

- `release-packaging`: what a distributable Backlogium build must guarantee about
  itself — that its version metadata identifies it uniquely and orders correctly
  against other builds, and that it contains no developer credentials regardless of
  the machine it was assembled on.

### Modified Capabilities

- `app-diagnostics`: the existing "Credentials never reach a log sink" requirement is
  already violated by the singular-`steamid` gap. Strengthen it from "remove known
  credential parameters" to "record a normalized endpoint plus a known-safe parameter
  set", so the guarantee holds by construction rather than by the denylist staying
  current with Steam's API surface.
- `backup-restore`: add a requirement that the app's platform-backup policy is
  explicit, and that data the app already backs up through its own export model is
  excluded from the platform's parallel backup channel.

## Impact

**Code and configuration**

| Path | Change |
|---|---|
| `app/build.gradle.kts` | version metadata from tag; credential fields scoped to debug |
| `app/src/main/res/xml/backup_rules.xml` | template → explicit policy |
| `app/src/main/res/xml/data_extraction_rules.xml` | template → explicit policy |
| `app/src/main/AndroidManifest.xml` | possibly `allowBackup` (see design.md) |
| `app/src/main/java/.../data/diagnostics/Diagnostics.kt` | denylist → normalization |
| `app/src/main/java/.../data/backup/SnapshotStore.kt` | `filesDir` → `noBackupFilesDir` |
| `.github/workflows/release.yml` | pass tag-derived version into the Gradle build |
| `scripts/bump-tag.{sh,ps1}` | unchanged, or emit the version the build reads |

**Migration consequence, deliberately accepted**: moving the snapshot directory
orphans snapshots already written under `filesDir`. Existing snapshots must be moved
on first launch rather than abandoned — a user's rolling backup history disappearing
without explanation is a worse outcome than the leak being fixed a release later.
Design covers the one-shot relocation.

**Not addressed here**: `BuildConfig.STEAM_API_KEY` as a credential-seeding mechanism
is unchanged — this change controls which variants receive a value, not whether the
seed path should exist. `onboarding-credentials` already owns that question.

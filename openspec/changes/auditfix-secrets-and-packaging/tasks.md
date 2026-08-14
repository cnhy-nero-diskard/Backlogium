## 1. Diagnostics redaction

- [ ] 1.1 Determine the current diagnostic retention window from `openspec/specs/app-diagnostics/spec.md` and the `DiagnosticsDao` pruning code; record whether it is short enough for old rows to age out on their own, which decides whether task 1.5 is needed
- [ ] 1.2 Replace `DiagnosticRedaction.secretParameters` in `data/diagnostics/Diagnostics.kt` with an enumerated safe-parameter allowlist (`appid`, `count`, `l`, `format`) and build the stored identifier from the normalized endpoint path plus only those parameters
- [ ] 1.3 Add unit tests asserting no value is stored for `steamid`, `steamids`, or `key`, that an unknown parameter's value is dropped while the record stays attributable to its endpoint, and that two calls to the same endpoint with different SteamIDs produce the same identifier
- [ ] 1.4 Verify the diagnostics UI (`ui/diagnostics/`) still renders normalized identifiers legibly — grouping and per-endpoint counts must remain readable
- [ ] 1.5 If task 1.1 showed retention is long, add a one-shot purge or rewrite of stored request identifiers so history cannot surface a credential written under the old scheme

## 2. Snapshot relocation

- [ ] 2.1 Change `SnapshotStore.kt:25` to resolve its directory under `context.noBackupFilesDir` instead of `context.filesDir`
- [ ] 2.2 Implement an idempotent one-shot relocation that copies each `*.json` from the old `filesDir` directory to the new one, deletes a source file only after its copy is verified, and removes the old directory only once it is empty
- [ ] 2.3 Ensure retention pruning cannot run against a partially relocated set — pruning must operate on the new directory only, and must not discard a snapshot whose only copy is still in the old location
- [ ] 2.4 Invoke the relocation once on app start, before any snapshot listing or write, and make a failure non-fatal and retried on the next start
- [ ] 2.5 Add tests for: nothing to migrate; a normal migration preserving all snapshots and their timestamps; an interrupted migration converging on a second run; and no data loss when a copy fails

## 3. Platform backup policy

- [ ] 3.1 Replace `app/src/main/res/xml/backup_rules.xml` with an explicit policy excluding the Room database, DataStore preferences, and the snapshot directory
- [ ] 3.2 Replace `app/src/main/res/xml/data_extraction_rules.xml` with an explicit policy for both `cloud-backup` and `device-transfer`, applying the same exclusions
- [ ] 3.3 Confirm `allowBackup="true"` remains correct given the rules now in place (see design.md Decision 1) and leave a comment in the manifest recording why it is not `false`
- [ ] 3.4 Verify the exclusions on a device with `adb shell bmgr backupnow` plus a restore, or document why verification was deferred — this is the one item in this change that static review cannot confirm

## 4. Credential scoping

- [ ] 4.1 Move the `STEAM_API_KEY` and `STEAM_ID` `buildConfigField` declarations in `app/build.gradle.kts` out of `defaultConfig` and into the `debug` variant
- [ ] 4.2 Pin both fields to explicit empty-string literals in the `release` variant so the fields still resolve and the credential seed path compiles unchanged
- [ ] 4.3 Assemble a release APK with `local.properties` populated and confirm by inspecting the built `BuildConfig` that both values are empty
- [ ] 4.4 Assemble a debug APK and confirm the values are still present, so local development is unaffected

## 5. Version metadata

- [ ] 5.1 Make `versionName` and `versionCode` in `app/build.gradle.kts` read project properties when supplied, falling back to `0.0.0-dev` / `1` for local builds
- [ ] 5.2 Add the tag-to-version derivation to `.github/workflows/release.yml`: strip the leading `v` for `versionName`, compute `major*1_000_000 + minor*1_000 + patch` for `versionCode`, and pass both as Gradle project properties
- [ ] 5.3 **Enforce the encoding's range in CI**: fail the release when any of major, minor, or patch reaches 1000, alongside the existing master-reachability and semver checks — documenting the ceiling in a comment is not sufficient, since `v1.100.0` and `v2.0.0` collide under the narrower encoding and the spec requires unconditional ordering
- [ ] 5.4 Confirm `scripts/bump-tag.sh` and `scripts/bump-tag.ps1` need no change, and add a comment to each stating that version metadata is derived in CI so a future reader does not add Gradle editing back
- [ ] 5.5 Verify end to end on a throwaway tag that the produced APK reports the expected version name and code
- [ ] 5.6 Test the derivation and the guard: adjacent versions order correctly, a major increment outranks every version below it, and an out-of-range component fails the release with a message naming it

## 6. Verification and close-out

- [ ] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [ ] 6.2 Run `./gradlew assembleDebug` and `assembleRelease` and confirm both succeed
- [ ] 6.3 Re-run the audit's five checks against the tree: hardcoded version gone, credential fields debug-only, backup rules non-template, snapshots under `noBackupFilesDir`, singular `steamid` unstorable
- [ ] 6.4 Run `openspec validate auditfix-secrets-and-packaging`
- [ ] 6.5 Note in the commit message that the device-transfer convenience regression (design.md Decision 1) is a deliberate trade, so the reasoning survives in git

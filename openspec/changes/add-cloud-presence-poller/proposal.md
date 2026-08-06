## Why

Presence detection today lives entirely on the phone. `PresenceService` polls every 30 seconds while it is alive, and `SteamSyncWorker` catches up roughly every 15 minutes — but both stop when Android kills the process, the phone sleeps deeply, or the user force-quits. `enhance-now-playing/design.md:150` names the resulting gap directly: "Detection latency (~15 min worst case) — inherent to not having a resident poller." Sessions played while the phone is unreachable are simply never seen.

A scheduled Cloud Function is a resident observer that never sleeps. This change stands one up and has it record what Steam reports, so the gap is captured even though nothing consumes the recording yet.

## What Changes

- Add a `functions/` Node project to this repo, deployed to Firebase in `asia-southeast1` (matching the Firestore database location, which is permanent).
- Add a scheduled Cloud Function that polls Steam's `GetPlayerSummaries` once per minute using a Steam Web API key stored in Secret Manager.
- Write observed presence to Firestore under `players/{steamId}`: a `current` document reflecting present state, and an append-only `presence/{timestamp}` subcollection recording transitions.
- Write on change only — an unchanged poll performs no Firestore write.
- Stamp every written document with a schema version (`v: 1`) so a future reader can tell which shape it is reading.
- Retain the presence log indefinitely. No TTL policy.
- Add Firestore security rules denying all client access. The poller writes through the Admin SDK, which bypasses rules; no other reader exists yet.
- **No Android code changes.** The app continues to poll and own sessions exactly as it does today. Nothing reads the Firestore data in this change.

## Capabilities

### New Capabilities
- `cloud-presence-poller`: A resident server-side observer that samples Steam presence on a fixed schedule and records state and transitions to Firestore, independent of whether the Android app is running.

### Modified Capabilities

None. The app is untouched; `live-status` and `steam-sync` keep their existing requirements and remain the sole authors of sessions and XP.

## Impact

- **New:** `functions/` (Node/TypeScript), `firebase.json`, `.firebaserc`, `firestore.rules`, `firestore.indexes.json` at the repo root.
- **New infrastructure:** Firebase project on the Blaze plan; Firestore in `asia-southeast1`; Cloud Scheduler, Cloud Functions, Cloud Build, Artifact Registry, Eventarc, and Secret Manager APIs enabled.
- **Secrets:** `STEAM_API_KEY` in Secret Manager. The key now exists in two places — encrypted on-device via `EncryptedCredentialStore`, and server-side for the poller. This is a deliberate departure from `establish-cloud-seam/design.md:71-80`, which had flagged server-side key storage as the cost of backend polling.
- **Unaffected:** the Gradle build, every Android source file, Room, and the XP engine. A Gradle build does not build or need `functions/`.
- **Cost:** ~43k invocations/month against a 2M free allowance; writes only on state change. Expected to round to zero, but a billing budget alert is part of this change.
- **Privacy:** the presence log is a permanent, minute-resolution record of when the user was playing, retained indefinitely in Google Cloud. Accepted deliberately — see design.
- **Deliberately deferred:** app-side backfill from the presence log, the OBS overlay, product flavors, and repository interfaces.

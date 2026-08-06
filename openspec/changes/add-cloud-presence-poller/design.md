## Context

Backlogium currently observes Steam presence only from the phone. `PresenceService` runs a 30-second foreground loop, `LiveStatusRepository` holds the shared state, and `SteamSyncWorker` reconciles roughly every 15 minutes. All three die with the process. The Steam Web API key lives on-device in `EncryptedCredentialStore`, seeded from `BuildConfig.STEAM_API_KEY`.

`establish-cloud-seam` (archived 2026-07-26) mapped two cloud shapes and deliberately shipped neither. It named the cost of the backend-polling shape plainly: the API key would have to live server-side. That objection has since been accepted by the project owner — the key will exist in both places, with the cloud poller handling presence and the app continuing to fetch library and metadata itself.

Constraints inherited from the console work already completed:
- Firestore is provisioned in `asia-southeast1`, Native mode. **This is permanent** and the function must be deployed to the same region.
- The project is on the Blaze plan.
- `STEAM_API_KEY` is already stored in Secret Manager.
- Single user, single Steam ID. There is no auth story and none is needed yet.

## Goals / Non-Goals

**Goals:**
- A resident observer that records Steam presence regardless of the phone's state.
- Durable, append-only history that a future consumer can reconstruct sessions from.
- A `current` document cheap enough for a future overlay to live-subscribe to.
- Zero change to Android behaviour. A user should not be able to tell this shipped.

**Non-Goals:**
- Computing sessions, XP, streaks, or any derived value server-side.
- Any Android code change, including reading the data this produces.
- The OBS overlay.
- Multi-user support, authentication, or client-facing security rules.
- Sub-minute presence granularity.

## Decisions

### The function writes observations, not sessions

The cloud records *what Steam said at time T*. It does not decide that a session started, ended, or lasted 90 minutes. The existing on-device engine remains the single author of sessions.

This is the principle `establish-cloud-seam/design.md:59-67` already set: "the boundary should be raw data, not computed results." Applying it here is not just consistency — it avoids a concrete failure. Two independent observers of the same presence produce two session records with disagreeing boundaries (different poll phase, different clock skew). They cannot be deduplicated by equality, and overlap-merging them is exactly the sort of logic that silently double-counts XP.

*Alternative considered:* the function detects sessions and writes them, and the app mirrors them down. Rejected — it makes the cloud authoritative for a value the app already computes correctly offline, and forces reconciliation of two session streams.

### Current state on the player document, plus an append-only `presence` subcollection

```
players/{steamId}                { v, personastate, gameid, gameName, since, updatedAt }
players/{steamId}/presence/{ISO} { v, t, personastate, gameid, gameName }
```

Current state lives on the player document's own fields. An earlier draft of this design placed it at `players/{steamId}/current`, which is not a valid document path: Firestore alternates collection and document segments, so `players/{steamId}/current` names a *collection*, and a document cannot sit directly beneath another document. Putting current state on the player document is both valid and cheaper — one read per invocation instead of navigating a subcollection.

The two shapes serve different readers. `current` is a single small document answering "what is true now" in one listener — the future overlay's entire need. `presence` is history nobody reads in real time; it is what a future backfill replays to reconstruct missed sessions.

Document IDs in `presence` are the observation timestamp in ISO-8601. This makes writes idempotent: a retried or duplicated invocation for the same minute overwrites rather than appends, so scheduler at-least-once delivery cannot inflate history.

*Alternative considered:* a single document holding a rolling array of samples. Rejected — Firestore's 1 MiB document limit turns into a hard cap on history, and every append rewrites the whole document.

### Write on change only

Each invocation reads `current`, compares the fetched presence against it, and writes only if something material differs (`personastate` or `gameid`). An unchanged poll writes nothing.

This is not primarily a cost decision — 43k writes/month would be affordable. It is what makes the `presence` subcollection a *transition log* rather than a minute-by-minute tape. A reader reconstructing sessions wants the handful of moments state changed, not 43,200 rows a month of "still playing Hades."

The read-then-compare costs one document read per invocation (~43k/month, against a 50k/day free allowance).

*Alternative considered:* keeping previous state in function instance memory to skip the read. Rejected — function instances are recycled unpredictably, so a cold start would lose the comparison baseline and emit a spurious transition.

### `since` is preserved across unchanged polls

`current.since` records when the present state began, not when it was last observed. It is carried forward untouched while state is unchanged and reset only on transition. `updatedAt` records the observation time. Without this split, a consumer cannot tell a 3-hour session from a 1-minute one.

### Every document carries a schema version

All written documents include `v: 1`. A future reader branches on that field rather than sniffing which fields happen to be present.

The cost of omitting it is real but modest: a reader can always treat an absent `v` as version 1, and `current` is a single document that could be rewritten trivially. This is a tidiness decision, not a trap being avoided — it is taken because two characters now removes a guessing game later, not because the alternative is unrecoverable.

### The presence log is retained indefinitely

No TTL policy. Documents are never expired.

The decisive fact is that **Steam does not expose historical presence.** `GetOwnedGames` reports cumulative playtime totals and a two-week rolling figure; neither says *when* those hours were played. This log is therefore the only record anywhere of the user's play timeline, and a deleted document is not recoverable from any source.

That reframes retention as a question of what the collection is. As a *buffer*, it exists only until the app ingests it, and 30 days is generous headroom. As an *archive*, it is the permanent record — and the archive reading is the one that survives the scenario that actually matters: the user reinstalls, or Room is wiped, and wants XP and streaks rebuilt from scratch. A buffer can rebuild only its window; everything older is permanently gone.

The cost side is lopsided. Write-on-change yields on the order of a few hundred documents per month — a couple of transitions per gaming session. A decade of that is tens of thousands of small documents, comfortably inside Firestore's 1 GiB free storage tier.

*Alternative considered:* a 30-day TTL. Rejected on the asymmetry — "delete it later" remains available at any time, while "undo the deletion" never does.

**Accepted trade-off:** this produces a permanent, minute-resolution record of the user's gaming activity held in Google Cloud. For a single-user personal project whose owner is also the data subject, that is acceptable. It would need revisiting before any multi-user use, where retention would become someone else's decision rather than the operator's.

### Steam ID lives in function configuration, not Firestore

Single user, one value, changes never. A configured environment value is the honest representation. Introducing a registration document implies a multi-user model this change explicitly does not have.

### Scheduled Cloud Function, one-minute cadence

Cloud Scheduler's minimum granularity is one minute, so session boundaries derived from this log are accurate to ±60s versus the app's ±30s. Accepted by the project owner.

*Alternative considered:* a Cloud Run service holding a resident sub-minute loop. Rejected for now — it trades a per-invocation cost model for an always-on one, for precision nobody has asked for.

### `functions/` lives in this repo

A Node project beside the Gradle build. The Firestore document shape is a contract between the function that writes it and the future app code that reads it; keeping both in one repo means a shape change is one commit and cannot half-land. The cost is a JS toolchain in an Android repository — `functions/` is invisible to Gradle and does not affect the app build.

### Security rules deny all client access

The poller writes via the Firebase Admin SDK, which runs on service-account credentials and bypasses security rules entirely. Locked-down rules therefore cost the poller nothing while there is no legitimate client. Test-mode rules are explicitly rejected: they expire after 30 days and fail silently long after the choice is forgotten.

## Risks / Trade-offs

- **A private or friends-only Steam profile returns no `gameextrainfo`** → The poller records presence it cannot attribute to a game, producing a useless log. Profile visibility must be verified as public before deployment, and the function should log distinguishably when `personastate` indicates online but game fields are absent.

- **The Steam API key now exists in two places** → Broader exposure surface than the on-device-only posture `establish-cloud-seam` protected. Mitigated by Secret Manager (never in source, never in function environment config, not in git history) and by the key being personal and revocable from Steam at any time.

- **Nothing consumes this data, so breakage is silent** → A poller that stops has no user-visible symptom. Mitigated by `current.updatedAt`: a staleness check against it is the single health signal, and is the natural first thing a future consumer asserts on.

- **Steam Web API transient failures** → A failed fetch must leave `current` untouched rather than writing an "offline" observation. Inferring offline from an API error would fabricate session ends.

- **The `presence` log grows without bound** → Accepted by design, because the history is unrecoverable and the storage is effectively free. The residual risk is a bug that defeats write-on-change and appends every minute, turning a few hundred documents a month into 43,200. Mitigated by the billing budget alert and by task 7.2, which verifies unchanged polls write nothing.

- **Clock source** → Observation timestamps come from the function's own clock at fetch time, not from Firestore server timestamps, so the recorded time reflects when Steam was asked. Scheduler jitter means invocations are not exactly 60s apart; consumers must not assume a fixed interval.

## Migration Plan

There is nothing to migrate — no existing data, no existing readers, no app change. Deployment is additive and rollback is deletion of the scheduled function, which returns the system to its present state with an orphaned Firestore collection that costs nothing.

Sequence: deploy with the schedule disabled or at a slow cadence, confirm one write lands with the expected shape, then enable the one-minute schedule and confirm a real state transition (start a game, stop it) produces exactly two `presence` documents and a correctly-moved `since`.

## Open Questions

Both prior open questions are now resolved above: documents carry `v: 1`, and the presence log is retained indefinitely.

- Should the health of the poller be surfaced anywhere, or is checking `current.updatedAt` by hand sufficient until a consumer exists? A silent stall is the most likely failure and currently nothing would report it.

## Why

The presence log is a durable record of *when* the user was playing, and nothing can read it. Firestore rules deny all client access, the poller's only health signal is a Cloud Logging line, and the app has no cloud identity of any kind.

Reading it matters because of what the phone actually loses while it is off. `playtime_forever` is cumulative, so `SteamSyncWorker` recovers every lost minute on the next sync — but it recovers them as one session, attributed entirely to the day the sync ran. Three days of play collapse into one 72-hour row, three `DailyProgress` days stay at zero, and the streak breaks over play that genuinely happened. The magnitude is never lost; the *attribution* always is, and the presence log is the only record anywhere that could restore it.

Before an app can use that, two things have to exist that do not: a way to read the log without opening Firestore to clients, and a way to tell "the user played for twelve hours" apart from "the poller was down for ten of them." This change builds both. It ships no Android code.

## What Changes

- Add an HTTPS Cloud Function exposing a read-only gateway over the presence log, deployed alongside the poller in `asia-southeast1`.
- Guard it with a bearer token held in Secret Manager, generated per deployment. Reject unauthenticated requests without touching Firestore.
- Expose `GET /health` — schema version, the polled Steam ID, last observation time, last poll time, and the coverage horizon. This is the whole compliance probe: one round trip tells a client whether the endpoint is real, current, and *theirs*.
- Expose `GET /history?since=&limit=` — the raw transitions in the window plus the poller's coverage over that same window, so shape and trustworthiness arrive together and cannot disagree.
- Return raw recorded documents only. The gateway computes no interval, duration, or session. Deliberate bright line — see design.
- Have the poller write hourly coverage records to `players/{steamId}/coverage/{yyyy-MM-ddTHH}`, incremented on each poll that completed a successful Steam fetch.
- **Firestore security rules are unchanged and remain deny-all.** Clients still do not read Firestore; a service-account-backed function reads on their behalf.
- **No Android code changes.** Nothing in the app calls this yet.

## Capabilities

### New Capabilities

- `cloud-presence-gateway`: A token-guarded, read-only HTTPS interface over the recorded presence log and poller coverage, so a client can retrieve history and verify the deployment without direct Firestore access.

### Modified Capabilities

- `cloud-presence-poller`: Gains a requirement to record hourly poll coverage. The existing "Write on game change only" requirement is narrowed in scope — it governs the presence log and the current-state document, and explicitly does not govern the coverage collection.

## Impact

- **New:** an HTTPS function in `functions/src/`, its route handlers, and a `BACKLOGIUM_READ_TOKEN` secret in Secret Manager.
- **Modified:** the poller's write path gains a coverage increment; `functions/README.md` gains gateway deployment, token generation, and rotation steps.
- **Unchanged:** `firestore.rules` (still deny-all), the presence log's shape, the current-state document's shape, the write-on-change rule for both, indefinite retention, and every Android source file. A Gradle build is unaffected.
- **Invariant amended:** CLAUDE.md records "Writes happen only when `gameid` changes" as a load-bearing constraint. Its scope narrows to the presence log and current-state document. The reasoning behind it — keeping the transition log free of idle churn — is untouched; coverage is a separate collection serving a different reader.
- **Cost:** ~1,440 coverage writes/day against a 20k/day free allowance, plus roughly one gateway invocation per app sync. Storage is 24 documents/day, 8,760/year.
- **New exposure surface:** an internet-reachable endpoint over a permanent record of when the user is at home playing games. Guarded by a 256-bit token and nothing else — there is no user account model here and this change does not introduce one.
- **Deliberately deferred:** all app-side work. Reading, ingestion, session provenance, retroactive day repair, and the settings surface are the next change (`cloud-presence-ingest`), which this one exists to unblock.

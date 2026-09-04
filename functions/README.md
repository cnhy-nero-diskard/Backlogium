# Backlogium — cloud presence poller

A scheduled Cloud Function that asks Steam what you are playing, once a minute,
and writes down what it sees. It exists because the phone cannot: `PresenceService`
and `SteamSyncWorker` stop when Android sleeps or kills them, so sessions played
while the phone is unreachable are never observed.

This directory is invisible to Gradle. It is not part of the Android build.

## Status

The cloud writer is implemented in this repository as a Node 22 / TypeScript
Firebase scheduled function. The repository proves the source and deployment
configuration, not the health of a particular live deployment. It is intentionally
not an app backend yet: the Android client has no reader for this data and
`firestore.rules` denies client access until a narrowly scoped consumer is ready.

## What it records

```
players/{steamId}                 current state — the document's own fields
  { v, personastate, gameid, gameName, since, updatedAt, lastObservedAt }

players/{steamId}/presence/{ISO}  append-only transition log
  { v, t, personastate, gameid, gameName }
```

Two things worth understanding before changing anything here:

- **It records observations, not sessions.** No duration, playtime, streak, or XP
  is computed server-side. The on-device engine remains the single author of
  those. Two independent session detectors would disagree on boundaries in ways
  that cannot be deduplicated.
- **History is never deleted.** Steam exposes no historical presence — this log
  is the only record anywhere of *when* you played, and an expired document is
  unrecoverable from any source. There is deliberately no TTL policy.

Transition history writes happen only when the game changes. Every successful poll
also advances `lastObservedAt` on the current-state document; an unchanged poll
refreshes the raw persona/game-name fields but does not append a presence entry or
reset the transition timestamps. This keeps the log a record of transitions rather
than 43,200 rows a month of "still playing Hades".

## Setup

The Steam API key lives in Secret Manager, never in this directory:

```bash
firebase functions:secrets:set STEAM_API_KEY --project <project-id>
```

The Steam ID is configuration, not a secret:

```bash
cp .env.example .env    # then fill in STEAM_ID
```

## Deploy

```bash
npm run build
firebase deploy --only functions
```

The function must stay in `asia-southeast1` to match the Firestore database,
whose location is permanent.

## Logs

```bash
npm run logs
# or: firebase functions:log --only pollPresence
```

Log lines worth recognising:

| Message | Meaning |
|---|---|
| `poll ok` | Liveness heartbeat — one per minute. Absence means something is broken. |
| `Recorded presence transition` | Normal. A state change was written. |
| `Profile is not public` | Game attribution unavailable — fix profile visibility, or the log is useless. |
| `Steam returned no player...` | Wrong Steam ID, or the profile is unreachable by this key. |
| `Steam request failed` / `error status` | Transient. Stored state deliberately untouched. |

An unchanged poll logs the `poll ok` heartbeat, refreshes current-state metadata, and
does not append a presence transition.

### What logs do and do not contain

Log output has no access control — Firestore denies client reads and the poller
writes through the Admin SDK, but Cloud Logging is readable by anyone with
log-viewer access, any configured sink, and any tool downstream of a sink.
Operational logs therefore never carry the configured Steam ID, a played
game's app ID, or its name. Every log call passes through `src/safeLog.ts`,
the single component that owns this rule, so a call site cannot reintroduce
the leak by being written somewhere new.

Verify the boundary with the same grep-must-be-silent pattern `CLAUDE.md` uses
for the haptics authority:

```bash
grep -rn "firebase-functions/logger" functions/src/ --exclude=safeLog.ts --exclude="*.test.ts"
```

## Rotating the Steam API key

```bash
firebase functions:secrets:set STEAM_API_KEY --project <project-id>
firebase deploy --only functions            # required — picks up the new version
```

Old secret versions linger; prune them with
`firebase functions:secrets:prune --project <project-id>`.

## Health

Nothing consumes this data yet, so a stalled poller has no user-visible symptom.
The signal is the `poll ok` log line, emitted once per minute after a successful
Steam fetch *and* a successful Firestore interaction. A metric-absence alert on
it is the monitoring hook.

A `Metric absence` policy on the `presence_poll_ok` log-based metric, set to 15
minutes, notifies in **roughly 23–25 minutes** in practice: the threshold, plus
log ingestion, plus the evaluation interval. That lag is inherent to alerting on
log-based metrics — the log must be ingested and counted before the metric can
be observed as absent. Verified 2026-08-07 by pausing the scheduler.

Shortening the threshold does not shorten the response proportionally; it mostly
raises the odds of a false alarm during scheduler jitter.

Do not substitute the cheaper signals — both are blind to the likeliest failure:

- **Invocation count** stays at a perfect 1,440/day if the Steam API key is
  revoked, because the function still runs and still returns 200.
- **`players/{steamId}.lastObservedAt`** advances after a successful poll, but a
  log-based absence alert on `poll ok` is cheaper and more direct than polling
  Firestore and interpreting timestamps.

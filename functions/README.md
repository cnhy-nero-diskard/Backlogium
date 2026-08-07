# Backlogium — cloud presence poller

A scheduled Cloud Function that asks Steam what you are playing, once a minute,
and writes down what it sees. It exists because the phone cannot: `PresenceService`
and `SteamSyncWorker` stop when Android sleeps or kills them, so sessions played
while the phone is unreachable are never observed.

This directory is invisible to Gradle. It is not part of the Android build.

## What it records

```
players/{steamId}                 current state — the document's own fields
  { v, personastate, gameid, gameName, since, updatedAt }

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

Writes happen only when presence state or the game changes. An unchanged poll
writes nothing, which is what keeps the log a record of transitions rather than
43,200 rows a month of "still playing Hades".

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

Silence is normal: an unchanged poll logs nothing and writes nothing.

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

Do not substitute the cheaper signals — both are blind to the likeliest failure:

- **Invocation count** stays at a perfect 1,440/day if the Steam API key is
  revoked, because the function still runs and still returns 200.
- **`players/{steamId}.updatedAt`** only advances on a game change, so a healthy
  idle poller looks identical to a dead one.

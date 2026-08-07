## 1. Prerequisites

- [ ] 1.1 Generate a 256-bit random token (base64url) and record it where the app's future settings entry can be pasted from
- [ ] 1.2 Store it as `BACKLOGIUM_READ_TOKEN` via `firebase functions:secrets:set`; never in source, never in function environment config
- [ ] 1.3 Confirm the existing billing budget alert still covers the added invocations, and note the gateway is unrate-limited by design
- [ ] 1.4 Confirm `firestore.rules` is deny-all and record that this change does not modify it

## 2. Coverage writer (poller side)

- [ ] 2.1 Add a coverage module writing `players/{steamId}/coverage/{yyyy-MM-ddTHH}` keyed by the observation hour in UTC
- [ ] 2.2 Increment the poll counter with `FieldValue.increment(1)`, creating the document on first write for the hour
- [ ] 2.3 Stamp `v: 1` on the coverage document at creation
- [ ] 2.4 Record no game ID, persona state, or derived value on the coverage document
- [ ] 2.5 Call the coverage write from `index.ts` only after `fetchPresence` returned an observation, and independently of whether `recordObservation` wrote anything
- [ ] 2.6 Leave the coverage write out of the batch that writes presence, so an unchanged poll still performs no presence write
- [ ] 2.7 Keep the coverage failure path from suppressing the `poll ok` heartbeat's existing meaning — decide and document whether a coverage failure suppresses it

## 3. Gateway scaffolding

- [ ] 3.1 Add an HTTPS function exported alongside `pollPresence`, deployed to `asia-southeast1`
- [ ] 3.2 Declare `BACKLOGIUM_READ_TOKEN` as a secret binding on the gateway only, not on the poller
- [ ] 3.3 Reject any method other than `GET` and `HEAD` with 405, before authorization
- [ ] 3.4 Parse the `Authorization: Bearer` header and compare against the secret with `crypto.timingSafeEqual` on equal-length buffers
- [ ] 3.5 Return 401 with an identical body for missing, malformed, and incorrect tokens
- [ ] 3.6 Issue no Firestore read on any unauthorized path
- [ ] 3.7 Route `/health` and `/history`; return 404 for anything else

## 4. Health route

- [ ] 4.1 Return the served schema version
- [ ] 4.2 Return the configured Steam ID, read from the same `STEAM_ID` function parameter the poller uses
- [ ] 4.3 Return the most recent observation time from the current-state document, or null when no document exists
- [ ] 4.4 Return the most recent poll time, derived from the newest coverage bucket, or null when none exists
- [ ] 4.5 Return the coverage horizon — the earliest coverage bucket — or null when none exists
- [ ] 4.6 Succeed on a deployment where no presence document and no coverage document have ever been written

## 5. History route

- [ ] 5.1 Accept `since` as an ISO-8601 timestamp; return 400 when absent or unparseable
- [ ] 5.2 Query `players/{steamId}/presence` ordered ascending by observation time, starting at `since`
- [ ] 5.3 Apply a server-side cap on returned transitions, with `limit` able to lower it but not raise it
- [ ] 5.4 Return an explicit truncation flag and, when truncated, the bound to resume from
- [ ] 5.5 Verify a resumed request skips and repeats nothing across the boundary
- [ ] 5.6 Return coverage documents spanning the same window as the returned transitions, in the same response
- [ ] 5.7 Report hours with no coverage document explicitly as uncovered rather than omitting them
- [ ] 5.8 Omit any stored document whose `v` differs from the served version, and log the omission
- [ ] 5.9 Return only stored document fields — no interval, duration, session, playtime, or experience value
- [ ] 5.10 Return an empty transition list, successfully, for a window containing none

## 6. Verification

- [ ] 6.1 `npm --prefix functions run build` passes
- [ ] 6.2 Deploy coverage alone; confirm buckets appear and increment once per minute
- [ ] 6.3 Confirm an unchanged poll still writes nothing to `presence` and nothing to the player document
- [ ] 6.4 Deploy the gateway; confirm 401 with no token and with a wrong token, and that neither issues a Firestore read
- [ ] 6.5 Confirm `/health` reports the expected Steam ID, a recent poll time, and a coverage horizon
- [ ] 6.6 Confirm `/history` returns known transitions in ascending order with coverage attached
- [ ] 6.7 Pause the scheduler for an hour, resume, and confirm the gap is visible as missing or low-count coverage
- [ ] 6.8 Confirm a Gradle build is still unaffected by `functions/`

## 7. Documentation

- [ ] 7.1 Extend `functions/README.md` with gateway deployment, token generation, rotation, and example `curl` invocations
- [ ] 7.2 Document in `functions/README.md` that rotation invalidates any configured client with no signal beyond a 401
- [ ] 7.3 Amend the CLAUDE.md invariant "Writes happen only when `gameid` changes" to state that it governs the presence log and current-state document, and that coverage is exempt
- [ ] 7.4 Note in CLAUDE.md that Firestore rules remain deny-all and that the gateway is the only read path

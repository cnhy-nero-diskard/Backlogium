## Context

`add-cloud-presence-poller` (archived 2026-08-07) stood up a resident observer and deliberately shipped no reader. Its non-goals listed "any Android code change, including reading the data this produces," and its risk list named the consequence plainly: "Nothing consumes this data, so breakage is silent."

This change builds the reader's half of the seam — the cloud side only. It exists because of a distinction the app-side design turns on:

**The phone loses attribution, not magnitude.** `SteamSyncWorker.persistPoll` derives sessions by diffing `playtime_forever` (`app/src/main/java/com/example/backlogium/work/SteamSyncWorker.kt:151-158`). That figure is cumulative, so every minute played while the phone was off is recovered on the next sync. What is *not* recovered is when it happened: `SessionDiffer.diff` opens one session spanning `previousPollAt → now`, and `DailyProgress` attributes the entire delta to the day the sync ran (`SteamSyncWorker.kt:186-192`). Three days offline become one 72-hour session, three days of zeroes, and a broken streak over play that genuinely occurred.

The presence log is the only record anywhere that can restore the timeline — Steam exposes no historical presence, which is the same fact that justified indefinite retention. Two things must exist before an app can use it, and neither does.

Constraints inherited:

- Firestore is in `asia-southeast1`, Native mode, permanent. Functions deploy to the same region.
- `firestore.rules` denies all client access. CLAUDE.md records that "any future client reader needs an auth decision first." This change is that decision.
- Single user, single Steam ID, no auth model. The poller reads its Steam ID from function config, not Firestore.
- Decided with the project owner during exploration: the app is **read-only** against the cloud, and cloud ingestion into Room is a **one-way import** with no user-facing undo.

That last constraint is what makes coverage load-bearing rather than merely tidy. A misread window under a reversible import is an annoyance; under a permanent one it is corruption the user lives with.

## Goals / Non-Goals

**Goals:**

- A client can read the presence log without Firestore rules being opened.
- A client can determine, in one round trip, whether an endpoint is reachable, current, schema-compatible, and recording *its own* Steam ID.
- A client can tell an unobserved window apart from a long play session.
- The Firestore document shapes and the write-on-change rule are unchanged.
- Zero Android change. Nothing in the app calls this yet.

**Non-Goals:**

- Any app-side code: reading, ingestion, session provenance, retroactive repair, settings UI.
- Multi-user support, user accounts, or per-device credentials.
- Realtime subscription. This is a batch history reader.
- The OBS overlay.
- Any server-side derivation — intervals, sessions, durations, totals.

## Decisions

### A gateway function, not an opened Firestore rule

Clients never touch Firestore. An HTTPS function reads on their behalf through the Admin SDK, and `firestore.rules` stays deny-all.

*Why:* the app then needs no Firebase SDK, no `google-services.json`, and no compiled-in cloud identity. Its entire configuration is two runtime strings — an endpoint and a token — which is what makes "point Backlogium at your own deployment" fall out for free rather than becoming a feature. It also makes the contract a *versioned API* rather than a *document shape*: the poller's Firestore layout can change without breaking an installed app, which matters because the app is sideloaded and cannot be force-updated.

*Alternative considered — a narrow read rule plus Firebase Anonymous Auth.* Rejected on three counts. It requires the Firebase SDK (or hand-rolled Identity Toolkit REST token minting) in an app that currently has zero cloud dependencies. It requires `google-services.json`, which bakes a cloud identity in at build time and destroys the runtime-configuration property above. And pinning a rule to an anonymous UID is circular: the UID does not exist until after the first sign-in, so setup becomes "install, sign in, copy your UID out of the app, paste it into your security rules, redeploy."

*Alternative considered — public read on the presence path.* Rejected. This is a permanent, minute-resolution record of when a specific person is at home playing games.

*Cost accepted:* one more deployed surface that can break, and a bearer token living on-device. The token goes in `EncryptedCredentialStore`, which already holds the Steam key — no new secret-handling pattern.

### One shared bearer token, in Secret Manager

A single 256-bit token, generated per deployment, stored as `BACKLOGIUM_READ_TOKEN`, compared in constant time, checked before any Firestore read is issued.

*Why not per-device credentials:* one user, one device. A credential model with issuance, listing, and revocation is unearned structure for a population of one. Revocation here is rotate-and-redeploy — the same operation the Steam key already requires, and already documented in `functions/README.md`.

*Why constant-time comparison:* the endpoint is internet-reachable and the token is the only thing in front of it. A timing side channel on a naive `===` is cheap to avoid and expensive to discover later.

*Why reject before reading Firestore:* an unauthenticated flood should cost invocations, not reads. It also means a wrong token can never be distinguished from a right one by response latency.

### The gateway derives nothing — a bright line, not a preference

It returns stored document fields. It does not pair transitions into intervals, does not compute durations, does not emit sessions.

This is worth stating as a requirement rather than leaving to taste, because pairing consecutive transitions is about four lines of code and will look like an obvious convenience to whoever next touches this file. The moment it emits a duration, the cloud is deriving, and `add-cloud-presence-poller/design.md:34` describes exactly what that costs: two independent observers producing session records with disagreeing boundaries, which cannot be deduplicated by equality and whose overlap-merging silently double-counts XP. The on-device engine stays the sole author.

*Alternative considered — return intervals, since every consumer wants them.* Rejected. It is true that every consumer wants them, and that is the trap: the second consumer would inherit the first consumer's pairing rule as a server-side fact rather than choosing its own. Pairing is cheap on-device and the rules are subtle (see the trailing-open-interval handling deferred to `cloud-presence-ingest`).

### Coverage as hourly counters

`players/{steamId}/coverage/{yyyy-MM-ddTHH}`, an integer incremented on each poll that completed a successful Steam fetch, whether or not that poll wrote presence.

*Why it is necessary at all:* the presence log's central property — write on game change only — makes silence ambiguous by construction. A healthy poller with an idle user and a dead poller produce byte-identical output: nothing. The existing liveness signal is a Cloud Logging line (`cloud-presence-poller/spec.md:151`), chosen over Firestore explicitly because there was no reader and logs were cheaper. That premise is now gone. A client cannot read Cloud Logging, and the failure this protects against is concrete:

```
poller dies 20:00, returns 08:00, user played 2h of the intervening 12

log says:      gameid=Hades @20:00 ──────────────► gameid=null @08:00
reader infers: one 12-hour Hades session
reality:       2 hours, then the function stopped
```

Under a one-way import, that is permanent.

*Why hourly buckets:* a single `lastPollAt` document answers "is it alive now," which is the setup question, not the backfill question. Backfill asks "was it alive last Tuesday at 21:00," which needs history. Hourly gives ±1h resolution on gap detection, comfortably inside the tolerance of a feature whose output is day attribution. It costs 24 documents/day, 8,760/year.

*Alternative considered — one document per poll.* Rejected: 1,440 documents/day and 525k/year for resolution nobody needs.

*Alternative considered — keep the heartbeat in logs and let the app clamp against the Steam delta instead.* The clamp is happening regardless and does bound the damage: if the cloud shape claims 720 minutes and Steam's delta is 120, the app scales down. But the clamp only fixes the *total*; it still smears 120 real minutes across a 12-hour span on the wrong days, which is precisely the attribution this whole effort exists to get right. Coverage lets the app skip the window instead of guessing at it.

*Cost:* ~1,440 writes/day against a 20k/day free allowance.

### Coverage is written only after a successful Steam fetch

A poll whose Steam request failed obtained no information. Recording it as coverage would assert observation where there was none — the same error as recording a failed fetch as "offline," which the poller already refuses to do.

*Consequence:* an hour during which Steam itself was down shows partial coverage, and the app treats it as partially unobserved. That is the honest reading.

*Accepted imprecision:* the counter is a `FieldValue.increment(1)` and is therefore not idempotent under redelivery. The scheduler is configured `retryCount: 0`, and the value is consumed as a threshold ("was this hour substantially covered?") rather than an exact figure, so a double-count changes nothing a reader concludes.

### Coverage begins when this ships, and the horizon is published

The presence log already contains history predating any coverage record. Those windows are not merely uncovered — they are *unknowable*, and a reader must not read an absent coverage document from before deployment as evidence of an outage.

`/health` therefore reports the earliest coverage bucket. Everything before it is a distinct third state: not covered, not proven uncovered, simply outside the record. What the app does with that window is `cloud-presence-ingest`'s decision, but it cannot make one without being told where the horizon is.

### History and coverage arrive in one response

`GET /history?since=&limit=` returns both the transitions in the window and the coverage spanning that same window.

*Why together:* two round trips can observe two different windows. A consumer that fetched transitions, then fetched coverage a second later, could hold a transition whose hour has no coverage entry yet — and conclude "outage" about the poll that is running right now. Serving both from one request makes that inconsistency unrepresentable.

*Why not fold in `/health`:* health is asked at setup and on a schedule to detect staleness; history is asked during ingestion. Different cadences, different failure handling.

### Truncation is explicit, with a cursor

A cap on transitions per response, an explicit truncated flag, and the bound to resume from.

*Why, given the volume is a few hundred documents a month:* the first ingest after a long absence, or a future rebuild-from-archive over years of history, is exactly when an unbounded response is worst — and exactly when nobody is watching. The flag matters more than the cap: a consumer must never infer completeness from a short list.

### Unrecognized schema versions are omitted, not passed through

The gateway serves one schema version and drops stored documents carrying another, logging the omission.

*Why not pass them through and let the client decide:* the client's version check would then have to be per-document rather than per-response, and the natural client implementation — check the response version, trust the contents — would silently misread. Failing at the boundary that knows the shape is cheaper than failing at the one that does not.

### No Android code in this change

Same discipline the poller change held. The app is untouched, so this ships with zero user-visible risk and can be verified entirely with `curl`.

## Risks / Trade-offs

- **An internet-reachable endpoint now fronts a permanent record of when the user is at home** → Guarded by a 256-bit token and nothing else; there is no account model and this change does not introduce one. Mitigated by constant-time comparison, rejection before any Firestore read, and rotation being a documented operation. The residual risk is a leaked token, whose blast radius is read access to presence history — real, but bounded, and revocable by rotating the secret.

- **Token brute force costs invocations rather than data** → 256 bits is not guessable, but an attacker who finds the URL can still burn function invocations. The existing billing budget alert is the backstop; a rate limit is deliberately not built, since one alert is cheaper than infrastructure for a threat nobody has.

- **The gateway is a tempting place to add derivation** → Mitigated by making "derives nothing" a spec requirement with its own scenarios, so a future change that pairs transitions server-side has to explicitly delete a requirement rather than quietly add a helper.

- **Coverage buckets are UTC hours; day attribution is local** → An hour bucket can straddle a local midnight. Only relevant at ±1h around the boundary, and only when the poller was partially down in that hour. Accepted; the consumer's day-splitting rules are its own problem and are deferred.

- **Coverage cannot describe the past** → Windows before deployment are permanently unknowable. Mitigated by publishing the horizon in `/health` so a consumer can refuse rather than assume.

- **The gateway is in `asia-southeast1` regardless of where the client is** → Adds round-trip latency for a distant client. Irrelevant for a background backfill; would matter for a realtime overlay, which is not this.

- **Two functions now share one Firestore and one deploy** → A broken gateway deploy could in principle disturb the poller. Mitigated by them being separate exported functions with no shared runtime state, and by `firebase deploy --only functions:pollPresence` remaining available for an isolated redeploy.

## Migration Plan

Additive. No existing data changes shape, no existing reader exists, no app change ships.

Sequence:

1. Generate the token and set `BACKLOGIUM_READ_TOKEN` in Secret Manager.
2. Deploy the coverage write first, on its own. Confirm buckets appear and increment once per minute, and that presence writes are unchanged — an unchanged poll must still write nothing to `presence` or the player document.
3. Deploy the gateway. Verify with `curl`: unauthorized without a token, unauthorized with a wrong token, health reports the expected Steam ID and a coverage horizon, history returns known transitions in order with coverage attached.
4. Verify a deliberate outage is visible: pause the scheduler for an hour, resume, confirm the gap appears as missing or low-count coverage.

Rollback: delete the gateway function. Coverage writes can be left in place harmlessly, or removed in a follow-up. Neither touches the presence log, and the system returns to its present state.

## Open Questions

- **Should the app ever be allowed to ingest pre-horizon windows?** Refusing is the safe default and what `cloud-presence-ingest` will likely start with, but it means the several weeks of presence history already recorded are unusable — which is a real loss given the log exists precisely because history is unrecoverable. A user-confirmed "import it anyway, unverified" is defensible for a single-user app where the operator is the data subject. Deferred to the consumer, but flagged here because the data is accumulating now.

- **Is hourly coverage resolution enough once sessions are attributed to days?** A session starting at 23:40 sits inside a bucket that could be half-covered. Suspected yes, since the clamp against the Steam delta catches gross errors independently, but only real ingestion will show whether the boundary cases matter.

- **Token rotation ergonomics.** Rotating invalidates the app's stored token with no signal beyond a 401, and the app has no push channel. The health probe surfacing "unauthorized" is probably sufficient, but the failure mode — silent ingestion stoppage after a rotation the user forgot about — is the same class of silent stall the poller's heartbeat exists to catch.

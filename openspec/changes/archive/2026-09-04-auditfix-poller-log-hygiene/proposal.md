## Why

**Branch: `fix/auditfix-poller-log-hygiene`**

The security audit (#118) found that the deployed Cloud Function writes the player's Steam
identity and currently-played title into Cloud Logging in plaintext. Firestore rules deny
all client access (`firestore.rules`: `allow read, write: if false;`) and the poller writes
via the Admin SDK — so the log is a **second copy of activity metadata living entirely
outside that boundary**, readable by anyone with Cloud Logging access, any log sink, or any
downstream tool a sink feeds. Log retention and exports can also outlive the Firestore state
that produced them.

Confirmed sites, all `functions/src/`, none gated by a debug flag because Functions has no
equivalent of the Android `BuildConfig.DEBUG` guard that keeps Timber quiet in release:

| Site | Leaks | Frequency |
|---|---|---|
| `index.ts:71` `logger.info("poll ok", { outcome, gameid })` | played title's app ID | **every successful poll — 1,440/day** |
| `presence.ts:165-170` `logger.info("Recorded presence transition", …)` | `steamId`, `gameid`, `gameName` | every game change |
| `steam.ts:109-112` unknown-player error | `steamId` | on misconfiguration |

`index.ts:71` is not in the audit's list but is the same class and by far the highest volume:
it is the required liveness heartbeat (`cloud-presence-poller/spec.md:255-270`), so it fires
once a minute forever and currently carries the app ID of whatever is being played. Correlating
that stream with its timestamps reconstructs a play history without touching Firestore at all.

The remaining `logger` calls were checked and are clean — they carry exception strings, HTTP
status codes, and `communityvisibilitystate`, none of which identify an account or a title.
`steam.ts:66` already documents the sharpest version of this rule (`NB: never log url — it
carries the API key`); the finding is that the rule was never generalized past the key.

The audit's second finding — that two public commits attribute several hundred Steam app IDs
to the maintainer's personal library — is a disclosure *decision*, not a defect. It is
recorded in `design.md` Decision 3 and carried as a task requiring an explicit answer, not
silently remediated.

## What Changes

- **One place decides what may be logged.** A small helper in `functions/src/` owns the
  redaction rule, so a future log line cannot re-leak by being written somewhere new. This
  mirrors how `ui/util/Haptics.kt` is the single authority for haptics on the app side.
- **`index.ts:71` heartbeat keeps its monitoring value and loses `gameid`.** The heartbeat
  exists so that *absence* is alertable; a monitoring policy needs the entry to exist, not to
  know what was played. `outcome` stays — it distinguishes `written` from `unchanged` without
  naming a title.
- **`presence.ts` transition log drops `steamId`, `gameid`, and `gameName`,** keeping
  `first` and the outcome. What was played is already in Firestore, which is the boundary
  that is actually access-controlled; the log does not need a shadow copy.
- **`steam.ts` unknown-player error stops echoing the configured `steamId`.** The message
  already tells the operator which setting to check; repeating the value adds nothing
  diagnostically and is the one line that pairs an identity with an error state.
- **A pseudonymous account token is deliberately NOT introduced.** See `design.md`
  Decision 2 — this deployment polls exactly one configured account, so a stable token would
  be a distinction without a difference while restoring the correlation handle.
- Spec-level: `cloud-presence-poller` gains an explicit requirement that operational logs
  carry no account or title identity, and the heartbeat and failure requirements are narrowed
  so a future implementer cannot satisfy them by logging identity again.

**No behavioural change to what is recorded.** Firestore documents are untouched — same
fields, same schema version, same transition semantics. Only what reaches Cloud Logging
changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cloud-presence-poller`: operational logs must not carry account or title identity; the
  liveness heartbeat and the failure-path log obligations are narrowed accordingly

## Impact

| Path | Change |
|---|---|
| `functions/src/index.ts` | heartbeat drops `gameid` |
| `functions/src/presence.ts` | transition log drops `steamId`, `gameid`, `gameName` |
| `functions/src/steam.ts` | unknown-player error drops `steamId` |
| `functions/src/` (new) | single redaction authority for log payloads |
| `functions/README.md` | operator note on what logs do and do not contain |
| `openspec/specs/cloud-presence-poller/spec.md` | one requirement added, two narrowed |

**Fully independent of every other audit-fix change.** `functions/` is its own npm/TypeScript
toolchain (Node 22) and is invisible to Gradle — per `CLAUDE.md`, a Gradle build neither needs
nor touches it. This change shares no file, no module, and no test suite with the other six,
so it can land at any point in the sequence. It is placed early only because the disclosure is
ongoing at 1,440 log entries a day.

**Deploy constraint carried over, not introduced**: Firestore lives in `asia-southeast1`
permanently and the function must be deployed to the same region. This change alters no
region, schedule, secret binding, or retention setting. It specifically does **not** add a TTL
policy — `CLAUDE.md` and `cloud-presence-poller/spec.md:226` forbid one, because Steam exposes
no historical presence and a deleted document is unrecoverable.

**Requires a deploy to take effect.** Redaction lives in the deployed function, so the leak
continues until `firebase deploy --only functions` runs. The spec change alone changes nothing.

**Not addressed here**: historical Cloud Logging entries already written, and the git-history
attribution finding. Both need an explicit decision from the maintainer rather than a default
(tasks 5.1 and 5.2). No history rewrite is proposed.

# Read cloud presence

*Phase 2 of the cloud poller integration. Phase 1 is `record-presence-coverage`.*

## Why

The poller has recorded presence since August and nothing reads it. Every planned consumer —
recovering family-shared play, re-filing misattributed days — needs the same three things first: a
way to get the data onto the phone, a way to turn transitions into intervals, and evidence that the
result is worth acting on.

Building those inside the first feature that needs them would mean committing to an allocation rule
before anyone has seen how far the cloud's account of a week diverges from the app's. This phase
gets the data onto the phone and puts the two accounts side by side, while writing nothing that
could be wrong.

## What Changes

- **A read endpoint** in `functions/`, authenticated by a bearer token from Secret Manager, serving
  a windowed slice of the transition log plus current state. Firestore rules stay `deny all` —
  there is still no client SDK.
- **A cloud section in Settings**: endpoint URL and token, verified against the endpoint before
  being accepted, stored Keystore-encrypted beside the Steam key. Absent until configured.
- **Account assertion.** The endpoint states which Steam ID it polls; a mismatch against the app's
  configured account is refused, never reconciled.
- **A pure reconstruction** turning transitions into intervals, each carrying what is known about
  whether it was observed continuously — the coverage phase 1 records.
- **A diagnostics surface** showing that reconstruction next to what the local session ledger holds
  for the same window, so the disagreement is measurable rather than assumed.
- **Nothing is written to the session ledger.** No session, no daily progress, no recompute, no
  progress event. This phase is read-only by construction.

## Capabilities

### New Capabilities

- `cloud-presence-reader`: how recorded presence reaches the device — authentication, account
  assertion, windowing and resumption, credential storage, the interval reconstruction and its
  coverage contract, and the rule that the feature is wholly absent until configured.

### Modified Capabilities

- `app-settings`: a cloud presence section — configure, verify, view last successful read, disable.
- `app-diagnostics`: cloud read outcomes are recorded, and the reconstructed timeline is presented
  against the local ledger for the same window.

## Impact

- **`functions/`** — a second function beside `pollPresence`, sharing `safeLog` and the existing
  region. Bounded `maxInstances`; unauthenticated requests rejected before any Firestore read.
- **`app/data/`** — a Retrofit client and a repository returning domain models; the token joins the
  existing encrypted credential store.
- **`app/domain/`** — the reconstruction, pure and Room-free, beside `SessionDiffer` and
  `PresenceSessionDeriver`. Phases 3 and 4 consume it unchanged.
- **`app/ui/diagnostics/`** — one surface, under the package's documented exception.
- **No new Android dependency.** Retrofit, OkHttp and kotlinx.serialization are already present; no
  Firebase SDK, no `google-services.json`, no Gradle plugin, so the `.debug` application id suffix
  stays irrelevant.
- **No Firestore rules change.** `allow read, write: if false` still holds.

## Non-goals

- **Writing anything derived.** Sessions, daily progress and recomputes belong to phases 3 and 4.
- **Live now-playing from the cloud.** The app keeps resolving presence itself; replacing that is a
  separate decision with its own spec.
- **Choosing an allocation rule for phase 4.** This phase exists partly to inform that choice.
- **Multi-account or multi-device support.** One configured account, asserted, or nothing.

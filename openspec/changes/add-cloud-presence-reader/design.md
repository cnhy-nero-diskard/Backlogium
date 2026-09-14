## Context

See proposal.md — Why.

The constraints that shape this phase come from four places already settled:

- **`establish-cloud-seam`** decided that when cloud sync arrived, the boundary would be raw data
  rather than computed results, and that repositories would return domain models so a second source
  could satisfy the contract. That work is done; this is the first change to use it.
- **`add-cloud-presence-poller`** left `firestore.rules` denying everything, with a note that a
  client reader needs a narrowly scoped rule — or, as chosen here, no client at all.
- **`record-presence-coverage`** (phase 1) gives transitions a record of whether the interval they
  close was observed throughout. Without it the reconstruction below could only ever say "unknown".
- **`onboarding-credentials`** already established the pattern this feature's credential follows:
  verified before accepted, Keystore-encrypted, masked on display, `BuildConfig` as a debug-only
  seed that is empty in release.

## Goals / Non-Goals

**Goals:**

- Get recorded presence onto the device over a transport that adds no dependency and leaves the
  Firestore rules closed.
- Produce the pure reconstruction phases 3 and 4 will both consume, and prove it against real data
  in a surface that cannot corrupt anything.
- Make the disagreement between the cloud's account and the local ledger measurable.

**Non-Goals:**

- Any write to the session ledger, and therefore any question of provenance, day attribution, or
  progress events. Those arrive with phases 3 and 4.
- Deciding how minutes should be re-placed. This phase produces the evidence for that decision; it
  does not make it.

## Decisions

### An authenticated HTTP endpoint, not the Firestore client SDK

A second function beside `pollPresence` serves a windowed read; the app calls it with Retrofit and
a bearer token.

*Alternative considered — the Firestore Android SDK with anonymous auth.* Rejected on four counts,
any one of which would be tolerable and which together are not. It adds `firebase-bom`,
`firestore-ktx`, `auth-ktx` and the Google Services Gradle plugin to an app whose entire dependency
list is currently deliberate. It requires `google-services.json` keyed by application id, and
`applicationIdSuffix = ".debug"` means two entries or a debug build that fails at init. Anonymous
auth is not authentication — anyone who extracts the config reads the log. And it would require
opening `firestore.rules`, discarding a property the poller's spec currently asserts outright.

*Alternative considered — the SDK with a real signed-in account.* Genuinely tight rules, and the
one thing HTTP cannot do: a live subscription to the current-state document. Rejected for now
because it puts a sign-in flow in an app whose identity is offline-first with no account, to buy a
capability nothing in this phase needs. If a live overlay is built later, this is the decision to
revisit — and revisiting it costs nothing built here, because the reconstruction is downstream of
the transport.

*Consequence worth stating:* the Firestore document shape stays a private contract between the two
functions that share a repository. The app learns a response shape, not a collection layout, so the
storage layout can change without an app release.

### The reconstruction is pure and lives beside the other two

`SessionDiffer` and `PresenceSessionDeriver` are both pure — no Room, no Android, no clock, the
caller supplies timestamps and persists the result. The reconstruction is the third of that family
and sits with them in `domain/`.

*Why it matters beyond tidiness:* phases 3 and 4 both consume it, and phase 4 will be reasoned
about by comparing its output against `SessionDiffer`'s. Two pure functions can be compared in a
unit test; two functions entangled with Room and a network client cannot.

*Alternative considered:* putting it in `:gamification`, the existing pure module. Rejected — that
module's identity is XP, levels, streaks and rarity. Presence reconstruction is Steam-shaped, not
progression-shaped, and `domain/` already holds exactly this kind of pure Steam-shaped logic.

### Coverage is carried, never resolved

The reconstruction reports coverage as it was recorded — observed continuously, observed until a
stated time, or unknown — and does not decide whether a given gap is tolerable.

*Why:* that threshold belongs to the consumer, and the consumers differ. Phase 4 can afford a loose
tolerance because Steam's totals bound the error; phase 3 cannot, because nothing bounds it there.
A single verdict computed here would have to be wrong for one of them. This is the same reasoning
phase 1 used to record a timestamp rather than a flag, applied one layer up.

### The read position is stored, and discarded on an account change

A stored watermark bounds every read and makes repetition harmless. It is cleared whenever the
configured Steam account changes, through the machinery `onboarding-credentials` already defines
for that event.

*Why the clearing matters:* a position established under one account, resumed against another,
would silently skip the new account's history up to that point and present it as complete. The
account assertion catches a mismatched *response*; clearing the position is what handles a
legitimate account *change*.

### The first read covers a bounded recent window, not all history

With no stored position, the read covers a recent window rather than the full retained log.

*Why:* retention is indefinite by design, so "everything" is a query whose cost grows for the life
of the project, and this phase has no use for history older than what the local ledger can be
compared against. Phase 4's backfill is where the full sweep belongs, with a rule for it.

### Rejection precedes the datastore read

The endpoint checks the credential before touching Firestore, caps `maxInstances`, and fails closed
when its own secret is absent.

*Why:* it is a public URL on a Blaze-plan project. An authenticated endpoint that reads first and
authorises second turns an unauthenticated flood into a bill, and a missing secret that defaults to
open turns a deployment mistake into publication of the log.

## Risks / Trade-offs

- **A second public surface on the project** → Bearer credential in Secret Manager, rejection
  before any read, bounded instances, and a credential that is revocable by rotating one secret and
  redeploying. The blast radius of disclosure is the presence log of one account, which is already
  the thing the endpoint exists to serve.

- **The diagnostics surface becomes the feature** → It is developer-facing and lives under the
  `ui/diagnostics` exception, which exists to show stored rows as stored. The guard against it
  growing into a product surface is the spec: it offers no action to apply what it shows.

- **The reconstruction is proven against one account's data** → True, and unavoidable for a
  single-user project. Mitigated by keeping it pure, so the cases that real data does not produce —
  a first transition with no predecessor, an unknown-coverage interval adjacent to a known one, an
  interval still open — are reachable as unit tests rather than only as luck.

- **Five weeks of existing history is all unknown-coverage** → Expected. It still supports the
  date-level comparison, which is where the disagreement mostly lives, and everything recorded
  after phase 1 deploys is fully described. The surface must make the distinction visible so the
  older era is not read as confident.

- **A read that has been failing for weeks is invisible** → The diagnostics record is the signal,
  and the Settings section states when the last successful read occurred rather than only whether
  configuration exists.

## Migration Plan

Additive on both sides. No schema migration, no Room migration, no data written.

**Deploy order:** the endpoint first, then the app. An app configured against an endpoint that does
not exist yet fails verification and refuses to store the configuration, which is the correct
behaviour rather than a broken state.

**Rollback:** removing the configuration in Settings destroys the credential and returns the app to
unconfigured. Deleting the endpoint function makes verification fail for anyone attempting to
configure it. Neither leaves anything behind, because nothing derived was written.

## Open Questions

- Whether the diagnostics comparison is most legible per-interval or per-date. Both are
  implementable from the same reconstruction and the choice can be made while building the surface;
  it changes no requirement and no task.

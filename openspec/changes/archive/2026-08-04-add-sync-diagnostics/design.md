# Design — sync and presence diagnostics

## Context

Two questions motivated this change, and both were answered by *reading code* rather than by
observing the app:

1. Which branch does presence detection take when the indicator doesn't appear?
2. Where does sync time actually go?

The first has six candidate answers, all silent. The second was answered with arithmetic
(`~780 requests × ~250ms`) that has still never been validated against a measurement. The design
goal is that neither question ever requires code-reading again.

The constraint that shapes everything: **the device is untethered when the interesting things
happen.** So the design question is not "what should we log" but "what should we *store*, and can
it be read on the device."

## Why structured records, not log lines

The natural instinct is Timber plus tagged strings. For narrative debugging that is right, and it
is included — but it is the least valuable third of this change, because the questions worth asking
about sync are numeric and comparative:

| Question | Log lines | Structured record |
|---|---|---|
| Is this run slower than usual? | grep, eyeball, guess | order by duration |
| How many requests did that sweep cost? | count matching lines | a column |
| Did tiering skip a game that had changed? | correlate two lines by appId | two fields, one row |
| Which presence branch fired last night? | gone, buffer rotated | a row |

The last is decisive. Logcat is an OS ring buffer; a determination from eight hours ago is simply
not there. Anything that must be readable later has to be in the database.

So: **structured records are the primary artifact; freeform logging is a convenience.** Building the
freeform facade first and hoping it answers these questions is the failure mode to avoid — it is
what makes codebases noisy without making them diagnosable.

## The three record types

```
┌──────────────────────────────────────────────────────────────────────────┐
│  sync_run          one row per worker execution                          │
│    trigger (periodic | manual | reconciliation)                          │
│    startedAt, durationMs                                                 │
│    requestCount, requestMillis                                           │
│    gamesExamined, gamesUpdated                                           │
│    outcome (success | failed | incomplete | skipped:<reason>)            │
│    errorMessage?                                                         │
├──────────────────────────────────────────────────────────────────────────┤
│  request timing    aggregated into the run; not individually persisted    │
│    endpoint, status, durationMs  ──▶ counted and summed per run          │
├──────────────────────────────────────────────────────────────────────────┤
│  presence_decision one row per determination                             │
│    at, trigger (foreground | poll | sync)                                │
│    outcome (in_game | not_playing | no_credentials | no_player | failed) │
│    appId?, retainedPriorState                                            │
└──────────────────────────────────────────────────────────────────────────┘
```

### Why request timings are aggregated, not persisted individually

A single sweep is ~780 requests. Persisting each would mean tens of thousands of rows per day to
answer a question — "where does the time go" — that is fully answered by per-endpoint counts and
sums per run. Aggregate in memory during the run, write one row.

If per-request detail is ever needed for a specific investigation, a debug-only flag to persist them
temporarily is a cheaper addition than carrying the volume permanently.

### Why `outcome` is an enum with a reason, not a boolean

The current code's failure modes are all *silent successes*. `SteamSyncWorker:67-71` returns
`Result.success()` on an empty games list; `LiveStatusRepository:150,154,161` return
indistinguishable `LiveStatus()` values for "no credentials", "no player returned", and "not
playing". A boolean `succeeded` would record all of these as `true` and preserve exactly the
ambiguity this change exists to remove.

The presence outcomes deliberately mirror those three branches one-to-one, because they are the
six-way ambiguity that made the original investigation slow.

### Outcome is enforced in code, not in the schema

`outcome` stays a `String` column in Room rather than becoming a typed enum with a migration.
`optimize-steam-sync` is in flight against the same tables concurrently; a schema-changing migration
here would be one more thing for that branch to rebase across, for a benefit — compile-time
exhaustiveness — that a code-layer sealed type gives for free.

Each recorder site constructs its outcome string from a Kotlin sealed class / enum (one for
`SyncRun`, one for `PresenceDecision`) whose `toString()`/`name` is what gets persisted. The fixed
set of values lives in one place in code; the column itself remains an untyped string, so no
migration is required to introduce or retire this constraint. If `optimize-steam-sync` lands changes
to the same tables first, this stays a pure Kotlin-side addition with no schema conflict to resolve.

## Redaction has to be structural

`SteamApi` passes credentials as query parameters, so the API key is in the URL of every request.
The existing `HttpLoggingInterceptor.Level.BASIC` logs full URLs, which means debug builds currently
write the key to logcat continuously.

Relying on call sites to redact fails the moment someone adds a call site. Redaction belongs in the
one place request data is turned into a string:

```
   Request ──▶ [ interceptor ]  ──▶  sanitized identifier ──▶ record / log
                     │
                     └─ strips `key` and `steamids` from the query
                        before anything downstream sees it
```

The interceptor is the only component that sees a `Request`, and nothing downstream receives an
unsanitized identifier. That makes the guarantee structural rather than a convention.

### Replacing HttpLoggingInterceptor rather than configuring it

`HttpLoggingInterceptor` in OkHttp 4.12 offers `redactHeader`, but no query-parameter redaction —
that arrived later. Since a custom interceptor is needed anyway for redaction, it should also be
where timing lives; that is the same wrapping point, and it means one component rather than two.
`Level.BASIC`'s output is not worth preserving for its own sake.

Only credential parameters are stripped. `appid` and similar must survive — a record that cannot
identify which game a request was for is useless for the achievement-tiering work.

## Threading records through the worker

`SteamSyncWorker.doWork` should not accumulate diagnostic bookkeeping inline; that is how a worker
becomes unreadable. A small scoped recorder created at the start of the run and finalised at the
end, with the interceptor contributing request counts through it:

```kotlin
// shape only
diagnostics.recordRun(trigger = PERIODIC) {          // opens a run scope
    ...existing body, unchanged...
}                                                     // finalises: duration, counts, outcome
```

Two properties matter more than the exact shape:

- **The run record must be written on every exit path** — success, failure, early return, and
  cancellation. The early-return paths are precisely the ones currently invisible, so a design that
  only records completed runs would miss the interesting cases. This argues for a `try/finally`
  wrapper rather than explicit calls at each return.
- **Recording must never affect the run.** A failure to write a diagnostic row must not fail a sync.
  Every recorder call is best-effort, consistent with how the existing snapshot write is treated
  (`SteamSyncWorker.kt:190`).

## Interaction with cancellation

`optimize-steam-sync` notes that `runCatching` at `AchievementRepository.kt:99` and the catch at
`SteamSyncWorker.kt:90` both swallow `CancellationException`, so a stopped worker keeps iterating.
That bug is directly relevant here: the `incomplete` outcome cannot be recorded correctly if
cancellation is indistinguishable from a normal failure.

These two changes therefore share an interest in that fix. Whichever lands first should make it, and
this change's `incomplete` outcome is unreliable until it is made — worth stating so it is not
discovered as a mystery later.

## The diagnostics surface

A Settings sub-destination: a list of recent runs (relative time, duration, request count, outcome
with a color cue), tapping through to a detail view. Presence decisions in a second section or tab.

Deliberately plain. The purpose is answering a question in ten seconds, and effort spent styling it
is effort not spent on the sync work it exists to support.

**Available in release builds.** The developer here installs signed release builds
(`keystore/`, the release workflow), and the reported problems occur there. Gating diagnostics on
`BuildConfig.DEBUG` — the reflexive choice — would put them in exactly the builds where they are
least needed. The split is: persisted records always on; logcat output debug-only.

## Retention sizing

At a 15-minute cadence a run row is ~96/day. Retaining ~200 runs covers about two days, which spans
the "I noticed this yesterday" case that motivates the feature, and pruning on insert keeps the
table trivially small. Presence decisions are more frequent while in game (30s cadence), so they
want their own, larger cap or a shorter time horizon — sizing worth checking against real volume
once records exist.

## Freeform logging

Timber, with a `DebugTree` installed only in debug builds. One dependency, and its `Tree`
abstraction is a clean fit if narrative output ever needs a second destination.

An internal facade over `android.util.Log` would also do and avoids a dependency. Either is fine;
this is the part of the change least worth deliberating, and it should not be allowed to absorb the
attention that belongs on the record schema.

What it is *for*: narrating a specific investigation while it is active, then being deleted. Not for
permanent instrumentation — that is what the records are.

## Verification

1. **The leak is closed.** Run a debug build with logcat attached, exercise a sync, confirm the API
   key appears nowhere. This is the one check with a security consequence, so it should be explicit.
2. **Endpoints stay identifiable.** Confirm redacted records still show which endpoint and which
   `appid`.
3. **Every exit path records.** Force each of: success, network failure, absent credentials, empty
   owned-games, worker cancellation. Five runs, five records, five distinct outcomes.
4. **Presence branches are distinguishable.** Force each presence outcome and confirm the records
   differ — particularly the three that currently produce an identical `LiveStatus()`.
5. **Retention holds.** Exceed the cap, confirm pruning and that the table stops growing.
6. **Behaviour is unchanged.** Sync results and presence state must be identical with recording
   active. Any difference is a bug in this change.
7. **Release build works.** Confirm records are written and readable on a signed release build.

Then the payoff check, and the reason this change is worth doing first: **use the records to
validate the `optimize-steam-sync` cost model.** Compare a real sweep run's duration and request
count against the estimate of ~780 requests and ~4 minutes. If the measurement disagrees materially,
that proposal's premise needs revisiting before it is implemented — which is precisely the kind of
thing that should be discovered from data rather than after shipping.

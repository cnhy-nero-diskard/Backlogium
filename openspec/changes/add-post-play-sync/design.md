## Context

The pieces this change connects already exist and are load-bearing elsewhere, so the design is
mostly about *not* disturbing them.

- `LiveStatusRepository` exposes `nowPlaying: Flow<NowPlaying>` and never persists presence
  (`live-status`: "The now-playing state is a transient live signal and SHALL NOT be persisted").
  `PresenceService` already holds the current `NowPlaying.InGame` and reacts to `handle(status)`,
  so the transition is observable without new polling.
- `steam-sync` guarantees exactly-once crediting *structurally*: "deriving each poll's committed
  delta from baselines read within the same transaction that commits it… SHALL NOT depend on
  scheduling behaviour, work-request identity, or the two polls running in the same process." A new
  poll source is therefore safe by construction if — and only if — it commits through that path.
- `SteamSyncCoordinator` is a process-local mutex. It is an optimization, not the correctness
  mechanism, and this change must not start treating it as one.
- `SteamApi` currently exposes no way to ask about a subset of games. `GetOwnedGames` takes no
  per-app filter in its present Retrofit form.
- The `CLAUDE.md` invariant stands: the on-device engine is the sole author of derived values. This
  change adds an observation trigger, not a second interpreter.

## Goals / Non-Goals

**Goals:**

- A finished session appears in History within roughly a minute of Steam publishing it, without the
  user touching anything.
- The mechanism cannot double-count, and that property does not rest on this change's own care.
- Bounded, predictable request cost: a hard cap per session, independent of library size.
- Failure is silent and self-correcting — the 15-minute poll remains the backstop.

**Non-Goals:**

- Reducing latency below Steam's own publication lag. If Steam has not updated, no design here
  helps.
- Replacing or reducing the periodic sync.
- Any new derived value, or any new author of an existing one.

## Decisions

### 1. `GetRecentlyPlayedGames` with `count=1`, not a filtered `GetOwnedGames`

Three ways to ask Steam about one game's playtime:

| Approach | Request shape | Response size | Verdict |
|---|---|---|---|
| `GetOwnedGames` unfiltered | plain GET | whole library | Defeats the purpose — the cost this change exists to avoid |
| `GetOwnedGames` + `appids_filter` | `input_json` with a nested JSON array | one game | Correct but awkward: the filter is only reachable through a JSON-encoded parameter, which Retrofit must hand-build and diagnostics must then normalize |
| `GetRecentlyPlayedGames` + `count=1` | plain GET, two params | one game | Chosen |

`GetRecentlyPlayedGames` is ordered by recency, so `count=1` returns the game the player just
stopped — which is precisely the game whose transition triggered the fetch. It is a plain GET with
`key` and `steamid`, so it normalizes into the diagnostics endpoint scheme with no special case, and
it carries `playtime_forever` and `playtime_2weeks`, the two fields session synthesis reads.

**The response is verified against the expected app id rather than trusted.** If the returned game
is not the one that stopped — possible if the player started something else within the window, or
if Steam orders differently than documented — the observation is discarded and the attempt counts as
unproductive. Applying playtime from an unexpected app id would attribute minutes to the wrong game,
which is worse than the staleness this change exists to fix.

*Rejected:* using `count=0` (all recently played) and picking the match. It returns more data for no
benefit — the app already knows which game it is asking about.

### 2. The retry schedule is fixed, front-loaded, and terminates on evidence

Attempts at **0s, 1m, 3m, 8m**, then stop.

The immediate attempt is not expected to succeed and is kept anyway: it costs one small request, it
occasionally does succeed, and it establishes the baseline observation that later attempts compare
against. The gaps widen because Steam's lag is not uniform — most sessions publish within a couple
of minutes and a tail runs longer — so linear retries would spend all four attempts inside the
window where the answer is most likely already known.

**Termination is on observed increase, not on elapsed time.** The schedule stops the moment a fetch
reports `playtime_forever` greater than the stored baseline for that game. A session that publishes
on the first attempt costs exactly one request.

**Exhausting the schedule is not a failure.** After 8 minutes the periodic poll is at most 7 minutes
away, and it will observe the same increase through the same diff. The worker succeeds silently
rather than retrying, so WorkManager does not back off and re-run a schedule that has already
concluded.

**A zero-minute session produces no increase and exhausts the schedule.** Launching a game and
quitting within a minute is real and common, and Steam rounds it to nothing. That case is
indistinguishable from lag, costs four small requests, and is correct — there is nothing to record.

*Rejected:* waiting a fixed 5 minutes and firing once. Cheaper, but it guarantees five minutes of
staleness even when Steam settled in thirty seconds, which is the common case and the one the
feature is judged on.

### 3. It commits through the existing poll path, and that is the whole double-count story

The targeted fetch does not compute or persist anything itself. It supplies an observed
`(appId, playtimeForever, playtime2Weeks)` to the same session-synthesis and commit path the
periodic poll uses.

This is what makes the change safe rather than merely careful. `steam-sync` already requires the
committed delta to be derived from baselines read inside the committing transaction, explicitly
independent of scheduling and process. So if a post-play fetch and a periodic poll observe the same
increase, the second to commit reads an already-advanced baseline and records nothing — no session,
no minutes, no XP. The two sources cannot disagree because neither of them decides anything; the
transaction does.

**The `SteamSyncCoordinator` mutex is taken opportunistically, and its absence is not an error.** It
prevents two concurrent Steam conversations in one process, which is worth having, but the worker
runs in the same process as the periodic sync and must not treat lock contention as a reason to
fail or to skip. Correctness lives in the transaction; the mutex only avoids waste.

**No derived-value recomputation is triggered separately.** The commit path already writes derived
values through the existing recoverable protocol immediately afterwards. Adding a second trigger
would be a second author, which `CLAUDE.md` forbids.

### 4. WorkManager, one uniquely-named chain per app id

The schedule is enqueued as WorkManager work rather than held on a coroutine scope. The case this
feature exists for is "quit the game, put the phone down" — and on modern Android the app process is
frequently gone within seconds of that. An in-process timer would drop precisely the sessions it was
built to catch.

Each attempt is enqueued as a one-time work request with an initial delay, under a unique work name
derived from the app id, with `REPLACE`. Two consequences, both wanted:

- Starting and quitting the same game twice in ten minutes replaces the first schedule rather than
  running two. The second quit's schedule is the one that matters.
- Quitting game A and starting game B keeps A's schedule alive under its own name, so A's minutes
  are still collected while B is running.

The worker takes no foreground service and sets no expedited flag. It is not urgent enough to
justify either, and the schedule's own delays make expedited execution meaningless.

### 5. The transition hook lives with presence, and carries no library work

`live-status` requires presence resolution to be independent of library-scale work, and the end of a
session is presence's own event. The hook therefore does exactly one thing: on observing
`InGame(appId)` → `NotPlaying`, enqueue the schedule for `appId`. It performs no request, touches no
database, and cannot fail in a way that affects presence.

The `appId` must be captured from the *previous* state — by the time the transition is observed,
`NowPlaying` no longer names the game. `PresenceService` already retains `current: NowPlaying.InGame`
for exactly this reason, so the value is available where the transition is seen.

**`LivePresence.OFFLINE` is not a session end.** Steam cycles idle accounts through away and snooze
on its own, and the cloud poller's history records that this churn previously fragmented continuous
sessions. Only a transition out of `InGame` enqueues; a presence change that leaves the running game
intact does nothing.

### 6. It is a first-class diagnostics run

Each schedule produces one sync-run record per attempt, carrying a trigger that names it as
post-play and identifies the app id. `app-diagnostics` already requires a record for every run
including early-returning ones, so an attempt that observed no increase is recorded as such rather
than being invisible.

This matters for a feature whose failure mode is silence: without records, "my session did not show
up" is undiagnosable. With them, the request breakdown shows four attempts, their timings, and what
each observed.

## Risks / Trade-offs

- **Up to four extra requests per play session.** Bounded, small, and proportional to play rather
  than to library size. A heavy day of five sessions costs at most twenty small requests against a
  periodic sync that already runs 96 times a day.
- **Steam may lag beyond 8 minutes.** Accepted: the periodic poll is the backstop, and the outcome
  is the current behaviour, not a worse one.
- **`GetRecentlyPlayedGames` semantics are less rigidly documented than `GetOwnedGames`.** Mitigated
  by verifying the returned app id and discarding a mismatch.
- **A family-shared or refunded game can show a playtime decrease.** `steam-sync` already requires a
  decrease to emit no session and produce no negative playtime; reusing the commit path inherits
  that.

## Migration Plan

No schema change and no migration. The feature is additive and self-limiting: if the worker never
runs, behaviour is exactly what it is today.

## Open Questions

None.

# Observability for background sync and presence

## Why

The app has **no logging of any kind**. `Log.`, `Timber`, `println` — zero occurrences across
`app/src/main/java`. Every failure path degrades silently to a default, which is defensible as
*user-facing* behaviour and indefensible as *diagnosability*.

The consequence is not hypothetical. Two recent investigations were slower than they needed to be
for exactly this reason:

- "The live playing indicator isn't propping up" — six plausible causes
  (`LiveStatusRepository.kt:150`, `:154`, `:161`, `:170`; `SteamSyncWorker.kt:67`, `:87`), each
  silent, discriminated only by reading code and reasoning about lifecycle. A single line recording
  which branch `fetch()` took would have answered it immediately.
- "The sync is taking too long" — no per-request timing, no request counts, no run durations. The
  cost model that identified the whole-library achievement sweep was reconstructed by counting call
  sites and multiplying by an assumed round-trip latency. That estimate has still never been checked
  against a real number.

### Logcat is structurally the wrong sink here

The tempting fix is "add Timber, tail logcat." That does not work for this app's failure modes:

```
   WHEN failures happen              CAN adb be attached?
   ───────────────────────────────   ────────────────────
   periodic sync, every 15 min       rarely — background, unattended
   presence detection while gaming   no — the phone is in use, playing
   reconciliation on charger+wifi    no — by construction, overnight
```

Every interesting failure occurs while the device is untethered. Logcat is also ring-buffered by the
OS and gone by the time you look. Diagnostics for this app must be **persisted and readable inside
the app**, or they will not be read.

A second consequence: the developer here is also the user, and installs signed release builds
(`keystore/`, the release workflow). Diagnostics gated entirely on `BuildConfig.DEBUG` would be
absent from precisely the builds where the reported problems occur.

### And the existing request logging leaks the API key

`SteamApi` passes credentials as query parameters (`@Query("key")`), and
`NetworkModule.kt:36-40` enables `HttpLoggingInterceptor.Level.BASIC` for debug builds. BASIC logs
the full request URL, so every Steam request writes the Steam Web API key to logcat in plain text.

`app-settings` already commits to the opposite: *"The raw API key SHALL NOT be displayed"* and
*"the API key appears only in masked form."* That rule was written about the Settings screen, but the
underlying reason — the key is a secret worth not scattering — applies at least as much to a log
sink that `adb logcat`, bug reports, and crash-capture tooling can all read. Any change that
expands logging must close this rather than widen it.

Conveniently, the fix and the instrumentation are the same component: a request interceptor that
redacts credentials is also where per-request timing and counting naturally live.

## What Changes

- **A redacting request logger** replacing `HttpLoggingInterceptor`, recording method, path, status,
  and duration per request, with credential query parameters removed before anything is emitted.
  This closes the key leak and supplies the per-request timing that "which fetch is slow" needs.
- **Persisted per-run sync records** — one bounded row per sync run capturing what actually
  happened: trigger, duration, request count, games examined and updated, outcome, and error. This
  is the deliberate half of the change: the questions worth asking about sync are *numeric and
  comparative* ("is this run slower than last week's", "how many requests did that cost"), and
  freeform log strings answer those badly.
- **A presence-decision record** — which branch presence detection took and why, so
  "the indicator didn't appear" resolves to a recorded reason rather than a code-reading exercise.
- **An in-app diagnostics surface** under Settings, listing recent runs and letting one be inspected.
  Available in release builds, since that is where the problems are observed.
- **A freeform logging facade** for narrative debugging, routed to logcat in debug builds only.
  Explicitly the least important part of this change, and listed last to keep it that way.

## Capabilities

### New Capabilities
- `app-diagnostics`: persisted, bounded, in-app-readable records of sync runs, request timings, and
  presence decisions, with credential redaction as a structural guarantee rather than a convention.

### Modified Capabilities
- `app-settings`: gains a diagnostics entry point, under the same masking rules the account section
  already follows.

## Impact

- **Affected code (new):** a redacting logging interceptor; a bounded sync-run entity, DAO, and
  recorder; a presence-decision recorder; a diagnostics screen and ViewModel; a logging facade.
- **Affected code (modified):** `NetworkModule` (replace `HttpLoggingInterceptor`);
  `SteamSyncWorker` and `LiveStatusRepository` (emit records at existing decision points — no
  control-flow changes); the Settings screen (one entry point); a Room migration for the new table.
- **Storage:** bounded by construction — a fixed number of retained runs, pruned on insert. Sizing
  should assume the 15-minute cadence, so "recent" is a couple of days, not forever.
- **Privacy:** this change *reduces* exposure on net. It removes the standing key leak in debug
  builds, and its own records are app-private, bounded, and never contain credentials. Redaction
  must be enforced where values are formatted, not left to callers remembering.
- **Performance:** one insert per sync run, negligible against a run that makes network calls. The
  interceptor's cost is a timestamp per request.

## Non-goals

- **Remote telemetry, crash reporting, or analytics.** Nothing leaves the device. This is a
  single-user app and the diagnostics exist for its one operator; adding an external sink would be a
  different change with an entirely different privacy conversation.
- **A general-purpose in-app log viewer.** Structured records for sync, requests, and presence —
  not a searchable tail of every log line the app ever emitted.
- **Logging as a substitute for user-facing error states.** The existing recoverable-error surfacing
  (`lastSyncError`) stays as it is; diagnostics sit behind Settings and are for investigation, not
  for telling a user something went wrong.
- **Instrumenting anything beyond sync, requests, and presence.** UI, navigation, and gamification
  recompute are out of scope. Broad instrumentation with no question behind it produces noise.
- **Changing any sync or presence behaviour.** Records are emitted at decision points that already
  exist. If this change alters an outcome, it has a bug.
- **Exporting or sharing diagnostics.** Read them on the device. Export invites the key-leak problem
  back in through a new door.

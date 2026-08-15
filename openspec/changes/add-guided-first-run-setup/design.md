## Context

- `OnboardingViewModel` has a two-value step enum (`API_KEY`, `STEAM_ID`) and `OnboardingScreen`
  renders `"Step ${...} of 2"` from a hardcoded conditional. Both assume exactly two steps.
- Validation today is shape-only: a 17-digit SteamID beginning `7656119`, plus — on the vanity path
  only — a `ResolveVanityURL` call that incidentally proves the key works. A user pasting a raw
  SteamID64 never exercises their key at all.
- `SteamSyncWorker`, the `add-offline-steam-assets` worker, and `HltbRefreshWorker` already exist as
  independent WorkManager jobs. `HltbRefreshWorker` already reports per-item progress via
  `setProgress` and posts a completion notification, with a `POST_NOTIFICATIONS` check that skips
  silently when the grant is absent.
- `app-ui` already requires the notification permission to be requested in-app.
- `add-offline-steam-assets` requires its download to be "triggered only by an explicit user action"
  and forbids enqueuing one when a sync completes.
- `steam-sync`'s *First-sync baselining* makes the first poll a baseline that creates no historical
  sessions. The setup pipeline's initial sync *is* that poll.
- A full-library HLTB sweep is paced and can run for a long time on a large library.

## Goals / Non-Goals

**Goals:**

- A new user reaches a populated app without knowing that Settings exists.
- Adding a fifth stage is a registration, not a redesign of the screen or its state.
- No stage is mandatory and none can trap the user in the setup screen.
- One failing stage cannot cost another its results.
- Setup owns no work of its own — it schedules and reports work that already exists.

**Non-Goals:**

- A second implementation of anything a stage wraps.
- Making setup a prerequisite for using the app.
- Reporting setup state anywhere outside the device.

## Decisions

### 1. Setup is a registry of stages, because that is what was asked for

The request was explicitly for a pipeline "left open for more triggered [stages] after setup". Two
hardcoded steps would satisfy today's list and have to be dismantled for the third.

A stage declares:

```
  id            stable, persisted — the opt-in and outcome are keyed by it
  title         what the checklist shows
  detail        one line on what it will do and roughly what it costs
  defaultOptIn  whether it starts ticked
  execution     IN_SCREEN | DETACHED
  run()         schedules the underlying work and reports progress
```

Everything else — the checklist, ordering, persistence, progress display, the failure summary, the
Settings re-run entry — is derived from the registered list. A fifth stage appears in all of them by
being registered.

**Stage ids are persisted, so an id is an API.** Renaming one orphans a user's stored opt-in and
outcome. The registry carries the same warning the app's other persisted-by-name enums carry.

**A stage whose prerequisite is absent is registered but unavailable** — shown, disabled, with the
reason. This is what lets setup ship before `add-offline-steam-assets`, and what keeps a
prerequisite change from having to modify this one.

### 2. Four stages, and verification is one of them

```
  ┌───────────────────────────────────────────────────────────────┐
  │ 1  Verify credentials      IN_SCREEN   always, not opt-out    │
  │ 2  Sync your Steam library IN_SCREEN   ticked by default      │
  │ 3  Download game artwork   DETACHED    unticked               │
  │ 4  Fetch completion times  DETACHED    unticked               │
  └───────────────────────────────────────────────────────────────┘
```

**Verification is a stage rather than a validation because it is a network operation that can be
slow, can fail in several distinguishable ways, and deserves the same progress treatment as
everything else.** Modelling it as a stage means the setup screen has one way of showing "something
is happening" rather than two.

It is the one stage with no opt-in. Declining to verify would mean saving credentials the app has
reason to believe are wrong.

**Verification is one `GetPlayerSummaries` call for the entered SteamID with the entered key.** It
is the cheapest call that exercises both values at once, and its three outcomes map onto the three
things that can be wrong:

| Response | Meaning | Message |
|---|---|---|
| HTTP 403 | key rejected | "Steam did not accept this API key." |
| 200, empty `players` | no such profile | "No Steam profile found for that ID." |
| 200, player present | both good | proceed |
| network failure | unknown | "Couldn't reach Steam. Check your connection." — with retry |

**A network failure is not a validation failure.** It must not read as "your key is wrong", and it
must offer retry rather than sending the user back to re-type a correct key.

**A private profile verifies successfully.** `GetPlayerSummaries` returns the player regardless of
privacy; it is `GetOwnedGames` that returns nothing. Detecting that would need a second call to
diagnose a condition the initial sync will surface anyway, with a better message, in the very next
stage.

**The default opt-ins encode cost.** Sync is ticked: it is fast, and the app is meaningless without
it. Artwork and completion times are unticked: they are the expensive ones, one measured in tens of
megabytes and the other in wall-clock time, and someone setting up on mobile data should have to
choose them.

### 3. The in-screen / detached boundary is drawn at "is the app usable yet"

Stages 1 and 2 run with the setup screen up. Stage 2 is the one that makes the app non-empty, it is
bounded by library size, and entering the app before it finishes means entering an empty app — the
exact first impression this change exists to prevent.

Stages 3 and 4 run detached. A full-library HLTB sweep is paced and can run a long time; holding a
new user on a setup screen for it would be worse than the empty library. Each reports progress in
its own notification, which is what "notification bar progress" was asked for.

The screen shows the detached stages as started and offers to enter the app. It does not wait.

**Setup requests the notification permission before starting a detached stage**, through the
existing in-app request. If declined, the stages still run — the Settings entry and the Library's
existing batch panel remain observable. `HltbRefreshWorker`'s existing skip-silently behaviour is
the precedent and it is not weakened.

### 4. Stages fail independently, and setup reports rather than aborts

Each stage has its own terminal outcome: succeeded, failed, or skipped. A failure does not cancel
sibling stages and does not fail setup.

The alternative — abort on first failure — is wrong for the specific shape of these stages. They are
unrelated: a HowLongToBeat timeout says nothing about whether artwork downloaded. Cancelling a
nearly-complete asset download because a different service was slow discards real work for no reason.

Setup therefore ends in one of three states — all succeeded, some failed, all skipped — and reports
which. A failed stage can be retried individually from the Settings entry.

**Retry is re-running the stage, not resuming it.** The wrapped workers already own their own
resumption and idempotence: sync diffs against stored baselines, the asset job has a missing-only
mode, HLTB has a freshness gate. Setup adding a second layer of resume logic would duplicate three
existing mechanisms and be wrong about at least one of them.

### 5. Setup schedules; it never performs

The coordinator enqueues each stage's existing worker and observes its `WorkInfo`. It does not fetch,
parse, write, or derive anything.

This is what keeps the change from touching the invariant that matters here: the on-device engine is
the sole author of derived values. Setup running an initial sync must be indistinguishable from the
periodic sync running one, which it is — same worker, same baseline path, same commit. In particular
the initial sync stage *is* the baseline poll of `steam-sync`'s *First-sync baselining*, and gets
that behaviour by being that poll rather than by reimplementing it.

**Concurrency is deferred to the existing unique work names.** Sync, assets, and HLTB each already
enqueue under their own unique name with their own policy. Setup starting a stage while that work is
already running behaves exactly as pressing the corresponding button in Settings would.

### 6. The asset stage needs a one-clause amendment, not an exemption

`offline-steam-assets` requires its download to be "triggered only by an explicit user action" and
forbids enqueuing one when a sync completes. Ticking a checkbox and pressing "Start setup" *is* an
explicit user action, so the first clause is already satisfied — but the intent of the requirement
was to forbid an automatic bulk download, and shipping a setup pipeline without saying which side of
that line it falls on would leave the reader to guess.

The amendment therefore states that a stage the user explicitly selected is an allowed trigger, and
restates the prohibition it does not touch: nothing chains an asset download off sync completion, or
off a schedule. The distinction being drawn is *user-selected* versus *automatic*, not
*onboarding* versus *Settings*.

### 7. Setup is re-runnable, from Settings, with the same checklist

Skipping setup is a legitimate choice. Making it unrecoverable except by clearing credentials would
turn a reasonable "not now" into a trap.

Settings gains a "Run setup" entry presenting the same checklist, with each stage's last outcome
shown and stages defaulting to unticked — a re-run is deliberate, and the common case is retrying
one failed stage.

**Verification is not part of a re-run.** Credentials that are already stored have already been
verified, and Settings already has its own credential-editing path.

**Setup completion is a fact, not a gate.** Nothing checks it before allowing anything. It exists so
onboarding does not present setup twice, and so Settings can show what happened last time.

## Risks / Trade-offs

- **Hard dependency on `add-offline-steam-assets` for one stage.** Contained by the
  registered-but-unavailable mechanism, which the registry needs anyway.
- **Setup is a fourth place from which sync, assets, and HLTB can be started.** Mitigated by
  scheduling rather than performing, so all four paths converge on the same unique work.
- **The onboarding step model has to stop being a two-value enum.** Unavoidable, and small — the
  screen's `"Step N of 2"` string becomes derived rather than hardcoded, which it should have been.
- **An added stage changes what "setup complete" meant for existing users.** Accepted: completion is
  informational and gates nothing, so a user who completed setup before a stage existed simply has
  no outcome recorded for it.

## Migration Plan

No schema change. New DataStore keys whose absence means "setup never run" — which for an existing
install is true and harmless, since nothing gates on it. Existing users are not shown onboarding and
are not prompted; the Settings entry is there if they want it.

## Open Questions

None.

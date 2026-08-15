## Why

Onboarding ends the moment credentials are saved. `OnboardingScreen` captures an API key and a
SteamID64, validates their *shape*, and hands the user a working app — except the app is empty. No
sync has run, no artwork has been fetched, no completion lengths are known. The Library is a list of
nothing, and the first thing a new user sees is an app that appears broken.

Two specific gaps make it worse than merely empty:

- **Credentials are never actually tested.** The flow checks that the SteamID is 17 digits beginning
  `7656119` and, on the vanity path, that a resolution call succeeded. A syntactically valid key that
  Steam rejects, or a valid SteamID whose profile is private, passes onboarding and fails silently
  at the first background sync — 15 minutes later, on a screen that gives no hint why nothing
  appeared.
- **The work that populates the app is scattered across Settings and the Library**, each behind a
  different control, with no indication that a new user should run any of them.

The user asked for this as an opt-in onboarding step with progress and status, and explicitly asked
that it be "left open for more triggered pipeline after setup" — so the deliverable is not two
hardcoded steps but a **stage registry** that two more stages can be added to without redesigning
anything.

## What Changes

- Add a verification step to onboarding that makes one cheap authenticated call before credentials
  are accepted, distinguishing a bad key from an unreachable profile from a network failure.
- Add a setup step after verification presenting the available stages as a checklist, each
  independently opt-in, with sync pre-selected and the heavier stages unticked.
- Define setup as an ordered registry of named stages, each with an opt-in state, progress
  reporting, an independent terminal outcome, and a retry path — so adding a fifth stage is a
  registration, not a redesign.
- Ship four stages: **verify credentials**, **initial Steam sync**, **download Steam assets**, and
  **fetch completion times**.
- Run the initial sync in the setup screen with visible progress; run the later stages detached,
  each reporting its own progress in its own notification, so the user can enter the app while they
  continue.
- Isolate stage failures: a failed stage does not stop the others, setup completes reporting what
  succeeded and what did not, and any failed stage can be retried.
- Add a "Run setup" entry to Settings presenting the same checklist, so a skipped or partially
  failed setup is recoverable without clearing credentials.
- Amend `offline-steam-assets` so that a stage the user explicitly ticked counts as an explicit
  trigger, while leaving intact its prohibition on chaining an asset download off sync completion.

## Capabilities

### New Capabilities

- `first-run-setup`: Defines the stage registry and its extension contract, per-stage opt-in,
  ordering, the boundary between in-screen and detached execution, progress and notification
  semantics, failure isolation and retry, completion and skip semantics, and re-running setup later.

### Modified Capabilities

- `onboarding-credentials`: Credentials are verified against Steam before being accepted, with
  distinguishable failure reasons, and completing the flow leads into setup rather than into an
  empty app.
- `app-settings`: A "Run setup" entry presents the same staged checklist after onboarding.
- `offline-steam-assets`: An asset download stage the user explicitly selected during setup is an
  allowed trigger; the prohibition on automatic and scheduled runs is unchanged.

## Impact

- **Affected code:** `ui/onboarding/` (a verification step, a setup step, and its state), a new
  `work/setup/` stage registry and coordinator, existing sync / asset / HLTB workers wrapped as
  stages, notification channels, `ui/settings/`.
- **Storage:** DataStore state for setup completion, per-stage opt-in and terminal outcome. No Room
  schema change.
- **Network:** One additional verification request per onboarding attempt. Every other request is
  work the app already performs, moved earlier and made explicit.
- **Permissions:** Setup requests the notification permission before starting a detached stage,
  through the existing in-app request, and proceeds without it if declined.
- **Dependencies:** None new.

## Depends on

`add-offline-steam-assets` **must land first.** The asset stage wraps that change's worker and
storage; there is no second asset-download path in this change. The other three stages have no such
dependency, so setup can ship with the asset stage registered but unavailable if that ordering
becomes inconvenient.

## Non-goals

- A second asset-download implementation.
- Changing what any wrapped stage does. Setup schedules and reports existing work.
- Making any stage mandatory. Every stage including the initial sync can be declined.
- Running setup automatically after any later sync.
- Cloud or account-based setup state. Setup completion is local.
- Re-verifying credentials on a schedule. Verification happens when credentials are entered.

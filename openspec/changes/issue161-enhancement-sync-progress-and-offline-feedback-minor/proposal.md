# Proposal

## Why

Setup recovery exposes operation stages, and the app shell already shows when a sync is active. People still need understandable progress and results for Steam and achievement work, an app-wide offline indication, and feedback for important actions. [Issue #161](https://github.com/cnhy-nero-diskard/Backlogium/issues/161)

## What Changes

- Show phase, real item counts when available, queued/retrying and terminal outcomes in Setup and Settings.
- Keep an accessible offline indication visible across normal in-app destinations while cached and local actions stay usable.
- Audit action feedback across the requested surfaces and fill user-visible gaps using existing feedback patterns.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `steam-sync` — Observable progress and operation outcomes.
- `first-run-setup` — Progress for Steam and achievement setup stages.
- `app-settings` — Operation-level progress and outcomes in Settings.
- `app-ui` — Persistent offline state and action feedback.

## Impact

Android workers, repositories, Setup, Settings and app shell. Builds on `stabilize-first-run-setup`.

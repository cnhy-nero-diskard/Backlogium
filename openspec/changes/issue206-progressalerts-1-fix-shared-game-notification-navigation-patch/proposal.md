# Proposal

## Why

The existing shared-game admission notification opens Backlogium without an app ID. Tapping it should open the game that triggered it. [Issue #206](https://github.com/cnhy-nero-diskard/Backlogium/issues/206)

## What Changes

- Verify the reported behavior, then pass a validated game and account target through the existing notification navigation path.
- Handle cold and warm launches, repeated delivery, and stale, hidden, removed, or otherwise inaccessible targets.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `game-sources` — Targeted navigation from an existing shared-game admission notice.
- `app-ui` — Lifecycle and navigation behavior for a game-targeted notification.

## Impact

First change in `progressAlerts`. Android admission notifier, MainActivity and app navigation. Builds on `add-achievement-watch`, which already routes achievement notifications to game detail. Provides shared routing for `issue166-progressalerts-2-feature-progress-notifications-minor`.

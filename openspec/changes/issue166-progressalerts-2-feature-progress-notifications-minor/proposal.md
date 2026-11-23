# Proposal

## Why

Achievement alerts and in-app progress events already exist. The other requested notifications need independent controls and an account-safe delivery path. [Issue #166](https://github.com/cnhy-nero-diskard/Backlogium/issues/166)

## What Changes

- Add optional alerts for newly added games, broken streaks, daily quota, game completion, and yesterday’s recap.
- Use committed events, date-aware recap content, Android channel vibration controls, and the shared game notification route.

## Capabilities

### New Capabilities

- `progress-notifications` — Optional Android alerts and the previous-day recap.

### Modified Capabilities

- `app-settings` — Independent preferences and notification controls.

## Impact

Second change in `progressAlerts`; requires `issue206-progressalerts-1-fix-shared-game-notification-navigation-patch` for shared routing and `issue159-enhancement-hydrate-admitted-game-achievements-minor` for the durable owned account/game admission identity. Implement #206 first, then #159 before #166, so the notification receipt can reuse the committed identity already used for hydration rather than adding a separate post-commit acquisition record. Keep achievement-watch settings and delivery behavior intact.

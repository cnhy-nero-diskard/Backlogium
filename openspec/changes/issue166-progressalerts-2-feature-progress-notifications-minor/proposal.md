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

Second change in `progressAlerts`. Requires `issue206-progressalerts-1-fix-shared-game-notification-navigation-patch`. Uses the existing progress events, admission commits, and achievement evidence. Keep achievement-watch settings and delivery behavior intact.

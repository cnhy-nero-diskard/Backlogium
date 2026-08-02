# Add opt-in live monitor

## Why

Today Backlogium only starts its 30-second presence poll after it has already observed a running
game. If the app stays backgrounded when a game begins, the first observation must wait for the
next periodic sync. Returning to the app is a reasonable recovery path, but not a satisfying
"live monitor" experience.

## What Changes

- Add a persisted, off-by-default **Live monitor** setting under Settings.
- Enabling it while Backlogium is visible starts the existing foreground presence service even
  when Steam currently reports no game. It keeps a low-priority ongoing "Monitoring Steam"
  notification and checks Steam every 30 seconds.
- The service transitions its notification to the existing game-and-elapsed-time form when a game
  starts, and returns to the monitoring notification after it ends while the setting remains on.
- Disabling the setting stops idle monitoring. A game already being tracked continues until Steam
  reports that it ended, preserving the existing session-tracking behavior.
- On a later app foreground, an enabled setting restarts monitoring if Android stopped the service.

## Impact

- Affected code: Settings DataStore/repository and settings UI; `PresenceService`, its
  notification builder, and the app foreground bootstrap.
- The service remains a user-started `dataSync` foreground service. Android 15 limits that type to
  six hours of background runtime in a 24-hour period, so the Settings copy must disclose the
  limit and users must explicitly re-enable it after a timeout.
- No new backend or credential movement. This is deliberately a local, opt-in bridge until the
  planned cloud sync can provide server-side presence delivery.

## Non-goals

- Detecting a game after the monitor's Android service timeout without the user returning to the
  app. That needs a backend/push architecture.
- Changing periodic sync cadence or achievement processing.
- Sending any additional notifications beyond the required ongoing foreground-service indicator.

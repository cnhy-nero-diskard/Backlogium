## Context

`UpdateInstallReceiver` handles three `PackageInstaller` statuses. Two of them
(`STATUS_PENDING_USER_ACTION` and the failure branch) already route through `isAppVisible()`
before doing anything that requires a foreground window — a prior fix hardened the pending-action
branch specifically because Android's background-activity-launch (BAL) restriction blocks a plain
`startActivity`/`PendingIntent.send()` from a process with no visible window. `STATUS_SUCCESS` was
the one branch still doing the unconditional thing.

## Decision

Reuse the exact same guard and fallback shape already established for
`STATUS_PENDING_USER_ACTION`, rather than inventing a second mechanism:

- Visible → `context.startActivity(launchIntent)` directly. A foreground app starting its own
  activity is not a background launch, so no BAL exemption is needed.
- Not visible → post a notification whose content intent opens the app. A user's tap on a
  notification is itself the foreground-launch trigger the platform requires, so it is exempt from
  BAL by construction, not by any special-casing on our side.

## Alternatives considered

- **`ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`**
  on the `PendingIntent.send()` call — technically bypasses the restriction, but only because the
  sender declares itself exempt; this is exactly the kind of self-granted BAL bypass Android added
  the stricter `balRequireOptInByPendingIntentCreator` check to close off, and there is no legitimate
  case here for skipping user awareness that an update just landed while they were elsewhere. The
  notification is also strictly more informative: the user sees that an update happened rather than
  just being silently dropped back into the app.
- **Retry the relaunch from a later foreground moment (e.g. on next `Activity.onResume`)** — would
  need new state and a new code path parallel to the notification one, for no behavioural gain over
  just notifying immediately.

## Risks

- If notification permission is absent and the app is backgrounded, the user gets no signal at all
  that the update completed; they will simply see the new version next time they open the app
  manually. This matches how `notify()` (the "update available" notification) already degrades under
  the same condition, so it's a pre-existing, accepted trade-off rather than a new one.

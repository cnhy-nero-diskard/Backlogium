## Why

`UpdateInstallReceiver.clearAvailableAndRelaunch` fired a plain `PendingIntent.getActivity(...).send()`
to relaunch the app after a successful install, unconditionally, from a `BroadcastReceiver`. Confirmed
live on-device (Android 15 emulator, real release build, real GitHub release round trip): the moment
`PackageInstaller.STATUS_SUCCESS` arrives, the foreground belongs to the system installer UI, not this
app, so the receiver's process has no visible window. Android's background-activity-launch restriction
blocks exactly this pattern — logcat: `Background activity launch blocked! ... realCallingUidHasAnyVisibleWindow:
false; realCallingUidProcState: RECEIVER ... resultIfPiSenderAllowsBal: BAL_BLOCK`. The install completes
and the update state is correctly cleared, but the app silently never relaunches — nothing is reported to
the user, and the "Update applied" scenario (`app-updates` spec) is not actually satisfied.

The sibling `STATUS_PENDING_USER_ACTION` branch in the same file already solves this exact class of
problem — checking `isAppVisible()` before launching directly, falling back to a tap-to-open notification
otherwise — because a prior fix (`fix: recover app update installer state`) hardened it. The
`STATUS_SUCCESS` path was never given the same treatment.

## What Changes

- `UpdateInstallReceiver.clearAvailableAndRelaunch` now checks `isAppVisible()` before attempting to
  relaunch: if visible, it starts the activity directly (no BAL restriction applies to a foreground
  app starting its own activity); if not, it posts a tap-to-open "Backlogium updated to `<version>`"
  notification via a new `UpdateNotifier.notifyInstallComplete`, matching the existing
  `notifyInstallConfirmation` pattern.
- `app-updates`: the "Update applied" requirement gains a scenario making explicit that relaunch is
  immediate only while the app is visible, and is otherwise offered via a notification rather than
  attempted as a background activity launch (which the platform blocks).

## Capabilities

### Modified Capabilities

- `app-updates`: "Installation and relaunch" requirement gains a scenario for the app being
  backgrounded at the moment installation succeeds.

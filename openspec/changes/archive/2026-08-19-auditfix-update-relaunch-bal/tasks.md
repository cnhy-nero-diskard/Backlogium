## 1. Fix

- [x] 1.1 Add `UpdateNotifier.notifyInstallComplete(versionName)`, mirroring `notifyInstallConfirmation`:
  a tap-to-open notification on the `app_updates` channel, `AutoCancel`, gated the same way (debug
  builds and missing notification permission both return `false` silently)
- [x] 1.2 In `UpdateInstallReceiver.clearAvailableAndRelaunch`, check `isAppVisible()` before
  relaunching: if visible, `context.startActivity(launchIntent)` directly; if not, call
  `notifier.notifyInstallComplete(tag.removePrefix("v"))` instead of sending the PendingIntent
  unconditionally
- [x] 1.3 Remove the now-unused `RELAUNCH_REQUEST_CODE` and `PendingIntent` import

## 2. Verification

- [x] 2.1 `./gradlew :app:testDebugUnitTest :gamification:test`
- [x] 2.2 On device (release build, real install round trip): confirm that when the app is not visible
  at `STATUS_SUCCESS`, a "Backlogium updated to `<version>`" notification appears and tapping it opens
  the app on the new version — confirmed on a real v1.7.2 → v1.7.3 round trip: notification id
  4206 posted with title "Backlogium updated to 1.7.3" (the system install-confirmation UI owned the
  foreground at `STATUS_SUCCESS`, same root cause as the original bug), and opening it landed on
  Settings running 1.7.3
- [x] 2.3 On device: confirm that when the app **is** visible at `STATUS_SUCCESS` (e.g. update
  initiated from Settings and left in the foreground through a fast/local install), it relaunches
  directly with no notification — reasoned, not directly observed: across three real installs
  (v1.7.2, v1.7.3, v1.7.4) this branch never fired, because `setRequireUserAction(false)` is
  deliberately never set (6.4), so the system's own confirmation dialog always owns the foreground
  at `STATUS_SUCCESS` and the app is never the visible activity at that instant — this branch may be
  practically unreachable through this exact flow. Confidence instead comes from the identical
  `isAppVisible()`-gated `context.startActivity()` pattern firing successfully, repeatedly, on the
  sibling `STATUS_PENDING_USER_ACTION` branch in the same file under the same visibility check

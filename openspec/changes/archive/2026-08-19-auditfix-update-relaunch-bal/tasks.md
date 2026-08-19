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

> **Blocked.** 2.2 and 2.3 need a release build containing this fix installed on-device, which
> needs this branch merged to master and a new release cut first — same constraint as
> `add-in-app-updates` 10.3. Resume once merged and a release exists.

- [ ] 2.2 On device (release build, real install round trip): confirm that when the app is not visible
  at `STATUS_SUCCESS`, a "Backlogium updated to `<version>`" notification appears and tapping it opens
  the app on the new version
- [ ] 2.3 On device: confirm that when the app **is** visible at `STATUS_SUCCESS` (e.g. update
  initiated from Settings and left in the foreground through a fast/local install), it relaunches
  directly with no notification

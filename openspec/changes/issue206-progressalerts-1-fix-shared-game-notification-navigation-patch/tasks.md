# Tasks

## 1. Reproduction and routing

- [x] 1.1 Record the current behavior of tapping a shared-admission notification on cold, warm and background launches. Verify the baseline notification payload and landing destination.
- [x] 1.2 Adapt the existing achievement intent path into a shared game route. Verify old achievement payloads and update intents, setup gating, malformed targets, and delivery after Activity recreation.
- [ ] 1.3 Emit an account/app-scoped destination from the shared-game notifier. Verify PendingIntent identity across multiple notices and rejection after hiding, removal or account switch.

### Baseline verification

On the Android 17 host-GPU emulator (`emulator-5554`), an isolated debug build of `c4d3ac47` posted two real admission notifications for family-shared rows in the selected fixture account. Android reported immutable, explicit `MainActivity` PendingIntents with request codes 20601 and 20602. Both had empty destination payloads.

| Starting state | Tapped notice | Landing screen |
| --- | --- | --- |
| Cold, app process absent | Shared Notice Alpha | Home |
| Foreground, Library selected | Shared Notice Beta | Library, same app process |
| Background, Library selected | Shared Notice Alpha | Library, same app process |

Neither tap path opened game detail. Both fixture games were visible in Library.

Legacy achievement, update-only, and malformed payloads passed the intent tests. On the same emulator, a legacy achievement tap waited through setup and Activity recreation, opened Alpha after setup exited, and returned to Home with one Back after another recreation.

## 2. Integration

- [ ] 2.1 Verify cold and warm taps reach the exact game once, and that Back returns to the expected screen. Record notification behavior on a targeted emulator or device.

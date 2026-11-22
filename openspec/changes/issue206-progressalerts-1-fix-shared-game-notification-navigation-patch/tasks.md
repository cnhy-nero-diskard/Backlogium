# Tasks

## 1. Reproduction and routing

- [ ] 1.1 Record the current behavior of tapping a shared-admission notification on cold, warm and background launches. Verify the baseline notification payload and landing destination.
- [ ] 1.2 Adapt the existing achievement intent path into a shared game route. Verify old achievement payloads and update intents, setup gating, malformed targets, and delivery after Activity recreation.
- [ ] 1.3 Emit an account/app-scoped destination from the shared-game notifier. Verify PendingIntent identity across multiple notices and rejection after hiding, removal or account switch.

## 2. Integration

- [ ] 2.1 Verify cold and warm taps reach the exact game once, and that Back returns to the expected screen. Record notification behavior on a targeted emulator or device.

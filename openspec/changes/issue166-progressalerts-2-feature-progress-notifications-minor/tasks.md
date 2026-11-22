# Tasks

## 1. Committed alert routing

- [ ] 1.1 Confirm the shared route from the first progressAlerts change. Connect existing earned events and new-game admissions to notification delivery with tests for deduplication, suppressed baselines, replay and retained shared fallback.
- [ ] 1.2 Detect confirmed completion transitions at the achievement merge boundary. Verify partial data, already complete baselines, estimates and repeated observations do not alert.
- [ ] 1.3 Add restart-safe delivery and per-channel preferences. Verify permission denial, Android channel and vibration controls, stable notification keys, account reset and existing achievement/shared-channel behavior.

## 2. Previous-day recap

- [ ] 2.1 Schedule recap delivery against the current local day and persist its account/date receipt. Verify midnight, daylight-saving, zone changes, delayed jobs and repeat execution.
- [ ] 2.2 Build the recap from canonical dated play and supported unlock evidence. Verify missing coverage and timing estimates remain disclosed.

## 3. Integration

- [ ] 3.1 Exercise Settings through receipt delivery and notification taps on a targeted device or emulator. Verify local progress still appears when notifications are disabled and verify affected baselines.

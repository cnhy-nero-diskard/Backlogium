# Tasks

## 1. Committed alert routing

- [ ] 1.1 Confirm the shared route from the first progressAlerts change. Connect existing earned events and new-game admissions to notification delivery with independent account/event receipts. Test foreground acknowledgement before dispatch, process death after commit but before dispatch, worker retry/duplicate, account switch, suppression, historical enable/restore, deduplication, and retained shared fallback.
- [ ] 1.2 Detect confirmed completion transitions at the achievement merge boundary. Verify partial data, already complete baselines, estimates and repeated observations do not alert.
- [ ] 1.3 Register stable progress-alert channels during app initialization before finalization checks eligibility, then add restart-safe delivery and per-channel preferences. Test a first-install/first-enabled event, an existing user-disabled channel without a replacement ID, and denied notification permission. Also verify channel and vibration controls, stable notification keys, account reset and existing achievement/shared-channel behavior; confirm suppression never consumes an in-app claim and stays terminal after later enablement.

## 2. Previous-day recap

- [ ] 2.1 Schedule recap delivery against the current local day and persist its account/date receipt. Verify midnight, daylight-saving, zone changes, delayed jobs and repeat execution.
- [ ] 2.2 Build the recap from canonical dated play and supported unlock evidence. Verify missing coverage and timing estimates remain disclosed.

## 3. Integration

- [ ] 3.1 Exercise Settings through receipt delivery and notification taps on a targeted device or emulator. Verify local progress still appears when notifications are disabled and verify affected baselines.

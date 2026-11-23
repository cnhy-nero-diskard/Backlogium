# Tasks

## 1. Committed alert routing

- [ ] 1.1 Confirm the shared route from the first progressAlerts change. Persist independent account/event receipts at earned-event finalization, post-baseline owned admission (reusing #159's durable admission identity), successful automatic shared-game insertion, and verified completion merge. Test a crash after the owned poll commit, automatic shared-game insert, and verified completion merge, each before notification work is enqueued; after restart verify one eligible alert and no duplicate on worker retry. Also assert no retroactive owned-acquisition alert for the first-sync baseline or completion alert for a first-complete baseline; test restore/replay, foreground acknowledgement, account switch, terminal suppression, and retained shared fallback.
- [ ] 1.2 Detect confirmed completion transitions at the achievement merge boundary and persist the receipt/handoff with that merge. Verify partial data, first complete baselines, estimates and repeated observations do not alert; verify crash recovery after merge and deduplication on retry.
- [ ] 1.3 Register stable progress-alert channels during app initialization before any source event checks eligibility, then add restart-safe delivery and per-channel preferences. Test a first-install/first-enabled event, an existing user-disabled channel without a replacement ID, and denied notification permission. Also verify channel and vibration controls, stable notification keys, account reset and existing achievement/shared-channel behavior; confirm suppression never consumes an in-app claim or replays after later enablement.

## 2. Previous-day recap

- [ ] 2.1 Schedule recap delivery against the current local day and persist its account/date receipt. Verify midnight, daylight-saving, zone changes, delayed jobs and repeat execution.
- [ ] 2.2 Build the recap from canonical dated play and supported unlock evidence. Verify missing coverage and timing estimates remain disclosed.

## 3. Integration

- [ ] 3.1 Exercise Settings through receipt delivery and notification taps on a targeted device or emulator. Verify local progress still appears when notifications are disabled and verify affected baselines.

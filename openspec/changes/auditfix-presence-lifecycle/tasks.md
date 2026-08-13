## 1. Establish the platform facts before designing the fix

- [ ] 1.1 On a real device via `run-on-device`, determine whether `startForegroundService` from a periodic `SteamSyncWorker` execution actually throws while the app is backgrounded, or whether an exemption applies that has not been noticed
- [ ] 1.2 Determine whether `dataSync` is the appropriate `foregroundServiceType` for a service that polls a network API and shows an ongoing now-playing notification, or whether a type with different start rules and budget fits better
- [ ] 1.3 Reach the Android 15 `dataSync` budget on a test device and record precisely what `onTimeout` does, what a subsequent start attempt does, and how long any refusal persists
- [ ] 1.4 Repeat the observations on the oldest and newest API levels the app supports, since the background-start restriction and the runtime budget arrived in different versions
- [ ] 1.5 Write all findings into design.md as evidence — the spec claims in this change rest on them, and the defect being fixed is an assumption about exactly this behaviour
- [ ] 1.6 Choose among design.md Decision 2's options (expedited work, worker-only polling, foreground-only start, different service type) on the basis of 1.1–1.4, and record why

## 2. Stop a refused start from failing the sync

- [ ] 2.1 Guard the `presenceServiceStarter.start()` call at `SteamSyncWorker.kt:89` so a platform refusal cannot propagate, matching the pattern already used for `getPlayerSummaries` at `:80`, genre enrichment at `:192`, and achievement sync at `:246-248`
- [ ] 2.2 Capture the start outcome rather than discarding it — a bare `runCatching` would fix the sync and make the monitoring failure permanently invisible
- [ ] 2.3 Preserve the existing ordering rationale at `:84-87`: presence stays ahead of `getOwnedGames` so a later failure cannot cost player detection
- [ ] 2.4 Test: a throwing starter still yields `Result.success()` when Steam data was retrieved

## 3. Record monitoring outcomes

- [ ] 3.1 Extend `PresenceDecision` recording to cover a refused start, a failed start, a start not attempted, and monitoring ended on a runtime budget
- [ ] 3.2 Make the budget-reached cause distinguishable from a start refusal, since they call for different responses
- [ ] 3.3 Confirm the new records carry no credential values, per the existing `app-diagnostics` redaction requirement
- [ ] 3.4 Verify the records appear in `ui/diagnostics/`, which reads `DiagnosticsDao` directly by documented exception
- [ ] 3.5 Test: each outcome writes its corresponding record

## 4. Implement the chosen start mechanism

- [ ] 4.1 Implement the option selected in task 1.6 in `PresenceServiceStarter`
- [ ] 4.2 If it relies on expedited work, account for expedited quota exhaustion — `SyncScheduler.kt:136` already falls back to non-expedited work, which is precisely when the mechanism would stop working
- [ ] 4.3 If unattended start is not achievable, ensure a foreground start still engages monitoring reliably when the app is opened
- [ ] 4.4 Verify on-device that a game started while the app was never opened is still detected, or record explicitly that this capability is reduced and by how much

## 5. Correct `onTimeout` and its documentation

- [ ] 5.1 Rewrite the comment at `PresenceService.kt:100-106`: the `dataSync` cap is cumulative across a rolling 24-hour window, not continuous runtime — the current wording implies only marathon sessions are affected and concludes wrongly from that
- [ ] 5.2 Remove the claim that the next periodic poll restarts the service and thereby self-heals, replacing it with what task 1.6 established actually happens
- [ ] 5.3 Keep `stopSelf(startId)` — stopping cleanly is the correct response; only the surrounding claim was wrong
- [ ] 5.4 Record the budget-reached presence decision from within `onTimeout`
- [ ] 5.5 Test: `onTimeout` stops the service and writes its record

## 6. Surface unavailable monitoring to the user

- [ ] 6.1 If monitoring cannot resume without foreground interaction, distinguish "monitoring unavailable until reopened" from active monitoring on the live-status surface
- [ ] 6.2 Present it as state rather than error — nothing is broken, but the user believes tracking is finer-grained than it is
- [ ] 6.3 Confirm periodic-poll playtime tracking is visibly unaffected, so the message does not imply playtime is being lost

## 7. Verification and close-out

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green — while noting that a green suite is not evidence for any platform claim in this change
- [ ] 7.2 Complete the on-device verification matrix from section 1 against the implemented fix and record the results
- [ ] 7.3 Confirm a backgrounded periodic sync with monitoring enabled no longer fails, on every API level tested
- [ ] 7.4 Run `openspec validate auditfix-presence-lifecycle`
- [ ] 7.5 Record in the commit message that the previous recovery path was circular — the documented self-heal depended on the very call the platform rejects — since that is the insight worth preserving

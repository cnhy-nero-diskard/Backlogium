## 1. The watch loop

- [ ] 1.1 Add an achievement watcher observing `LiveStatusRepository`'s in-game state, hosted where presence observation runs so it shares presence's lifecycle rather than owning one
- [ ] 1.2 Begin a watch session when presence reports a running game; end it when presence reports any other state, including a different game
- [ ] 1.3 Verify the watch never keeps `PresenceService` alive to finish its own work — a watcher that extends a foreground service turns an opt-in monitor into something the player did not agree to
- [ ] 1.4 Issue no requests at all while not in a game
- [ ] 1.5 Verify the watch stops when the platform ends presence for a runtime-budget reason, without restarting itself

## 2. Cadence

- [ ] 2.1 Implement the back-off as a pure policy — floor, doubling, ceiling, reset-on-unlock — so it is unit-testable without a clock or a service
- [ ] 2.2 Set the floor no shorter than the presence cadence and the ceiling low enough that a long idle session's cost is bounded; document the arithmetic (a floor near a minute and a ceiling near five gives roughly a hundred requests over eight idle hours, about a tenth of what presence spends)
- [ ] 2.3 Reset to the floor on any observation that finds a new unlock
- [ ] 2.4 Unit-test the interval sequence across: an idle session reaching the ceiling, an unlock resetting it, and consecutive unlocks holding it at the floor

## 3. Observation and the snapshot rule

- [ ] 3.1 On each observation, fetch the running game's per-player unlock state for that one app id
- [ ] 3.2 Treat the first observation of a watch session as a baseline: store what it finds, produce no event. This is the rule that prevents a stale or absent stored state producing hundreds of notifications for years-old unlocks
- [ ] 3.3 Diff subsequent observations against the previous observation of the same watch session, not against stored state
- [ ] 3.4 **On an observation that finds a new unlock, fetch that game's global unlock percentages and wait for them before writing anything.** `snapshotPercent` is taken at first observation and never overwritten, and the engine scores XP from the snapshot — an unlocked row written without one is worth zero XP permanently and cannot be repaired by any later sync
- [ ] 3.5 If the globals fetch fails, write nothing for that observation and produce no event; a missed notification is recoverable, a null snapshot is not
- [ ] 3.6 Request no globals on an observation that finds no new unlock
- [ ] 3.7 Serve the achievement schema from storage while it is within its long freshness window, rather than refetching per observation
- [ ] 3.8 Write through the existing merge and persistence path, under the existing per-game refresh serialization, so a watch tick and a sync cannot interleave
- [ ] 3.9 Record a game that exposes no achievements and stop polling it for the remainder of the watch session
- [ ] 3.10 Derive nothing in the watch — no XP, level, streak, session, or playtime. Trigger recompute through the existing path
- [ ] 3.11 Test: globals-fetch failure leaves storage byte-identical and produces no event. This is the change's highest-value test

## 4. The event

- [ ] 4.1 Add an achievements-unlocked event to the closed vocabulary, carrying the game's identity and each observed achievement's identity, sufficient to present without reading storage
- [ ] 4.2 Collapse several achievements found in one observation into one event; produce separate events for separate observations
- [ ] 4.3 Insert it into the priority order between streak milestone and quest met
- [ ] 4.4 Keep it outside the persist/recovery protocol: it is produced from an observation, not from a derived-value transition, so it records no pending transition, is not consumed by transition recovery, and is not reseeded by a delivery baseline
- [ ] 4.5 Verify the sync and the reconciliation pass produce no such event, and that a sync observing an unlock the watch already announced produces no second event
- [ ] 4.6 Verify at-most-once delivery and process-death survival hold for the new kind, as they do for every other

## 5. Delivery

- [ ] 5.1 Present the event in the app when foregrounded, naming the game and its achievements, transiently and without requiring dismissal to keep using the screen beneath
- [ ] 5.2 Post a notification when not foregrounded, on a new channel distinct from `presence` — `presence` is `IMPORTANCE_LOW`, silent, and ongoing, which is wrong for a discrete alerting event
- [ ] 5.3 Treat notification and in-app presentation as two deliveries of one event: whichever lands, acknowledge once, and never present the same event twice
- [ ] 5.4 With no `POST_NOTIFICATIONS` permission, post nothing, surface no error, and leave the event pending for in-app presentation
- [ ] 5.5 Post one notification per event, not one per achievement, and leave the ongoing now-playing notification in place and unchanged
- [ ] 5.6 Open the app on tap without re-presenting the event
- [ ] 5.7 Announce the game and achievements to accessibility services

## 6. Haptics

- [ ] 6.1 Add an achievement-unlock intent to the vocabulary and supply its effect in the authority's mapping, which will not compile until it is
- [ ] 6.2 Make it lighter than the level-up intent — an unlock is the app's most frequent earned moment and must not feel like its rarest
- [ ] 6.3 Deliver it once per event presented in the app, whatever the achievement count
- [ ] 6.4 Deliver nothing for a notification delivery, per "a haptic never fires alone"
- [ ] 6.5 Verify exactly one haptic is delivered when an unlock and a higher-priority event are both pending

## 7. Setting

- [ ] 7.1 Add the watch switch beside the live-monitor switch, defaulting on, stating that it makes additional requests only while a game is running
- [ ] 7.2 Persist it and verify it survives a restart
- [ ] 7.3 Disabling mid-session stops the watch immediately; enabling mid-session begins a session with a baseline observation
- [ ] 7.4 Deliver the existing toggle intent on switching
- [ ] 7.5 Verify it is independent of the live-monitor setting

## 8. Diagnostics

- [ ] 8.1 Record watch observations with a trigger distinguishing them from periodic, manual, post-play, and reconciliation work
- [ ] 8.2 Fold watch requests into the rolling request counters, keyed by route and status
- [ ] 8.3 Record an observation discarded for missing globals, with its outcome, so a repeatedly discarded observation is diagnosable
- [ ] 8.4 Verify a diagnostics failure cannot affect the watch, and that no credential value reaches a record

## 9. Coordination and non-regression

- [ ] 9.1 If `add-post-play-sync` has landed, register both through one presence-triggered seam rather than each observing `LiveStatusRepository` independently, so the ordering between a session ending and a final watch tick is defined rather than accidental
- [ ] 9.2 Confirm no Room schema change, no migration, and no new entity
- [ ] 9.3 Confirm the periodic sync's cadence, the tiered refresh's game selection, and the reconciliation pass's coverage are unchanged
- [ ] 9.4 Confirm presence observation, the now-playing state, the ongoing presence notification, and the recorded live session are unaffected by any watch outcome, including repeated failures
- [ ] 9.5 Confirm no achievement row anywhere ends up unlocked with a null snapshot as a result of this change, on a fixture exercising baseline, normal unlock, and globals failure

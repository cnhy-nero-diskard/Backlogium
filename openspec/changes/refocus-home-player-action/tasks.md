## 1. Next-action presentation model

- [x] 1.1 Add the sealed Home next-action model and deterministic Focus/collection/fallback selector, and verify unit tests cover priority, incomplete filtering, ordered-queue-only collection fallback (skipping non-queue, empty, and completed-queue collections without `nextUp`), current-game suppression, and empty inputs.
- [x] 1.2 Combine the selector into `HomeUiState` using existing local flows only, and verify a ViewModel test observes updates when Focus, collection order, completion, or live status changes.
- [x] 1.3 Add navigation callbacks for next-game, collection, and Library fallback actions, and verify navigation tests resolve each destination without adding a new route type.

## 2. Home hierarchy and collection actions

- [x] 2.1 Implement the compact next-action surface after urgent/live content and before level/quest/streak, and verify Compose semantics expose one heading, one primary action, and no duplicated now-playing game.
- [x] 2.2 Rework the Collections heading so `New` is primary and `View all` plus release-gap planning are labeled secondary actions, and verify all three existing destinations remain reachable in a Compose UI test.
- [x] 2.3 Verify narrow-screen layout through measurement/semantics tests so the next action and collection controls do not clip or create horizontally scrolling controls.

## 3. Accessible collection reordering

- [ ] 3.1 Add transient reorder mode with a visible entry/exit affordance and drag handle, and verify ordinary card taps still open collections outside reorder mode.
- [ ] 3.2 Route drag, move-up, and move-down accessibility actions through one reorder mutation while preserving drag cancel rollback and fresh card content, and verify focused unit tests cover first/middle/last positions plus cancellation.
- [ ] 3.3 Add Compose tests asserting reorder actions, disabled boundary actions, resulting position announcements, and persisted order after leaving/re-entering Home.

## 4. Loading, motion, and localized copy

- [ ] 4.1 Separate first-load from refresh-with-cached-content presentation, and verify tests cover onboarding takeover, first-load placeholders, retained cached content, updating status, and error recovery.
- [ ] 4.2 Gate level-up and streak Lottie playback through reduced-motion state while preserving one-shot haptic/event acknowledgement, and verify reduced-motion and ordinary-motion event tests.
- [ ] 4.3 Move Home labels, plurals, and formatted values to default Android resources (no locale-qualified resources or translations), and verify a non-default-locale test asserts fallback default English copy with locale-aware quantity/date formatting without changing selection logic.

## 5. Verification

- [ ] 5.1 Run `./gradlew.bat :app:testDebugUnitTest --offline --no-daemon` and verify all Home presentation/ViewModel regressions pass.
- [ ] 5.2 Run `./gradlew.bat :app:compileDebugKotlin --offline --no-daemon` and the focused Home connected Compose tests when a device is available; record any unavailable device check without claiming it passed.
- [ ] 5.3 Run `openspec validate refocus-home-player-action --strict` and `git diff --check`, and verify only this change's production/spec/test paths are included.

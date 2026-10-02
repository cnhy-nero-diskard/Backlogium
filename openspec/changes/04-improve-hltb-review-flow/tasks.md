## 1. Baseline Investigation

- [x] 1.1 Reproduce or rule out the reported jump to a previously skipped game against the current app-ID selector; verify a short record of queue order, selected ID, and match outcome before changing selection logic.

## 2. Session Progression

- [ ] 2.1 Add route-session deferred app IDs for explicit Skip and navigation past unresolved games; verify the repository still reports each deferred game as unresolved and automatic selection excludes it.
- [ ] 2.2 Derive next selection from the current ambiguous-plus-unmatched queue by app ID, handling matches, removals, and partition reordering; verify the next unprocessed game is chosen and deferred games are never selected automatically.
- [ ] 2.3 Show exhaustion and Review skipped behavior, and keep scoped completion tied to actual resolution of the requested game; verify last-item skip, no-wrap completion, another pass, and scoped-game deferral.
- [ ] 2.4 Preserve selection and deferred IDs across configuration recreation and temporary detail return, then reset on route dismissal or process restart; verify each session boundary with navigation checks.

## 3. Entry Points

- [ ] 3.1 Add accessible per-game HLTB lookup/review actions to both Library grid densities using the targeted route; verify list and grid parity, correct app ID, and distinct game-opening behavior.
- [ ] 3.2 Add the general Match Center shortcut to Settings Gameplay and wire it through the app shell; verify it opens with and without an attention count.

## 4. Integrated Review

- [ ] 4.1 Exercise ambiguous and unmatched partitions, queue changes, list/both-grid entry, Settings entry, and a scoped single-game route on a device or emulator; verify forward progress, exhaustion, and return behavior match the spec.

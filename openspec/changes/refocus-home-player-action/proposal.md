## Why

Home has strong Backlogium-specific live-play and reward moments, but it answers “how am I doing?” before “what should I play next?” and hides collection reordering behind an inaccessible long-press gesture. Refocusing the screen on the player’s next useful action will make the primary destination more actionable without removing its identity or progress feedback.

## What Changes

- Introduce a prominent next-action surface derived from the current Focus games and collection missions, with a useful empty state when no recommendation is available.
- Reorder Home so the live now-playing state and next action lead, while level, quest, and streak remain visible as supporting progress.
- Replace the crowded collection header actions with a clearer hierarchy for opening all collections, creating one, and planning a release gap.
- Give collection reordering a visible affordance plus TalkBack-accessible move actions; retain direct card opening and scroll behavior.
- Render a stable loading presentation instead of an empty surface, while preserving locally cached content when available.
- Respect reduced-motion settings for level-up and streak celebration while keeping the earned event visible and acknowledgeable.
- Move Home-facing copy into Android string resources without changing the established Steam-native visual identity.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Change Home’s content priority, next-action behavior, collection reorder accessibility, loading presentation, reduced-motion behavior, and localized copy requirements.

## Impact

- Affects `ui/home/HomeScreen.kt`, `HomeRoute.kt`, `HomeViewModel.kt`, Home UI models/presentation helpers, string resources, and Home unit/instrumentation tests.
- Reuses current Focus, collection, smart-collection, gap-plan, and live-status data; no persistence or sync contract changes are expected.
- Keeps the full collections section in its current supporting role; the new next-action surface is a separate concise projection of the most relevant Focus or collection mission. Now-playing remains the top priority while active.

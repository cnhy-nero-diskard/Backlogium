## Context

Home currently composes an optional full-bleed now-playing panel followed by error/monitoring/event cards, level, quest, streak, custom collections, and derived collections. The screen is a plain vertically scrolling column because collection drag-reordering spans multiple cards and can auto-scroll. The existing visual language, persistent progress-event delivery, collection-order durability, and now-playing priority are constraints; see `proposal.md` and `specs/app-ui/spec.md` for the behavior change.

## Goals / Non-Goals

**Goals:**

- Add one deterministic, locally derived next action without creating another recommendation backend.
- Preserve Home’s now-playing identity, progress-event durability, and collection data model.
- Make collection actions and ordering usable through touch and assistive technology.
- Keep content stable through loading and reduced-motion settings.

**Non-Goals:**

- Changing Focus membership, collection membership, pacing calculations, XP, quests, or streak rules.
- Moving the full Collections section ahead of all progress content.
- Adding network calls or persisting a separate recommendation record.

## Decisions

### 1. Derive a sealed next-action presentation in the ViewModel

Add a `HomeNextAction` presentation model with variants for continuing a Focus game, continuing a collection mission, and choosing a game. Resolve it from existing local flows using a stable priority: most recently played incomplete Focus game; otherwise the first incomplete next game from the first ordered-queue collection in the player's collection display order (skipping basic, completion-goal, deadline-goal, empty, and completed-queue collections, which expose no `nextUp`); otherwise the choose-game fallback. Exclude the currently running game to avoid duplicating now-playing.

This keeps selection deterministic and testable. A scoring/recommendation engine was rejected because no critique finding justifies new persistence, ranking inputs, or opaque behavior.

### 2. Use one compact action surface before progress summaries

Compose the next-action surface after now-playing and urgent error/monitoring feedback, but before level/quest/streak. It uses existing game art and collection accent primitives, one primary tap target, and at most one supporting line. The full collections and derived-collections sections remain in their established supporting position.

A carousel was rejected because a single next action reduces decision load and avoids another horizontal gesture inside the vertical screen.

### 3. Separate collection creation from collection utilities

Keep `New` as the primary collection action. Place `View all` and release-gap planning in a labeled overflow or secondary row with sufficient touch targets. The release-gap action remains reachable from Home but no longer reads as a third peer in the heading.

### 4. Add explicit reorder mode and semantic move actions

A visible reorder control enters reorder mode. Cards expose a drag handle for touch and Compose `CustomAccessibilityAction`s for Move up/Move down. Both paths call one reorder operation and retain the current drag baseline/cancellation logic so newer card content is never overwritten by an old snapshot.

Always-visible handles were rejected because they would add noise to every mission card outside the infrequent ordering task.

### 5. Separate first-load and refresh presentation

Carry a renderable cached snapshot independently of the transient loading flag. First load shows fixed-height placeholders matching the Home hierarchy; subsequent refresh keeps the snapshot mounted with a compact updating indicator. The onboarding takeover remains authoritative when credentials are not configured.

### 6. Gate every celebration through reduced-motion state

Use the shared reduced-motion signal before mounting Lottie. In reduced motion, render a static earned badge/card state for at least the same acknowledgement frame and keep haptic attribution/one-shot acknowledgement unchanged.

### 7. Localize at the presentation boundary

Move visible strings and plurals to resources and pass already resolved labels into small presentation helpers where needed. Domain and persistence models remain locale-neutral.

## Risks / Trade-offs

- **[Risk] The next action feels repetitive.** → Suppress duplication with now-playing and use a fallback action rather than repeating the same game across surfaces.
- **[Risk] Reorder mode adds interaction state.** → Keep it transient, exit on navigation, and reuse the proven persistence/cancel path.
- **[Risk] Cached content could appear current during refresh.** → Pair retained content with an explicit updating label and preserve existing error reporting.
- **[Risk] Hierarchy changes regress compact devices.** → Add semantics/layout tests here and rely on the separate visual-regression change for narrow-device goldens.

## Migration Plan

No data migration is required. Introduce the presentation model and tests, add the next-action surface, add reorder mode/accessibility, then replace loading/motion/copy paths. Rollback removes the new presentation layer without touching stored data.

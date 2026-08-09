# Tasks

> **Sequencing:** this change's `Collections section on the Home screen` delta is written against the
> version modified by `refine-collection-pacing-ui` (41/45). Apply this change after that one, and do
> not re-derive the delta from the archived spec — doing so reverts that change's card condensation.

## 0. Deletion guard (independent of the migration; can land first)

- [ ] 0.1 Replace the immediate `viewModel.delete()` in the collection actions menu with a
      confirmation dialog.
- [ ] 0.2 Name the collection in the confirmation and state that its game memberships are removed
      with it.
- [ ] 0.3 Confirm nothing is deleted until the user confirms, and that dismissing or cancelling
      leaves the collection and every membership row unchanged.
- [ ] 0.4 Confirm the confirmed path still removes the collection and its memberships and returns to
      the previous screen as before.

## 1. Reorder gesture spike (gates section 4)

- [ ] 1.1 Prototype long-press-then-drag on a collection card inside Home's existing
      `Column(...).verticalScroll(...)`. Confirm a plain swipe beginning on a card scrolls the
      screen and does not pick the card up, and that a long press then drag reorders.
- [ ] 1.2 Confirm a dragged card can reach positions outside the current viewport, auto-scrolling the
      container as it approaches an edge.
- [ ] 1.3 If arbitration or auto-scroll is unworkable here, stop and evaluate restructuring Home's
      collections section as a lazy list on its own merits, per design.md. Do not restructure Home
      silently as part of this task.

## 2. Schema

- [ ] 2.1 Add `description: String?` and a display-order column to the `Collection` entity.
- [ ] 2.2 Write `MIGRATION_12_13` following the file's hand-written pattern, adding both columns.
- [ ] 2.3 Seed the display order from `createdAt ASC, id ASC` in the migration, so existing installs
      see an unchanged Home order on first launch after upgrade.
- [ ] 2.4 Test the migration with rows sharing a `createdAt`, confirming the tie resolves by `id` and
      the seeded order is deterministic.
- [ ] 2.5 Bump the database version and confirm no other table is affected.

## 3. Data layer

- [ ] 3.1 Change the collections query to order by the display-order column instead of `createdAt`.
- [ ] 3.2 Add `reorderCollections(orderedIds)` mirroring the existing `reorderMembers`, rewriting the
      sequence transactionally.
- [ ] 3.3 Assign a position to newly created collections without disturbing existing relative order.
- [ ] 3.4 Confirm deleting a collection leaves the remaining relative order intact.
- [ ] 3.5 Carry `description` through `create` and `updateDetails`.
- [ ] 3.6 Extend `CollectionDaoTest` for ordering, reordering, new-collection placement, and
      deletion leaving order intact.

## 4. Home reordering

- [ ] 4.1 Present cards in stored display order.
- [ ] 4.2 Implement long-press-then-drag reordering per the spike's outcome, persisting on release.
- [ ] 4.3 Confirm releasing at the original position persists nothing.
- [ ] 4.4 Present no reordering affordance when only one collection exists.
- [ ] 4.5 Confirm the active-play glow added by `refine-collection-pacing-ui` follows its card
      through a reorder and is not disturbed mid-animation.
- [ ] 4.6 Check the gesture against `ui/util/ReducedMotion.kt`, consistent with the app's other
      animated surfaces.

## 5. Description

- [ ] 5.1 Add an optional description field to the management form.
- [ ] 5.2 Persist null for never-described and preserve the distinction from a cleared-to-empty
      description.
- [ ] 5.3 Render the description on the collection overview.
- [ ] 5.4 Confirm the description is not rendered on the Home card.
- [ ] 5.5 Decide the overview's treatment of a very long description per design.md's open questions
      — a max line count with expansion rather than a stored length limit.

## 6. Validation

- [ ] 6.1 Walk every scenario in `specs/custom-collections/spec.md` and `specs/app-ui/spec.md`.
- [ ] 6.2 Verify order, description, and memberships all survive a sync poll.
- [ ] 6.3 Verify order survives an app restart.
- [ ] 6.4 Verify an upgrade from a pre-migration database presents collections in their previous
      order.
- [ ] 6.5 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [ ] 6.6 `openspec validate improve-collection-organization --strict`.

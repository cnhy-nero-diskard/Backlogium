## Why

Collections have accumulated modes, accents, deadlines, and pacing, but the collection itself is
still barely organizable. Three gaps:

**Deleting a collection is unguarded.** The action fires immediately from a dropdown:

```kotlin
DropdownMenuItem(
    text = { Text("Delete collection") },
    onClick = { showActions = false; viewModel.delete() },
)
```

One mis-tap in a menu destroys a collection and every membership row under it, with no confirmation
and no undo. Collections are hand-curated app-owned state absent from the Steam payload, so nothing
restores them — a resync cannot, because the sync worker never touches this table.

**Collection order is fixed at creation time.** `CollectionDao` orders by
`createdAt ASC, id ASC` and the `collections` table has no order column, so the Home list is
permanently in the order the collections happened to be made. A collection created first outranks
the one being actively played. Members within a collection already have `orderIndex` and a
`reorderMembers` mutation, so the concept exists one level down but not at the collection itself.

**A collection carries no description.** Its name is the only place to record what it is for, so
distinguishing two similarly-named collections, or remembering the intent behind one made months
ago, is not possible.

## What Changes

- Add a persisted display order to collections, reorderable from Home by press-and-hold then drag.
- Add an optional description to a collection, editable in the management form and shown on the
  collection overview.
- Require confirmation before deleting a collection, naming the collection and stating that its
  memberships go with it.

**Not in scope:** the description does **not** appear on the Home collection card. That card is
being deliberately condensed by `refine-collection-pacing-ui` around name, one status line, compact
progress, and three thumbnails; adding a description there would work against a change that is
nearly complete. The description belongs to the overview, where there is room for it.

Also not in scope: undo for deletion (confirmation is the guard chosen here), collection nesting or
grouping, sorting collections by anything derived, and the management form's overall density.

## Capabilities

### Modified Capabilities

- `custom-collections`: `Collection persistence` gains the description and the display-order field.
  A new requirement covers collection-level ordering, mirroring the existing
  `Ordered-queue sequencing` requirement that governs member order.
- `app-ui`: `Collection management screen` gains the description field and requires confirmation
  before deletion. `Collections section on the Home screen` gains press-and-hold drag reordering.

### New Capabilities

None.

## Impact

**Schema**

Room is at `version = 12` with hand-written migrations `MIGRATION_1_2` through `MIGRATION_11_12`;
there is no `autoMigration` in use. Both new fields land on the `collections` table in a single
`MIGRATION_12_13`, following the established pattern.

- `description: String?` — nullable; existing rows migrate as null, which is distinct from an empty
  description the user typed and then cleared.
- A display-order column — existing rows must be seeded in their current `createdAt ASC, id ASC`
  order so the Home list is unchanged on first launch after upgrade.

**Affected code**

- `data/local/entity/Collection.kt`, `data/local/BacklogiumDatabase.kt` — the two fields and the
  migration.
- `data/local/dao/CollectionDao.kt` — ordering moves off `createdAt` onto the new column; a
  reorder mutation is added, mirroring the existing `reorderMembers`.
- `data/repo/CollectionRepository.kt` — `create`/`updateDetails` carry the description;
  a `reorderCollections` sibling to `reorderMembers`.
- `ui/home/HomeScreen.kt` — the drag gesture. ⚠️ Home is a plain
  `Column(...).verticalScroll(rememberScrollState())` and the collection cards are a `forEach`
  inside it, not a `LazyColumn`. A long-press drag inside a scrolling column must arbitrate with
  that scroll or the two gestures fight.
- `ui/collections/CollectionScreen.kt` — the description field, the overview's rendering of it, and
  the delete confirmation.

**Risk**

The reorder gesture is the uncertain part and is called out as a spike. Nothing else here is
architecturally novel — both fields are additive columns, and member-level reordering is existing
precedent to follow rather than invent.

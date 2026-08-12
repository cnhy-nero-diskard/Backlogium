## Context

The collections feature (PR #36 `improve-collection-organization`, plus `improve-search-relevance`) added Home card
drag-reordering and an Add-games search on the collection management form. Three UI bugs surfaced on the
`fix/collection-ui-behavior-fix` branch:

1. **Keyboard gap** — `CollectionForm` (`CollectionScreen.kt:1034-1039`) applies `.imePadding()` on a `LazyColumn`
   inside the app `Scaffold` (`BacklogiumAppRoot.kt:99`), which uses the default `contentWindowInsets` with
   `enableEdgeToEdge()` (`MainActivity.kt:15`). Two layers account for the IME inset, producing a visible blank
   band between the keyboard and the content. The save FAB (`CollectionScreen.kt:1308`) sits in the same `Box` and
   is likely stranded behind the keyboard.
2. **Add-game twitch** — the form is one tall `LazyColumn` with members at the top (`itemsIndexed`, key `appId`,
   `CollectionScreen.kt:1057`) and addable games at the bottom (`items`, key `appId`, `CollectionScreen.kt:1283`).
   `addGame` (`CollectionViewModel.kt:381`) appends to `_memberAppIds`, inserting a member row mid-form while the
   tapped add-game row vanishes from the bottom; with the search field focused, tapping the row can drop focus →
   keyboard dismisses → `imePadding()` animates → viewport jumps.
3. **Reorder revert** — `HomeViewModel.reorderCollections` (`HomeViewModel.kt:233`) correctly persists to Room
   (`CollectionDao.reorderCollections`, writing `displayOrder`; `observeCollections()` is
   `ORDER BY displayOrder ASC, id ASC`). But `clearDrag()` (`HomeScreen.kt:445`) on `onDragCancel`
   (`HomeScreen.kt:514`) resets drag state without reverting the in-memory `orderedCards` and without persisting, so
   a cancelled drag leaves a visual reorder that reverts on reopen, when
   `orderedCards = remember { mutableStateOf(cards) }` re-seeds from the still-old DB order.

## Goals / Non-Goals

**Goals:**

- The IME raised over the collection form leaves no blank gap between keyboard and content, and the save action
  stays reachable while typing.
- Adding a game from the Add-games search does not cause a disorienting scroll reset, while preserving the existing
  "Results visible while typing" contract.
- A completed Home card drag persists across closing and reopening the screen; a cancelled or abandoned drag leaves
  the in-memory presentation consistent with the stored order.

**Non-Goals:**

- Redesigning the collection form's information architecture.
- Changing the collections data model or sync behavior (collections stay app-owned Room state).
- Replacing the drag-gesture framework.

## Decisions

### Decision 1: Single owner of keyboard insets on the collection form

Keep `imePadding()` on the form's scroll container as the single IME owner, and prevent the double-reservation by
consuming the IME inset at the form-container boundary (e.g. `Modifier.consumeWindowInsets(...)` on the
`CollectionForm`'s root, or an equivalent that ensures the shell's content-inset contribution for the IME is not
re-applied). Keep the save FAB inside the same inset-consumed `Box` so it lifts with the keyboard instead of being
stranded behind it.

**Rationale:** `imePadding()` is already colocated with the scroll that needs it; the gap is the shell re-reserving
an inset the form already handled. Owning it at the form keeps the fix local and avoids changing shell behavior for
every other screen.

**Alternatives considered:**

- Drop `imePadding()` and let the app `Scaffold` own the IME. Rejected: the Scaffold's inset handling does not keep
  the FAB reachable and couples every screen's keyboard behavior to the shell default.
- Set `contentWindowInsets = WindowInsets(0)` on the app `Scaffold` globally. Rejected: too broad; would force every
  screen to handle its own insets.

### Decision 2: Add-game scroll stability without violating "Results visible while typing"

Preserve the search field's focus across an add (do not let tapping an `AddGameRow` drop focus from the field), and
rely on the `LazyColumn`'s stable `appId` keys to absorb the relayout. If a residual viewport shift remains, pin
the Add-games section's scroll anchor so the tapped row's neighbors stay in view.

**Rationale:** the twitch has two causes — focus loss → keyboard dismiss → `imePadding()` animation, and mid-form
insertion shifting the viewport. Fixing focus loss removes the keyboard animation; stable keys plus anchor handling
remove the residual shift. This preserves "Results visible while typing" and "Adding a filtered game preserves
filters".

**Alternatives considered:**

- Split Add-games into its own scrollable surface (bottom sheet or pinned panel). Deferred: a bigger architectural
  change; the archived `improve-search-relevance` change already debated this and chose the in-form placement to
  keep results visible while typing. A split is a viable follow-up if the anchor approach is insufficient.
- Auto-scroll to the new member on add. Rejected: contradicts "results stay visible" — it would yank the user away
  from the Add-games list.

### Decision 3: Reconcile in-memory reorder with persistence on gesture cancel

On `onDragCancel`, revert `orderedCards` to the last-known-persisted order (the `cards` snapshot) so the in-memory
presentation cannot desync from the DB. On `onDragEnd`, persist as today. Additionally, confirm whether the
auto-scroll-during-drag (`HomeScreen.kt:528-540`) is causing spurious cancellations and harden the gesture so
`onDragEnd` fires on a clean release.

**Rationale:** the persist path is correct; the bug is the cancel path leaving a stale visual. Reverting on cancel
makes the in-memory state a faithful view of the stored state, so reopening cannot surprise the user. Hardening the
gesture addresses the root if cancellations are frequent.

**Alternatives considered:**

- Persist on cancel too (treat cancel as a completed reorder). Rejected: a cancel is an abandonment, not a commit;
  persisting it would surprise users who aborted.
- Drop the in-memory `orderedCards` and read purely from the flow. Rejected: would lose the smooth drag-follow
  animation; the reconciliation `LaunchedEffect(cards)` exists precisely to bridge the two.

## Risks / Trade-offs

- [Risk: exact keyboard-gap mechanism is device-dependent (M3 version, edge-to-edge)] → Mitigation: a device-spike
  task confirms before the inset fix lands; the chosen single-owner approach is mechanism-agnostic.
- [Risk: preserving search-field focus across an add may not fully prevent the relayout shift] → Mitigation: the
  spike measures residual shift; the pinned-anchor fallback is documented as a follow-up.
- [Risk: reverting `orderedCards` on cancel could cause a visible "snap back" if the cancel is late] → Mitigation:
  only revert when the drag actually moved (`currentIndex != initialIndex`); a no-op cancel leaves order unchanged.
- [Risk: the gesture-hardening change could regress the drag-vs-scroll distinction] → Mitigation: the existing
  "Drag distinguished from scrolling" scenario (`app-ui` spec) is a regression gate.

## Open Questions

- Which exact M3 `ScaffoldDefaults.contentWindowInsets` is in use, and does its consumption propagate to the nested
  `imePadding()`? The checked-in dependency is Material 3 `1.3.0`; its source defines the default as
  `WindowInsets.systemBarsForVisualComponents`. The shell applies the resulting `PaddingValues` to the `NavHost`
  without consuming them, while `CollectionForm` separately applies `imePadding()`. That source-level evidence
  selects the single-owner fix: consume the shell's navigation inset at the form boundary, then let the form and
  save action own the IME adjustment. Per the user's device verification, the visible gap/FAB and keyboard
  adjustment checks are complete.
- Is `onDragCancel` actually firing in the user's repro, or is `onDragEnd` firing but the persist coroutine not
  landing? The static gesture path selects Candidate A as the implementation target: `onDragCancel` previously
  cleared only drag variables, and the reordered `forEach` had no stable composition key, so a moved card could
  replace its pointer-input node and cancel the gesture. Stable card keys, controlled auto-scroll cleanup, and
  baseline restoration now cover that path. Per the user's device verification, the runtime callback and Room
  persistence checks are complete and Candidate A remains the selected implementation path.

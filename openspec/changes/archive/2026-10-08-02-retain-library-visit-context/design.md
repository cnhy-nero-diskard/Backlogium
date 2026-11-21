## Context

See `proposal.md` for the motivation and `specs/app-ui/spec.md` for the visit contract. `LibraryViewModel` currently owns transient `LibraryFilters`, and `LibraryScreen` clears them from `DisposableEffect.onDispose`. That callback cannot distinguish a pushed detail/review route from a tab departure. Top-level navigation uses `saveState`/`restoreState`; the screen uses a `LazyColumn` for all three densities, but its scroll state is not explicitly managed. The app already observes process foreground transitions for presence checks.

## Goals / Non-Goals

**Goals:**

- Give Library discovery context one owner whose lifetime covers destination disposal and activity configuration recreation, but ends with the process.
- Make tab departure, backgrounding, return, and expiry explicit events so all entry paths use the same five-minute rule.
- Restore a useful list or grid position without reviving stale filters after expiry.

**Non-Goals:**

- Persisting a Library visit across process death, changing sort/density storage, or preserving multi-select and open dialogs as discovery context.
- Implementing the other issue #167 requests (wishlist search, added-recency sort, Hide dialog copy) or the separate navigation and reviewer changes in #157/#163.

## Decisions

### Own visit state above the Library destination

Use an activity-retained, process-local visit state holder shared by the app shell and Library presentation. It owns the filter value, a scroll anchor for the active density, the absence start, and a visit generation. `LibraryViewModel` reads and updates the shared filters instead of clearing them on composition disposal. Library selection and temporary dialogs keep their existing shorter lifetime. This avoids depending on whether a saved navigation entry happens to retain a particular ViewModel instance. An alternative was to keep all state in the destination ViewModel and infer departures in `onDispose`; that cannot distinguish detail/review navigation from a tab switch.

### Classify departures at the shell boundary

The shell records when the user leaves the Library flow for another top-level tab. A route pushed from Library remains in the Library flow; the same detail route opened from Home does not. Observe process `ON_STOP`/`ON_START` separately for background departures. Set the absence start only if one is not already set, so backgrounding while on another tab cannot extend the window. On return to the Library flow, compare the clock before showing restored context: below 300,000 ms resumes and clears the absence marker; at or above it increments the visit generation and clears filters and scroll. A later departure after a successful return starts a new interval. An alternative was a scheduled five-minute timer; return-time evaluation is simpler and does no work while the app is away.

### Use process-local elapsed time

Measure the interval with Android's monotonic elapsed-realtime clock. It is unaffected by wall-clock edits and survives activity recreation. Do not persist the timestamp or visit data; a new process starts with a fresh visit. The alternative, wall-clock time stored in preferences, would retain transient search state across cold launches and make clock changes alter expiry.

### Restore scroll from a stable content anchor

Capture the first useful visible game or section plus offset and density when Library leaves the foreground, and restore against the current filtered content when it returns. If the anchor is gone or grouping has changed, clamp to the nearest valid position. All three densities share a lazy column, but grid row keys include row position, so retaining only a raw row index is insufficient when results reorder. A new visit generation resets the lazy scroll state to the top before the fresh Library is displayed. Explicit Clear controls continue to change filters immediately.

## Risks / Trade-offs

- [A route is mistaken for a Library child] → Track entry origin for pushed routes rather than classifying by route name alone; cover detail/review return from Library and detail entered from Home.
- [Tab and process lifecycle events arrive close together] → Keep the earliest absence timestamp and make return/expiry handling idempotent.
- [Content changes during absence] → Restore by item identity where possible, with a valid-position fallback in list and both grids.
- [Foreground lifecycle delivery is slightly delayed] → Use the lifecycle transition timestamp as the start of a true background interval and keep the threshold decision deterministic at return.

## Migration Plan

No stored-data migration or service rollout is required. Replace the disposal-based filter reset and its old reset-contract checks as part of the implementation; retained sort and density settings remain compatible. Rolling back the app restores the prior in-memory behavior without data conversion.

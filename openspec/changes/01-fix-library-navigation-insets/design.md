## Context

See `proposal.md` for the reported symptoms and `specs/app-ui/spec.md` for the target behavior. `BacklogiumAppRoot` currently hides the bottom bar through several route exceptions, and its `Scaffold` supplies padding to `NavHost`. `LibraryScreen` adds its own list padding. The reported blank strip has not yet been reproduced on a device, so the design must begin with measured screenshots and inset inspection.

## Goals / Non-Goals

**Goals:**

- Make bottom-bar visibility follow a small, explicit top-level route set.
- Give the shell and child content a clear bottom-padding ownership rule across visible and hidden bar states.
- Preserve access to the final Library item and system Back across route transitions.

**Non-Goals:**

- Changing Android's system navigation bar, redesigning the five top-level tabs, or changing Library visit-state timing.

## Decisions

### Reproduce before choosing the inset fix

Capture the current Library in list and both grids under gesture and three-button navigation, then inspect the shell's `innerPadding`, any child bottom padding, and system inset values while the bar is visible and hidden. Fix the layer that owns the duplicated or stale space revealed by evidence. The alternative of removing one apparent padding value immediately could make the final row overlap the bar on another navigation mode.

### Use an allowlist for app-bar visibility

Compute visibility from the exact five top-level destination routes, with Settings overview treated as the top-level Settings surface. All pushed destinations, including setup, onboarding, HLTB review, and diagnostics, fall outside that set. This replaces a growing exclusion list that can show the bar by accident when new routes are introduced. The shell still leaves unconfigured top-level screens reachable, preserving their setup guidance.

### Keep transitions and padding in agreement

The shell remains responsible for app-bar height and system inset coordination. Child screens consume the space the shell supplies without adding a second copy of the same inset. If bar animation would retain bottom padding after the route changes, adjust the transition so the visible bar and content viewport settle together; verify the final item throughout the transition. The alternative of adding a Library-only spacer masks the symptom and does not address pushed screens.

## Risks / Trade-offs

- [The gap differs by device navigation mode] → Inspect gesture and three-button layouts before changing inset ownership.
- [A newly added pushed route shows the bar] → Derive visibility from the top-level allowlist and cover representative routes in navigation checks.
- [Animation briefly blocks the last item or Back] → Check entry and return transitions on a device, including increased font size and both themes.

## Migration Plan

No stored-data migration is needed. This is a shell/layout change; a rollback restores the prior route visibility and padding behavior.

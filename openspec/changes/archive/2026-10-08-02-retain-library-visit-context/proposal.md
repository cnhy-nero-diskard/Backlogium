## Why

Returning from game detail or HLTB review currently clears Library discovery filters because the screen resets them when its composition is disposed. Players also need a short window to switch tabs or background the app without losing the search they were using.

## What Changes

- Preserve the Library query, active filters, and useful scroll position while opening and returning from pushed screens, including game detail and HLTB review, and across configuration recreation.
- Treat switching to another top-level tab or backgrounding the app as a temporary departure. Restore the same Library visit if the player returns in less than five minutes; start a fresh visit at five minutes or later.
- Start a fresh visit after a cold launch. Keep explicit Clear actions and the existing persisted sort and density choices.
- Define the five-minute boundary in the Library contract so route and lifecycle changes do not clear discovery context accidentally.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Revise Library search and filter visit lifetime, and specify restoration or expiry of Library discovery context across navigation and app backgrounding.

## Impact

- Planning scope: the Library visit-state portion of issue #167. `03-extend-library-discovery` covers its remaining requests; `01-fix-library-navigation-insets` and `04-improve-hltb-review-flow` cover the other two threads in index #174's second recommendation.
- Expected implementation areas: `BacklogiumAppRoot.kt`, top-level navigation, `LibraryScreen.kt`, `LibraryViewModel.kt`, and app foreground lifecycle observation.
- No data migration or network API change is expected.

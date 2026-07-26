# Establish the data-source seam

## Why

Cloud sync is coming (roadmap Phase 5), and the intended shape is that a backend polls Steam while
the app only reads. Whether that ships in three months or twelve, one thing determines how much it
costs: whether the UI depends on *repositories* or on *storage*.

Right now it partly depends on storage. ViewModels import Room entities and map them themselves:

| Leak | Where |
|---|---|
| `Achievement` (Room entity) mapped to UI | `GameDetailViewModel` |
| `Session` (Room entity) mapped to UI | `HistoryViewModel` |
| `Game` (Room entity) exposed by the repository | `GameRepository.library` / `goalGames` / `backlog` |
| `HltbMatchStatus` (Room entity enum) | `LibraryViewModel`, `LibraryScreen` |
| `HltbData` read directly in the ViewModel | `LibraryViewModel` |
| `SettingsDataStore` injected into ViewModels | `HomeViewModel`, `GameDetailViewModel` |

Each one is a screen reaching past the repository layer into the storage schema. That is fine while
there is exactly one data source. It becomes the whole cost of the project the moment there are two —
because a second source cannot satisfy a contract expressed in Room types.

The prize is that this codebase is otherwise already well set up for it. `:gamification` is pure with
no Android, Room, or network dependencies. `HltbDataSource` is an explicit interface whose doc comment
already says *"a server-side proxy could replace it without touching any consumer."* The pattern
exists; it just is not applied uniformly.

## What Changes

- **Repositories expose domain models, not Room entities.** Each repository gains plain data types of
  its own, and no `data.local.entity` type appears above the `data/` layer.
- **ViewModels stop reading storage directly.** Settings access moves behind a repository, so
  `SettingsDataStore` is not injected into ViewModels.
- **The rule is written down** — one short section in the README stating the boundary, so it holds
  without being remembered.

Nothing about behavior changes. No screen looks different.

## Capabilities

No behavior changes. This is an internal boundary cleanup; no capability's requirements change.

## Impact

- **Affected code:** `GameRepository`, `SessionRepository`, `AchievementRepository`, `HltbRepository`,
  `ProfileRepository` gain domain models; `HomeViewModel`, `LibraryViewModel`, `GameDetailViewModel`,
  `HistoryViewModel`, `HltbReviewViewModel` consume them instead of entities; `LibraryScreen` and
  `HltbReviewScreen` stop importing data-layer types.
- **No migration, no new dependencies, no Firebase code.** Room, Retrofit, and WorkManager stay
  exactly where they are.
- **No engine change.** `:gamification` is already pure and stays untouched.
- **Sequencing:** cheapest done incrementally, screen by screen, alongside the UI changes already
  proposed on this branch — each of those changes touches a ViewModel anyway.

## Non-goals

- **Any Firebase, Firestore, or Cloud Functions code.** This change deliberately contains none. Its
  entire purpose is to make that work cheap later, not to start it now.
- **Gradle product flavors.** A `local`/`cloud` flavor split is the right mechanism when a second
  implementation exists. Adding it now would be ceremony around a single implementation.
- **Extracting repository interfaces.** Concrete classes with domain-model returns are enough; the
  interface can be extracted when there is a second implementation to satisfy it. Interfaces with one
  implementor are speculative structure.
- **A separate git branch for cloud work.** Explicitly rejected — see design.
- **Moving the gamification engine server-side.** A significant decision with real consequences,
  discussed in design but not taken here.
- **Renaming or restructuring Room entities.** They stay as they are, behind the seam.

# Tasks

- [x] Refresh the root README around current features, setup, architecture, roadmap, and safety notes.
- [x] Update the screen descriptor where it describes stale app navigation or top-level surfaces.
- [x] Apply small forward-facing wording touchups only when they directly support the refreshed docs.
- [x] Run practical validation for a documentation/copy-only change.

Validation:

- `git diff --check --` passed.
- `.\gradlew.bat testDebugUnitTest` was attempted but did not reach compilation because the worktree
  has no Android SDK location configured (`ANDROID_HOME` or `local.properties` `sdk.dir`).

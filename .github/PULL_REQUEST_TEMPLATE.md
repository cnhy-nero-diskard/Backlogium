## Summary

<!-- What does this PR do and why? -->

## Changes

<!-- Bullet the notable changes -->
-

## Release note

<!-- Add 1-2 short bullets describing the user-visible result in plain language. Keep each bullet
under 180 characters, because anything longer is cut mid-sentence when the notes are published. Do
not mention implementation details. If this PR changes nothing users see, write exactly `None`.
Notes on a pull request whose title has no `feat:`, `fix:` or `perf:` prefix are filed under
Maintenance, so use a conventional prefix when the change is user-visible. -->
-

## Testing

<!-- Check what you ran locally. CI runs all of these anyway; this list exists so the
slow ones (Roborazzi, instrumented) get caught before you push, not after. -->
- [ ] `./gradlew test` passes (Android unit tests)
- [ ] `./gradlew lintDebug` passes
- [ ] Roborazzi: this PR touches no rendered UI, **or** baselines were re-recorded and
  `:app:verifyRoborazziDebug` passes — see `docs/visual-regression-screenshots.md`.
  Covered paths are listed in `FIXTURE_RENDERED_SOURCES` in
  `scripts/check_visual_baseline.py`; a covered change that provably moves no pixel
  gets the `no-golden-change` label instead of new PNGs.
- [ ] `npm run build` + `npm test` in `functions/` (if Cloud Functions changed)
- [ ] Manually verified on device/emulator (if UI change)

## Notes

<!-- Anything reviewers should know: follow-ups, tradeoffs, screenshots. If this PR edits a
screen the Roborazzi goldens render, state whether the baseline PNGs were re-recorded or why
`no-golden-change` applies. -->

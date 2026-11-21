## 1. Evidence and presentation model

- [x] 1.1 Capture leading-game, comparison, empty, tied-game, missing-artwork, and current-calendar overview states on device/emulator; deliver labelled evidence and record whether the reported banner confusion is reproduced.
- [x] 1.2 Bind the featured app ID, name, minutes, artwork, represented dates, and headline to one presentation snapshot; verify identity matching, deterministic ties, headline-type changes, and rapid range changes in focused mapping checks.

## 2. Visible scope

- [x] 2.1 Add visible most-played/game-minute wording and a distinct all-games aggregate block; verify the leading game's minutes cannot be mistaken for whole-period totals in a screenshot.
- [x] 2.2 Add precise represented-period context and matching semantics; verify elapsed-to-date labels, locale formatting, existing all-time notes, and retained old-snapshot labels during recomputation.
- [x] 2.3 Preserve artwork fallback and readable role/date text on narrow screens with larger fonts; verify no stale featured panel survives a comparison or empty headline and no image implies current play or completion.

## 3. Acceptance

- [x] 3.1 Run relevant Analytics headline/window/presentation tests and `./gradlew.bat :app:compileDebugKotlin :app:lintDebug`; verify arithmetic, hidden exclusions, attribution, comparison eligibility, and request behavior remain unchanged.
- [x] 3.2 Inspect revised device/emulator states in both themes and larger font scale, including screen-reader scope; record evidence that visible labels and accessibility descriptions convey the same facts.
- [x] 3.3 Coordinate marker placement with draft #176, then run `openspec.cmd validate 3c-clarify-analytics-featured-game-scope --strict` and `git diff --check`; verify the whole existing modified insight requirement and its scenarios are retained and `4c` can reuse the scope convention.

# Tasks

## 1. Carry the game's identity into the header

- [x] 1.1 Widen `ProfileHeaderUiState` to carry the running game's name alongside its presence state,
      taking it from the `NowPlaying.InGame` identity the repository already resolves.
- [x] 1.2 Confirm no new repository read is introduced — the flow is already combined in
      `ProfileHeaderViewModel` and only the field is being kept.
- [x] 1.3 Ensure the `"App <id>"` fallback that `LiveStatusRepository` uses when Steam reports no
      `gameextrainfo` does not reach the header as if it were a title; an unresolved name presents
      the in-game state without a name.

## 2. Header presentation

- [x] 2.1 Extend `presenceLabel` so the in-game state names the running game.
- [x] 2.2 Apply the currently-playing accent to the game's name in the presence line.
- [x] 2.3 Keep the in-game state stated in words, so the accent is never the only carrier.
- [x] 2.4 Ellipsize the presence line as the persona name above it already does, so a long game name
      cannot widen the row or displace the sync indicator.
- [x] 2.5 Confirm the presence line returns to its non-playing state, with no accent, when play ends.

## 3. Home suppression

- [x] 3.1 Suppress only the game's name on Home, using the existing `onHome` signal that already
      drives the header's `transparent` flag. Do not add a field to the ViewModel state for this.
- [x] 3.2 Confirm the header, avatar, persona name, and in-game state all remain present on Home.
- [x] 3.3 Confirm Home's now-playing panel is unchanged and still carries the game's identity.
- [x] 3.4 Confirm navigating from Home to another top-level destination reveals the name, and
      returning to Home hides it again, without the header re-loading.

## 4. Library

- [x] 4.1 Apply the currently-playing accent to the currently-played game's name in the Library.
- [x] 4.2 Keep the existing dot and its `Currently playing` content description, so the state is
      carried by more than colour.
- [x] 4.3 Confirm no other game's name is accented, and that the accent clears when play ends.
- [x] 4.4 Confirm the accent does not compete with the selection border or a row's other colour.

## 5. Accent verification

- [x] 5.1 Check `PlayingIndicator` and `PlayingIndicatorLight` as body text against their surfaces in
      both schemes. The token was chosen as a dot colour, where contrast requirements are looser than
      for text.
- [x] 5.2 If either fails as text, adjust the text usage rather than shipping an illegible name, and
      leave the dot's existing colour alone.
- [x] 5.3 Settle design.md's open question on whether the Library dot stays once the name is accented.
      Keeping it is the default; removing it requires another non-colour carrier in its place.

## 6. Validation

- [x] 6.1 Walk every scenario in `specs/app-ui/spec.md` for both requirements.
- [x] 6.2 Verify the header on every top-level destination while in a game, and on Home.
- [x] 6.3 Verify an offline launch and the unconfigured state are unaffected.
- [x] 6.4 Verify the state is perceivable with colour discrimination simulated or disabled.
- [x] 6.5 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [x] 6.6 `openspec validate refine-live-status-presentation --strict`.

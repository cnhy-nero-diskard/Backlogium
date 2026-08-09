# Tasks

## 1. Carry the game's identity into the header

- [ ] 1.1 Widen `ProfileHeaderUiState` to carry the running game's name alongside its presence state,
      taking it from the `NowPlaying.InGame` identity the repository already resolves.
- [ ] 1.2 Confirm no new repository read is introduced — the flow is already combined in
      `ProfileHeaderViewModel` and only the field is being kept.
- [ ] 1.3 Ensure the `"App <id>"` fallback that `LiveStatusRepository` uses when Steam reports no
      `gameextrainfo` does not reach the header as if it were a title; an unresolved name presents
      the in-game state without a name.

## 2. Header presentation

- [ ] 2.1 Extend `presenceLabel` so the in-game state names the running game.
- [ ] 2.2 Apply the currently-playing accent to the game's name in the presence line.
- [ ] 2.3 Keep the in-game state stated in words, so the accent is never the only carrier.
- [ ] 2.4 Ellipsize the presence line as the persona name above it already does, so a long game name
      cannot widen the row or displace the sync indicator.
- [ ] 2.5 Confirm the presence line returns to its non-playing state, with no accent, when play ends.

## 3. Home suppression

- [ ] 3.1 Suppress only the game's name on Home, using the existing `onHome` signal that already
      drives the header's `transparent` flag. Do not add a field to the ViewModel state for this.
- [ ] 3.2 Confirm the header, avatar, persona name, and in-game state all remain present on Home.
- [ ] 3.3 Confirm Home's now-playing panel is unchanged and still carries the game's identity.
- [ ] 3.4 Confirm navigating from Home to another top-level destination reveals the name, and
      returning to Home hides it again, without the header re-loading.

## 4. Library

- [ ] 4.1 Apply the currently-playing accent to the currently-played game's name in the Library.
- [ ] 4.2 Keep the existing dot and its `Currently playing` content description, so the state is
      carried by more than colour.
- [ ] 4.3 Confirm no other game's name is accented, and that the accent clears when play ends.
- [ ] 4.4 Confirm the accent does not compete with the selection border or a row's other colour.

## 5. Accent verification

- [ ] 5.1 Check `PlayingIndicator` and `PlayingIndicatorLight` as body text against their surfaces in
      both schemes. The token was chosen as a dot colour, where contrast requirements are looser than
      for text.
- [ ] 5.2 If either fails as text, adjust the text usage rather than shipping an illegible name, and
      leave the dot's existing colour alone.
- [ ] 5.3 Settle design.md's open question on whether the Library dot stays once the name is accented.
      Keeping it is the default; removing it requires another non-colour carrier in its place.

## 6. Validation

- [ ] 6.1 Walk every scenario in `specs/app-ui/spec.md` for both requirements.
- [ ] 6.2 Verify the header on every top-level destination while in a game, and on Home.
- [ ] 6.3 Verify an offline launch and the unconfigured state are unaffected.
- [ ] 6.4 Verify the state is perceivable with colour discrimination simulated or disabled.
- [ ] 6.5 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [ ] 6.6 `openspec validate refine-live-status-presentation --strict`.

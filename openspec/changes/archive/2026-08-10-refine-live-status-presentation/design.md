# Design — Live status presentation

## Context

The data already exists. `LiveStatusRepository` resolves `NowPlaying.InGame(gameId, name, iconUrl)`,
and `live-status` guarantees the game's identity is part of in-game state. What is missing is that
`ProfileHeaderViewModel` narrows it away:

```kotlin
ProfileHeaderUiState(
    presence = live.presence,   // LivePresence enum — no game identity
    ...
)
```

`ProfileHeader` then maps `LivePresence.IN_GAME -> "In game"`.

The accent already exists too: `PlayingIndicator` / `PlayingIndicatorLight`, reached through
`ColorScheme.playingIndicator`, with a comment stating it is deliberately distinct from
`RarityUncommon`'s sage so the two are not read as the same signal. It is used twice — a 6dp dot on a
Library row (carrying `contentDescription = "Currently playing"`) and as a fallback glow colour for
collection cards.

The Home suppression needs no new signal: `BacklogiumAppRoot` already computes `onHome` and passes
`transparent = onHome || accentColor != null` into the header.

This change is therefore almost entirely subtraction of a narrowing, plus a colour application.

## Goals / Non-Goals

**Goals:**
- Name the running game where the app already says a game is running.
- Make the existing accent mean "this is the game you are playing", consistently.
- Avoid repeating the game's name on Home, where the now-playing panel already carries it.

**Non-Goals:**
- Changing detection, polling cadence, or persistence — `live-status` owns those.
- Changing Home's now-playing panel, the ongoing notification, or the collection-card glow.
- Adding a new colour token.

## Decisions

- **Widen `ProfileHeaderUiState` to carry the running game's name, rather than deriving it in the
  composable.** The state gains the name; `presenceLabel` consumes it.
  *Why:* the composable has no access to `LiveStatusRepository`, and giving it one would put a
  repository read inside a presentation function. The ViewModel already combines that flow — it is
  only discarding the field.
  *Alternative rejected:* keeping `LivePresence` and looking the name up separately in the header —
  two sources for one state, able to disagree mid-poll.

- **The Home suppression is a presentation flag on the header, not a second state.** Reuse the
  existing `onHome` signal that already drives `transparent`.
  *Why:* the ViewModel's state should describe what is true, not where it is being rendered. A
  `hideGameName` field on the state would make the same state object mean different things depending
  on the screen. The header already takes a presentation flag for exactly this kind of
  screen-dependent difference.

- **Only the game's *name* is suppressed on Home — not the header, not the presence line.** The
  header keeps the avatar, persona name, and in-game state.
  *Why:* the redundancy is specifically the game's name appearing twice within a few dp. The
  identity strip is shell furniture that `Steam profile header` requires to persist across
  navigation, and removing it on one tab would be a larger and unrequested change.

- **Currently-playing must never be colour-only, and the spec says so as a requirement rather than a
  note.**
  *Why:* accenting a game's name makes colour a carrier of meaning where previously the signal was a
  dot with an accessible description. `0xFF4ADE80` against the theme's `OnNavy` is a hue difference,
  not a strong luminance one, so a user who cannot distinguish it would lose the state entirely on any
  surface where the name alone were accented. The codebase already applies this discipline to motion —
  the reduced-motion sync indicator is drawn static rather than dropped, "never the only thing carrying
  the state" — and this is the same rule for colour.
  *Consequence:* the Library keeps its dot; the header's presence line keeps naming the in-game state
  in words rather than relying on the accent to say it.

- **The accent is applied to the game's *name*, not to whole rows or containers.**
  *Why:* the accent's existing meaning is "this specific game is running". Tinting a row's surface
  would compete with the Library's selection border and the collection accent tints, which already own
  surface-level colour on those cards.

## Risks / Trade-offs

- **Contrast of the accent as text.** `PlayingIndicator` was chosen as a dot colour, where contrast
  requirements are looser than for text. → Verify the light and dark variants as body text against
  their surfaces, and adjust the token's text usage if it fails rather than accepting an illegible
  name. The token has a light counterpart already, so both schemes need checking.

- **A long game name in the header.** The presence line sits under a persona name that already
  ellipsizes, in a row that must leave room for the sync indicator. → The identity column already takes
  the slack and ellipsizes; the presence line must do the same rather than pushing the row wider.

- **Name resolution lag.** `LiveStatusRepository` falls back to `"App $gameId"` when Steam reports no
  `gameextrainfo`. → The spec requires presenting the in-game state without a name rather than showing
  an app id, so that fallback must not reach the header as if it were a title.

## Migration Plan

None. No schema, no persisted state, no settings key. Revertable by restoring the narrowed projection.

## Resolved Questions

- **The Library's currently-playing dot remains.** The accented name identifies the game visually,
  while the existing dot and its `Currently playing` content description provide the non-colour
  signal required for accessibility. Removing the dot would require another equivalent carrier.
- **The accent passes as body text in both schemes.** `PlayingIndicator` against `NavySurface` is
  9.79:1, and `PlayingIndicatorLight` against `LightSurface` is 4.73:1; both meet the 4.5:1 body-text
  threshold, so the existing dot colors remain unchanged.

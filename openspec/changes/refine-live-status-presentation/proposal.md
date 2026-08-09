## Why

The app knows which game is running and barely says so.

`live-status` already resolves in-game state *with the game's identity* — `LiveStatusRepository`
produces `NowPlaying.InGame(gameId, name, iconUrl)`. But the shell's profile header throws the
identity away: `ProfileHeaderViewModel` projects `presence = live.presence`, a bare enum, and the
header renders it as the word `In game`. So on every screen except Home, the app displays that a game
is running while withholding which one, despite holding the answer.

The green reserved for this state is barely used. `PlayingIndicator` (`0xFF4ADE80`) exists with light
and dark variants and a `colorScheme.playingIndicator` accessor, and appears in exactly two places: a
6dp dot on a Library row, and as the fallback glow color for a collection card. The game's own name —
the thing the color is about — is rendered in the same color as every other game's.

On Home the opposite problem applies. Home already presents a full-bleed now-playing panel with the
game's name, art, and elapsed time, directly beneath the header. The header naming the same game
immediately above it is redundant.

## What Changes

- Carry the running game's identity into the profile header's state, instead of discarding it.
- Show the running game's name in the header's presence line, alongside the in-game state.
- Use the existing currently-playing green for the running game's name in the header and wherever a
  game is identified as currently playing.
- Omit the game's name from the header on Home only, where the now-playing panel directly below
  already carries it. The header itself, its avatar, and its persona name remain on Home unchanged.
- Require that currently-playing is never conveyed by color alone, so the state survives for users who
  cannot distinguish the green.

**Not in scope:** how in-game state is detected, polled, or persisted — `live-status` governs that and
is unchanged. Home's now-playing panel, the ongoing notification, and the collection-card glow are
also unchanged.

## Capabilities

### Modified Capabilities

- `app-ui`: `Steam profile header` currently requires only that the header "reflects the in-game
  presence state". It gains the running game's name, the accent treatment, and the Home exception.

### New Capabilities

None. The cross-surface treatment of a currently-playing game is added as a new requirement within
`app-ui`.

## Impact

**Affected code**

- `ui/shell/ProfileHeaderViewModel.kt` — `ProfileHeaderUiState` carries `presence: LivePresence`, an
  enum with no game identity. It needs the name; the repository already has it, so this is widening a
  projection rather than adding a data source.
- `ui/components/ProfileHeader.kt` — `presenceLabel()` maps `IN_GAME -> "In game"`; it needs the name
  and the accent, plus the Home suppression.
- `ui/BacklogiumAppRoot.kt` — the header already receives `transparent = onHome || accentColor != null`.
  The same `onHome` signal drives the suppression, so no new plumbing is required.
- `ui/library/LibraryScreen.kt` — the currently-playing game's name takes the accent alongside the
  existing dot.

**No data or schema impact**

Nothing is persisted. `live-status` explicitly holds now-playing as a transient signal that is never
stored, and this change does not alter that.

**Risk**

Low, with one accessibility caveat: applying a color to a game's name makes color a carrier of
meaning. The existing dot carries a `Currently playing` content description, and the change must keep
a non-color signal wherever the accent is applied rather than letting the color become the only
indication.

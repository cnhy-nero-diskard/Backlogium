## MODIFIED Requirements

### Requirement: Steam profile header
The system SHALL present a persistent profile header at the top of the app shell showing the
player's Steam avatar, persona name, and current presence state, and the header SHALL render
from locally stored identity so it is populated on an offline launch.

While the player is in a game, the header's presence line SHALL name the running game alongside the
in-game state, using the app's currently-playing accent. On the Home screen the header SHALL omit the
game's name, because Home's now-playing panel directly beneath it already carries that game's
identity; the header, its avatar, and its persona name SHALL otherwise remain present and unchanged
on Home. When the running game's name is unavailable, the header SHALL present the in-game state
without a name rather than a placeholder.

#### Scenario: Viewing the profile header
- **WHEN** any top-level screen is shown while credentials are configured
- **THEN** the header displays the player's Steam avatar and persona name

#### Scenario: Header persists across navigation
- **WHEN** the user navigates between top-level destinations
- **THEN** the header remains visible without re-loading

#### Scenario: Offline launch
- **WHEN** the app is launched without network after at least one successful sync
- **THEN** the header displays the last known persona name and avatar rather than a blank or
  loading state

#### Scenario: Identity not yet synced
- **WHEN** no persona name or avatar has been stored yet
- **THEN** the header displays a neutral fallback presentation instead of an empty or broken
  avatar

#### Scenario: Avatar image unavailable
- **WHEN** the avatar image fails to load
- **THEN** the header displays a themed fallback in place of the image and remains legible

#### Scenario: Presence reflected while in game
- **WHEN** the player is currently in a game and live status is being observed
- **THEN** the header reflects the in-game presence state

#### Scenario: Running game named in the header
- **WHEN** the player is in a game and a screen other than Home is shown
- **THEN** the header's presence line names that game alongside the in-game state, in the
  currently-playing accent

#### Scenario: Game name omitted on Home
- **WHEN** the player is in a game and the Home screen is shown
- **THEN** the header does not name the game, while the avatar, persona name, and header itself
  remain present, and Home's now-playing panel continues to carry the game's identity

#### Scenario: Running game name unavailable
- **WHEN** the player is in a game whose name has not resolved
- **THEN** the header presents the in-game state without a name, rather than a placeholder or an
  app id

#### Scenario: Presence line returns to normal after play
- **WHEN** the player stops playing
- **THEN** the header's presence line returns to its non-playing state with no game name and no
  currently-playing accent

#### Scenario: Hidden while unconfigured
- **WHEN** Steam credentials are not configured
- **THEN** no profile header is shown, and the onboarding takeover is presented as it is today

#### Scenario: App XP level not duplicated
- **WHEN** the header is shown
- **THEN** it does not present a level number, so the player's Steam level is never confused
  with the app's own XP level

## ADDED Requirements

### Requirement: Currently-playing game presentation
Where a surface identifies a specific game as currently being played, it SHALL render that game's
name in the app's currently-playing accent, distinguishing it from every other game named on the same
surface. The accent SHALL be the same one used for the existing currently-playing indicator, so one
colour carries this meaning throughout the app.

The currently-playing state SHALL NOT be conveyed by colour alone. Every surface applying the accent
SHALL also carry a non-colour signal for the same state — an indicator, a label, or an accessible
description — so the state remains perceivable without colour discrimination.

The accent SHALL apply only while the game is actually running, and SHALL be removed when play ends.

#### Scenario: Playing game named in the accent
- **WHEN** a surface lists games and one of them is currently being played
- **THEN** that game's name is rendered in the currently-playing accent and the others are not

#### Scenario: One accent for one meaning
- **WHEN** more than one surface identifies the currently-played game
- **THEN** each uses the same currently-playing accent, rather than a per-surface colour

#### Scenario: State perceivable without colour
- **WHEN** a surface marks a game as currently playing
- **THEN** the state is also carried by a non-colour signal, so it is perceivable without
  distinguishing the accent

#### Scenario: Accent removed when play ends
- **WHEN** the player stops playing a game
- **THEN** that game's name returns to its normal presentation on every surface that had accented it

#### Scenario: No game playing
- **WHEN** no game is currently being played
- **THEN** no game name carries the currently-playing accent

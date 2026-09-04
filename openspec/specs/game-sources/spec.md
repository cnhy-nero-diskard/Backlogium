# game-sources

## Purpose

Defines how tracked games are sourced, admitted, removed, converted, and timed when they are
owned by the player or played through Steam Family Sharing.

## Requirements

### Requirement: Every tracked game declares its source
Each tracked game SHALL carry a source stating how the app came to track it ??? owned in the
player's Steam library, or played through Family Sharing without being owned. Existing behaviour
SHALL apply to a game regardless of source except where this specification states otherwise.

#### Scenario: An owned game
- **WHEN** a game is present in the player's Steam library
- **THEN** its source is owned

#### Scenario: A borrowed game
- **WHEN** a game has been admitted after being played without being owned
- **THEN** its source is family-shared

#### Scenario: Source does not restrict participation
- **WHEN** a family-shared game accumulates tracked playtime
- **THEN** that playtime earns XP, counts toward the daily quest, and contributes to streaks on the
  same terms as an owned game's

### Requirement: A played but unowned game is admitted automatically
The system SHALL admit a game as family-shared when it is observed being played, is confirmed
absent from the player's Steam library by a completed sync, and is confirmed by Steam's store to be
a game. Where any condition is unmet the game SHALL NOT be admitted, and the observation SHALL be
reconsidered on a later occasion.

#### Scenario: First observation of a borrowed game
- **WHEN** presence reports a game the app does not track, a sync has since confirmed it is not in
  the player's library, and the store confirms it is a game
- **THEN** it is admitted as family-shared, with its name, artwork, and genres

#### Scenario: A game not yet synced is not mistaken for a borrowed one
- **WHEN** presence reports a game the app does not track and no sync has completed since it was
  first observed
- **THEN** it is not admitted, and it arrives normally if the next sync reports it as owned

#### Scenario: A shared application that is not a game
- **WHEN** presence reports an unowned app id that Steam's store does not identify as a game
- **THEN** it is not admitted

#### Scenario: Verification unavailable
- **WHEN** an unowned app id cannot be verified because the store cannot be reached
- **THEN** it is not admitted, and it is reconsidered the next time it is observed

#### Scenario: Admission happens once
- **WHEN** an already-admitted family-shared game is observed again
- **THEN** no duplicate game is created

### Requirement: A newly admitted game is announced
The system SHALL notify the player when it admits a family-shared game, naming the game. Admission
SHALL NOT occur silently.

#### Scenario: New game admitted
- **WHEN** a family-shared game is admitted
- **THEN** the player is notified that it was added, and it is named

#### Scenario: Subsequent play is not announced again
- **WHEN** an already-admitted family-shared game is played again
- **THEN** no admission notification is issued

### Requirement: Manual admission preserves source safety
A Settings-initiated import SHALL confirm the submitted app id is absent from the configured
account's current `GetOwnedGames` response and is a game according to the Steam Store before
creating a Family Shared row. It SHALL respect existing tracked rows and sticky exclusions.

#### Scenario: Current owned-library check passes
- **WHEN** a submitted app id is absent from `GetOwnedGames`, is not tracked or excluded, and the
  Store verifies it as a game
- **THEN** the same Family Shared game shape used by automatic admission is persisted

#### Scenario: Owned, tracked, or excluded
- **WHEN** the submitted app id is owned, already tracked, or excluded
- **THEN** no duplicate or wrongly sourced row is created and the reason is returned to Settings

#### Scenario: No authoritative answer
- **WHEN** either the owned-library request or Store verification cannot provide an authoritative
  answer
- **THEN** nothing is imported

### Requirement: Session mechanism is determined by source
Sessions for a game with Steam-reported playtime SHALL be synthesized by diffing that playtime.
Sessions for a game without Steam-reported playtime SHALL be derived from observed presence. No
game SHALL be subject to both mechanisms.

#### Scenario: An owned game
- **WHEN** sessions are synthesized for an owned game
- **THEN** they come from playtime diffing, and no presence-derived session is created for it

#### Scenario: A family-shared game
- **WHEN** a family-shared game is observed in presence over successive observations
- **THEN** an open session is derived and extended, and closed once it is no longer observed

#### Scenario: A derived session is an ordinary session
- **WHEN** a session has been derived from presence
- **THEN** it participates in XP, quests, streaks, history, and analytics identically to a
  playtime-derived session

#### Scenario: No overlap
- **WHEN** any game's sessions are examined
- **THEN** they originate from exactly one mechanism

### Requirement: Tracked time for a shared game is disclosed as observed, not total
Where playtime for a family-shared game is presented, the system SHALL convey that it reflects
only play the app observed, and SHALL NOT present it as the player's complete time in that game.

#### Scenario: Viewing a shared game's playtime
- **WHEN** a family-shared game's tracked playtime is shown
- **THEN** it is presented as the time the app observed rather than as a Steam total

#### Scenario: Play while unobserved
- **WHEN** a family-shared game is played while the app is neither in the foreground nor monitoring
  presence in the background
- **THEN** no session is derived for that play, and the game's tracked time is unchanged

#### Scenario: The remedy is offered
- **WHEN** the disclosure is shown and background presence monitoring is not enabled
- **THEN** the player is pointed at the setting that would improve coverage

### Requirement: A shared game can be removed and stays removed
The player SHALL be able to remove a family-shared game. A removed game SHALL NOT be re-admitted
when it is next played, and the player SHALL be able to reverse a removal. Reversing a removal
SHALL restore the game immediately, without waiting for it to be observed being played again.

#### Scenario: Removing a shared game
- **WHEN** the player removes a family-shared game
- **THEN** it no longer appears among tracked games

#### Scenario: Removal survives further play
- **WHEN** a removed game is played again
- **THEN** it is not re-admitted and no admission notification is issued

#### Scenario: Reversing a removal
- **WHEN** the player reverses a removal
- **THEN** the game is immediately tracked again as family-shared, appears in the library and
  in collection choices, and no longer appears in the removed-games list

#### Scenario: Reversal does not wait for play
- **WHEN** the player reverses a removal and the game is not currently being played
- **THEN** it is still restored, because the player's reversal is the admission decision and
  requiring a future observation would leave the setting looking inert

#### Scenario: Owned games are unaffected
- **WHEN** removal is considered for a game whose source is owned
- **THEN** it is not offered, because the game's presence in the library is not the app's to decide

### Requirement: Buying a shared game converts it in place
When a family-shared game appears in the player's Steam library, the system SHALL change its source
to owned, retain its existing sessions, and begin diffing its playtime from a baseline of the total
reported at conversion, creating no sessions for playtime accrued before that point.

#### Scenario: A borrowed game is purchased
- **WHEN** a sync reports an admitted family-shared game as owned
- **THEN** its source becomes owned and its existing sessions are retained

#### Scenario: No phantom session on conversion
- **WHEN** conversion occurs and Steam reports a large lifetime playtime for the newly owned game
- **THEN** that total is stored as the diffing baseline and no session is created from it

#### Scenario: Diffing resumes normally after conversion
- **WHEN** playtime increases after a conversion
- **THEN** sessions are synthesized from those increases as for any owned game

#### Scenario: History is continuous across conversion
- **WHEN** the game's history is viewed after conversion
- **THEN** sessions recorded while it was shared remain present and attributed to that game

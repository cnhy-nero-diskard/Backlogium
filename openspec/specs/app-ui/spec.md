# app-ui

## Purpose

Defines the Android app's UI behavior: the app shell and navigation, the Steam profile
header, the Home screen,
visual theming, typography, iconography, game art states, celebratory animations, the
Library screen, the History screen, and sync feedback in the app shell.

## Requirements

### Requirement: App shell and navigation
The system SHALL present a Compose UI with navigation between Home, Library, History, Analytics,
and Settings screens, and all screens SHALL render from locally stored state so the app
is fully usable offline.

#### Scenario: Offline launch
- **WHEN** the app is opened without network
- **THEN** all screens display the last synced state and never block on a network call

#### Scenario: Navigating between screens
- **WHEN** the user selects a destination from the app's navigation
- **THEN** the corresponding screen (Home, Library, History, Analytics, or Settings) is shown

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

### Requirement: Live indicator on the running game in the Library
The Library SHALL mark the game the player is currently in with a live indicator, regardless of which
section that game appears in, and SHALL mark no game when the running game cannot be identified.

#### Scenario: Running game marked
- **WHEN** the player is in a game that is present in the stored library
- **THEN** that game's Library row displays a live indicator

#### Scenario: Marked in either section
- **WHEN** the running game belongs to the tracked set, or to the remaining games
- **THEN** it is marked in whichever section it appears in

#### Scenario: Ordering unaffected
- **WHEN** a game is marked as running
- **THEN** its position in the list is unchanged, so the user's chosen sort order is preserved

#### Scenario: Running game not identifiable
- **WHEN** the player is in a game whose identity cannot be resolved to a game in the stored library
- **THEN** no game is marked, rather than marking a game matched by name

#### Scenario: Not in a game
- **WHEN** the player is not in a game
- **THEN** no Library row displays a live indicator

#### Scenario: Indicator cleared when presence ends
- **WHEN** presence observation stops
- **THEN** no row continues to display a live indicator

### Requirement: Sync-in-progress feedback in the app shell
The system SHALL indicate an in-flight sync in the app shell's profile header, so the cue is
visible from every top-level destination rather than only from the screen carrying the sync
trigger. The indicator SHALL reflect both scheduled and manually triggered syncs. Because a
sync can complete in well under a second, the indicator SHALL remain visible long enough to be
perceptible rather than flickering. The indicator SHALL respect the platform's reduced-motion
preference and SHALL NOT rely on motion as its only cue.

#### Scenario: Manual sync reflected in the header
- **WHEN** the user triggers a manual sync from Settings
- **THEN** the profile header shows the sync indicator until that sync completes

#### Scenario: Scheduled sync reflected in the header
- **WHEN** a periodic background sync runs while the app is in the foreground
- **THEN** the profile header shows the sync indicator for that sync as well

#### Scenario: Indicator visible across destinations
- **WHEN** a sync is in flight and the user navigates between top-level destinations
- **THEN** the indicator remains visible, because it belongs to the shell rather than to a screen

#### Scenario: Very fast sync remains perceptible
- **WHEN** a sync completes almost immediately after being enqueued
- **THEN** the indicator is still displayed for a perceptible minimum duration rather than
  appearing and vanishing within a frame or two

#### Scenario: Idle state
- **WHEN** no sync is in flight
- **THEN** the header shows no sync indicator and the header's identity content is unaffected

#### Scenario: Reduced motion honored
- **WHEN** the platform reports a reduced-motion preference
- **THEN** the indicator conveys the in-flight state without continuous animation

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress, today's daily
quest status, the current streak, and a "Now playing" indicator reflecting the player's current
in-game state. While the player is in a game, the "Now playing" indicator SHALL be the most
visually prominent element on Home, presenting enlarged game art, the game's name, and the elapsed
session time, in a color lane distinct from the accent reserved for milestone moments, and SHALL
convey its active state through motion as well as color. Home SHALL present progress content only: account, sync, and data-management
controls belong to the Settings screen and SHALL NOT appear on Home. When a sync has failed,
Home SHALL surface the error together with an action that retries the sync, so a failure is
recoverable without leaving the screen. When credentials are not configured, the Home screen
SHALL present the onboarding flow as a full-screen takeover rather than a static "Steam not
configured" message. The streak count SHALL reflect the streak through the last completed day,
extended only once today's quest is actually met, and SHALL NOT read as broken solely because
today's quest has not yet been met while the day is still in progress.

#### Scenario: Viewing progress
- **WHEN** the Home screen is shown while configured
- **THEN** it displays current level with progress toward the next level, whether today's quest is
  met, and the current streak count

#### Scenario: Administration controls absent from Home
- **WHEN** the Home screen is shown while configured
- **THEN** it does not display the Steam account card, the manual sync trigger, the last-sync
  time, or the Steam history import

#### Scenario: Retrying a failed sync from Home
- **WHEN** the last sync failed and the Home screen displays the resulting error
- **THEN** the error presentation offers a retry action that enqueues a new sync

#### Scenario: Error cleared after a successful retry
- **WHEN** a retry triggered from Home completes successfully
- **THEN** the error presentation is no longer shown

#### Scenario: Now playing shown while in-game
- **WHEN** the live status reports the player is in a game
- **THEN** the Home screen shows a "Now playing" indicator with the running game's name
  (and its icon when resolvable)

#### Scenario: Now playing hidden when not in-game
- **WHEN** the live status reports the player is not in a game
- **THEN** the Home screen does not show a "Now playing" indicator

#### Scenario: Onboarding takeover when not configured
- **WHEN** no credentials are configured
- **THEN** the Home screen presents the onboarding flow (API key entry, then SteamID entry) as a
  full-screen takeover instead of a dead-end "Steam not configured" message

#### Scenario: Streak shown before today's quest is met
- **WHEN** today's quest has not yet been met and today is still in progress
- **THEN** the Streak card shows the streak count carried in from the last completed day, not zero,
  and does not present today as already having extended it

#### Scenario: Streak extends once today's quest is met
- **WHEN** today's quest becomes met
- **THEN** the streak count increases to include today, the same way it would for any other met day

#### Scenario: Streak breaks only once a day has concluded unmet
- **WHEN** a day ends without its quest ever being met (beyond any configured grace)
- **THEN** the streak count resets to zero starting from the next day, not while that day was still
  in progress

#### Scenario: Prominent now-playing card
- **WHEN** the player is in a game and Home is shown
- **THEN** an enlarged card presents the game's art, its name, and the elapsed session time, and is
  visually distinct from the other cards on Home

#### Scenario: Elapsed time advances
- **WHEN** the now-playing card is displayed during a session
- **THEN** the elapsed time advances continuously without requiring a network response

#### Scenario: Milestone accent not diluted
- **WHEN** the now-playing card is displayed
- **THEN** it does not use the accent color reserved for level-up, streak-milestone, and
  completion moments

#### Scenario: Card conveys an active state through motion
- **WHEN** the now-playing card is displayed
- **THEN** it presents continuous, ambient motion that distinguishes an active session from a static
  card, without competing with the app's celebratory milestone animations

#### Scenario: Reduced motion respected
- **WHEN** the system indicates that animations should be reduced or disabled
- **THEN** the card is presented without motion, and the active state remains legible from its elapsed
  time and its presence alone

#### Scenario: No motion when not in a game
- **WHEN** the player is not in a game
- **THEN** no now-playing animation runs

#### Scenario: Elapsed time not presented as exact
- **WHEN** elapsed session time is shown
- **THEN** it is presented as time since the session was detected, not as an exact game-launch time

### Requirement: Ongoing now-playing notification
The system SHALL present an ongoing notification naming the currently played game and its elapsed
session time while the player is in a game, and SHALL remove it once the game ends.

#### Scenario: Notification shown while playing
- **WHEN** the player is in a game
- **THEN** an ongoing notification names the game and shows the elapsed session time

#### Scenario: Elapsed time kept current
- **WHEN** the session continues
- **THEN** the notification's elapsed time is updated without repeatedly alerting the user

#### Scenario: Notification removed when the game ends
- **WHEN** the player is no longer in a game
- **THEN** the notification is removed

#### Scenario: Notification permission not granted
- **WHEN** notification permission has never been granted
- **THEN** no notification is posted, no error is surfaced, and in-app now-playing presentation is
  unaffected

#### Scenario: Opening the app from the notification
- **WHEN** the user taps the notification
- **THEN** the app is opened

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection. Each card SHALL foreground the collection name, one concise mode-relevant status line, and a structured progress surface when applicable without rendering a separate uppercase mode label. The mode icon SHALL remain visually and accessibly identifiable. Cards SHALL use an elevated surface distinct from the Home background, remain visually separated, and use a stored accent to tint card and accent affordances. Tapping a card SHALL open its collection overview. The section SHALL render from locally stored state, present an empty state when no collections exist, and SHALL NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces.

Cards SHALL be presented in the collection's stored display order, and the user SHALL be able to
change that order by pressing and holding a card and dragging it to a new position. The reordering
gesture SHALL be distinguishable from the section's own scrolling, so neither gesture triggers the
other. A completed reorder SHALL be persisted so the new order is present on the next visit. The
collection description SHALL NOT be rendered on the Home card, which stays limited to the name,
one status line, progress, and member thumbnails.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection with its name, mode icon, and concise mode-relevant state

#### Scenario: Cards shown in stored order
- **WHEN** the Home collections section is shown
- **THEN** the cards appear in the collection's stored display order

#### Scenario: Reordering a collection card
- **WHEN** the user presses and holds a collection card and drags it to another position
- **THEN** the card follows the drag, the other cards move aside, and on release the new order is
  persisted

#### Scenario: Reordered collections persist
- **WHEN** the user reorders collections and later returns to Home
- **THEN** the collections are presented in the order the user left them

#### Scenario: Drag distinguished from scrolling
- **WHEN** the user scrolls the Home screen with a swipe that begins on a collection card
- **THEN** the screen scrolls and no card is picked up for reordering

#### Scenario: Reorder abandoned
- **WHEN** the user picks up a card and releases it at its original position
- **THEN** the order is unchanged and no reorder is persisted

#### Scenario: Single collection
- **WHEN** only one collection exists
- **THEN** it presents no reordering affordance, since there is no other position to move it to

#### Scenario: Description absent from the Home card
- **WHEN** a collection has a stored description
- **THEN** its Home card does not render that description, keeping the card limited to name, status
  line, progress, and member thumbnails

#### Scenario: Cards visually separated
- **WHEN** two or more collection cards are shown
- **THEN** consecutive cards are separated by visible spacing

#### Scenario: Mode-specific card surfaces
- **WHEN** a collection's mode is completion goal, deadline goal, or ordered queue
- **THEN** its card presents compact structured information relevant to that mode without an uppercase
  mode heading or a multi-detail sentence

#### Scenario: Healthy deadline card stays quiet
- **WHEN** a reliable complete deadline plan is on track
- **THEN** its Home card shows concise countdown and progress information without buffer or corrective copy

#### Scenario: At-risk deadline card shows required pace
- **WHEN** a reliable complete deadline plan is at risk
- **THEN** its Home card replaces verbose estimate detail with one concise required-pace or attention state

#### Scenario: Incomplete forecast is not expanded on Home
- **WHEN** a collection forecast is incomplete because history or HLTB estimates are missing
- **THEN** Home uses at most a compact incomplete state and leaves the detailed explanation to the collection overview

#### Scenario: Completion-goal trophy summary
- **WHEN** a collection's mode is completion goal and achievement counts exist for one or more
  members
- **THEN** its Home card shows aggregate unlocked out of total trophies and the remaining count

#### Scenario: Accent tint applied
- **WHEN** a collection has a stored accent
- **THEN** its card surface and accent affordances use a low-opacity tint from that accent while
  its text remains legible

#### Scenario: Cards remain compact
- **WHEN** the Home screen shows multiple collection cards
- **THEN** each card uses compact internal padding and consecutive cards have visible spacing without
  excessive vertical gaps

#### Scenario: Collection member thumbnail preview
- **WHEN** a Home collection card has one or more members
- **THEN** the card shows up to three small member-game thumbnails in stored member order on the right

#### Scenario: Collection member thumbnail overflow
- **WHEN** a Home collection card has more than three members
- **THEN** the card shows three thumbnails followed by the number of remaining members using the existing `N+` convention, such as `8+` for an eleven-game collection

#### Scenario: Default styling without accent
- **WHEN** a collection has no stored accent
- **THEN** its card presents the default neutral styling

#### Scenario: Opening an existing collection
- **WHEN** the user taps a collection card on Home
- **THEN** a read-only overview of that collection is opened, with its selected games and local
  collection metrics visible before customization controls

#### Scenario: No collections
- **WHEN** the Home screen is shown and no collections exist
- **THEN** the collections section presents an empty state rather than an empty list

#### Scenario: Offline rendering
- **WHEN** the Home screen is shown without network
- **THEN** the collections section renders from the last stored state without blocking

#### Scenario: Existing Home surfaces preserved
- **WHEN** the collections section is shown on Home
- **THEN** the level, XP, daily-quest, streak, and now-playing surfaces remain present and unchanged

### Requirement: Active-play collection glow
Every visible Home collection card containing the currently played game's app id SHALL display a faint border glow while that game is active. The glow SHALL use the collection accent when available and the themed live-playing color otherwise, SHALL animate drawing without changing card dimensions, and SHALL fade after play ends. Motion SHALL NOT be the only indication when reduced motion is requested.

#### Scenario: Played game belongs to one collection
- **WHEN** live status identifies a game contained by a visible Home collection
- **THEN** that collection card displays a slow faint pulsating border glow

#### Scenario: Played game belongs to multiple collections
- **WHEN** the active game belongs to more than one visible collection
- **THEN** every matching collection card displays the glow concurrently

#### Scenario: Played game belongs to no collection
- **WHEN** the active game belongs to no visible collection
- **THEN** no collection card displays an active-play glow

#### Scenario: Play ends
- **WHEN** live status transitions from the matching game to not playing
- **THEN** the matching collection glow fades out rather than disappearing in a single frame

#### Scenario: Reduced motion
- **WHEN** the platform requests reduced motion while a matching game is active
- **THEN** the collection card uses a static faint outline or equivalent non-animated cue

### Requirement: Collection game-card surface treatments
Game cards inside collection overview and management surfaces SHALL use a surface treatment appropriate to their layout. Horizontal collection-list and management cards SHALL use the same right-aligned, low-opacity Steam `header.jpg` treatment and horizontal fade behavior as Library game cards. If `header.jpg` fails, the renderer SHALL try `library_hero.jpg`, `capsule_616x353.jpg`, `hero_capsule.jpg`, and `library_600x900.jpg` in that order. Library and collection overview grid tiles SHALL use Steam's portrait `hero_capsule.jpg` artwork derived from the app id as their primary image instead of a thumbnail-plus-faded-header composition. If the hero capsule fails, the renderer SHALL try `library_hero.jpg`, `library_600x900.jpg`, `header.jpg`, and `capsule_616x353.jpg` in that order. The collection accent, text, metrics, and controls SHALL remain legible, and exhaustion of every candidate SHALL leave a complete themed surface rather than a broken image state.

#### Scenario: Header art is available on a horizontal card
- **WHEN** a collection member with a Steam header-art URL is rendered in a horizontal collection or management card
- **THEN** its card shows the artwork aligned to the right and fading out before the primary text region

#### Scenario: Grid uses Steam hero capsule artwork
- **WHEN** a game is rendered in the Library or collection overview's grid density
- **THEN** its tile uses the game's portrait `hero_capsule.jpg` artwork as the primary image, with its name and density-appropriate metadata below, without a faded full-card header image

#### Scenario: Header art falls back on a horizontal card
- **WHEN** a collection member's `header.jpg` fails in a horizontal collection or management card
- **THEN** the loader tries `library_hero.jpg` first, followed by the remaining ordered wide, portrait, and library assets

#### Scenario: All horizontal artwork is unavailable
- **WHEN** every horizontal background candidate fails
- **THEN** the game card retains the normal themed surface without a broken-image placeholder

#### Scenario: Hero capsule artwork is unavailable
- **WHEN** a game has no usable `hero_capsule.jpg` artwork and is rendered in a grid
- **THEN** the loader tries `library_hero.jpg` first, then the remaining ordered Steam assets, while retaining the same tile geometry

#### Scenario: All grid artwork is unavailable
- **WHEN** every grid artwork candidate fails
- **THEN** the tile retains the same geometry and shows the generic game fallback without a broken-image placeholder

#### Scenario: Collection controls remain usable
- **WHEN** a management game card contains reorder, done, or remove controls over a bright header image
- **THEN** every control and its state remain visually legible and interactive

#### Scenario: Horizontal artwork treatment stays shared
- **WHEN** Library and horizontal Collection cards render game-header backdrops
- **THEN** both use the same shared fade and opacity treatment rather than independently tuned copies

### Requirement: Game detail artwork fallback
The full game-detail destination opened from Library and the game-detail overlay opened from Collection SHALL render the same wide `header.jpg` banner treatment and ordered fallback chain as horizontal game cards. The chain SHALL try `header.jpg`, then `library_hero.jpg`, `capsule_616x353.jpg`, `hero_capsule.jpg`, and `library_600x900.jpg`; the detail surface SHALL remain intact if every candidate fails. The surrounding full-detail accent wash SHALL sample the first candidate that decodes successfully rather than depending only on `header.jpg`.

#### Scenario: Library game detail uses fallback art
- **WHEN** a Library game detail screen cannot load its `header.jpg`
- **THEN** it tries `library_hero.jpg` first, followed by the remaining ordered assets, without changing the banner geometry

#### Scenario: Collection game detail uses the same fallback art
- **WHEN** a Collection game-detail overlay cannot load its `header.jpg`
- **THEN** it uses the same ordered fallback chain and banner treatment as the Library detail screen

#### Scenario: Detail artwork is entirely unavailable
- **WHEN** every game-detail artwork candidate fails
- **THEN** the detail card keeps its themed content and the full-detail accent wash remains unset rather than showing a broken-image placeholder

### Requirement: Collection overview Personal Pace presentation
Collection overviews SHALL present Personal Pace detail only for modes that benefit from pacing. They SHALL distinguish reliable forecasts, learning history, and missing estimate data; use approximate human-readable durations; and SHALL show `Change deadline` only when the collection domain marks that action eligible.

#### Scenario: Reliable deadline detail
- **WHEN** a deadline overview has a reliable complete Personal Pace forecast
- **THEN** it shows approximate required pace, recent tracked pace, projected capacity, and on-track or at-risk state

#### Scenario: Learning state
- **WHEN** Personal Pace does not yet have sufficient history
- **THEN** the overview explains that Backlogium is learning from tracked activity and makes no definitive fit claim

#### Scenario: Missing estimate detail
- **WHEN** one or more members lack the applicable HLTB estimate
- **THEN** the overview identifies the incomplete estimate count and makes no definitive fit claim

#### Scenario: Conditional deadline action visible
- **WHEN** the collection domain marks deadline intervention eligible
- **THEN** the overview shows the direct `Change deadline` action

#### Scenario: Conditional deadline action hidden
- **WHEN** the collection domain marks deadline intervention ineligible
- **THEN** the overview does not show the direct `Change deadline` action

#### Scenario: Basic list overview
- **WHEN** the collection mode is basic list
- **THEN** the overview presents no Personal Pace section

### Requirement: Collection management screen
The system SHALL provide a collection management screen, reached as a pushed sub-destination from the
collection create entry point or an existing collection's explicit customization action, where the user
can create a collection, choose its mode and an accent from the app's
palette, name it, give it an optional description, add and remove games, filter the pool of addable games with a search, set a target
date for deadline-goal collections, reorder members and mark them done for ordered-queue collections,
and delete the collection. The save action SHALL remain reachable regardless of the form's scroll
position. Deleting a collection SHALL require an explicit confirmation that names the collection and
states that its game memberships are removed with it, and SHALL NOT delete anything until that
confirmation is given. The screen SHALL render from locally stored state.
The configuration controls SHALL be presented compactly, so that the collection's games are reachable
without scrolling past a full screen of settings. No configuration option SHALL be removed to achieve
this. The collection overview's member list SHALL offer a display density choice.

#### Scenario: Creating a collection
- **WHEN** the user creates a new collection with a name and a mode
- **THEN** the collection is persisted and appears on the Home collections section

#### Scenario: Customizing an existing collection
- **WHEN** the user chooses the collection actions control from an existing collection overview
- **THEN** the management form opens with the collection's current settings and members

#### Scenario: Configuration presented compactly
- **WHEN** the management form is shown for a collection
- **THEN** its configuration controls occupy materially less vertical space than a full screen before
  the collection's games are reachable

#### Scenario: No option removed for compactness
- **WHEN** the management form is shown
- **THEN** every configuration option remains available — name, description, mode, order, accent, and
  for deadline collections the target date and estimate basis — whether directly or through a
  disclosure the user can open

#### Scenario: Add games hidden from the overview
- **WHEN** an existing collection overview is shown
- **THEN** the name/mode/accent fields and add-games pool are not shown until the user opens
  customization

#### Scenario: Save reachable at any scroll position
- **WHEN** the management screen is shown
- **THEN** the save action is presented as a floating control that remains reachable while the form
  scrolls

#### Scenario: Save blocked without a name
- **WHEN** the collection name is blank
- **THEN** the save action is not usable

#### Scenario: Describing a collection
- **WHEN** the user enters a description on the management screen and saves
- **THEN** the description is persisted on the collection

#### Scenario: Description is optional
- **WHEN** the user saves a collection without entering a description
- **THEN** the collection is saved and no description is required or rendered for it

#### Scenario: Clearing a description
- **WHEN** the user clears a previously saved description and saves
- **THEN** the collection is stored with no description and none is rendered

#### Scenario: Description shown on the overview
- **WHEN** a collection with a stored description is opened
- **THEN** its overview presents that description

#### Scenario: Adding games to a collection
- **WHEN** the user adds games to a collection from the management screen
- **THEN** those games become members of the collection

#### Scenario: Filtering the add-games pool
- **WHEN** the user enters a search query on the management screen
- **THEN** only library games matching the query and not already members are offered for adding

#### Scenario: Search matches nothing
- **WHEN** the search query matches no addable game
- **THEN** the list presents a no-match state beneath the search field and the field remains available
  to clear

#### Scenario: Removing games from a collection
- **WHEN** the user removes a game from a collection
- **THEN** that game is no longer a member of the collection

#### Scenario: Choosing an accent
- **WHEN** the user selects an accent on the management screen
- **THEN** only palette-compatible tokens are offered and the selection is persisted on the collection

#### Scenario: Setting a deadline
- **WHEN** the user sets or changes a target date on a deadline-goal collection
- **THEN** the collection's banner reflects the updated countdown

#### Scenario: Choosing a deadline estimate basis
- **WHEN** the user edits a deadline-goal collection
- **THEN** the setup offers Main Story, Main + Extra, Completionist, and All Styles as the HLTB
  time-estimate basis choices

#### Scenario: Hindsight deadline guidance
- **WHEN** the user opens an existing deadline-goal collection overview
- **THEN** it shows the selected basis, time until or past the deadline, estimated time remaining,
  and a shortfall warning only when the differential is negative

#### Scenario: Shortcut to change an infeasible deadline
- **WHEN** the selected estimate has a negative differential
- **THEN** the overview recommends changing the deadline and provides a direct target-date picker

#### Scenario: Reordering an ordered queue
- **WHEN** the user reorders members of an ordered-queue collection
- **THEN** the sequence is persisted and the next-game surface updates

#### Scenario: Marking a queue member done
- **WHEN** the user marks a member of an ordered-queue collection as done
- **THEN** the member stays listed with its name struck through and its card greyed, and the next-game
  surface skips it

#### Scenario: Deleting a collection
- **WHEN** the user deletes a collection and confirms the deletion
- **THEN** the collection and its memberships are removed, and it no longer appears on Home

#### Scenario: Delete requires confirmation
- **WHEN** the user chooses the delete action for a collection
- **THEN** a confirmation is presented naming the collection and stating that its game memberships are
  removed with it, and nothing is deleted until the user confirms

#### Scenario: Cancelling a deletion
- **WHEN** the user dismisses or cancels the delete confirmation
- **THEN** the collection and all of its memberships remain unchanged

#### Scenario: Empty collection on the management screen
- **WHEN** a collection has no members
- **THEN** the management screen presents an empty state with a control to add games

#### Scenario: Collection overview highlights selected games
- **WHEN** an existing collection has one or more members
- **THEN** the overview presents those members as larger visually highlighted tiles, each showing
  cached playtime and session count and showing trophy progress when stored achievement data exists

#### Scenario: Collection overview at a denser setting
- **WHEN** the user chooses a denser setting for the collection overview's member list
- **THEN** more members are visible at once with less detail each, following the display density
  ladder, and no member is omitted

#### Scenario: Collection overview summary metrics
- **WHEN** an existing collection overview is shown
- **THEN** it summarizes member count, aggregate playtime, aggregate session count, and aggregate
  trophy progress when achievement data exists

#### Scenario: Target date only for deadline mode
- **WHEN** the user is editing a collection whose mode is not deadline goal
- **THEN** no target date field is offered

### Requirement: Collection add-game genre filtering
The collection management screen SHALL provide a compact multi-select genre control for the Add games pool. With multiple genres selected, a non-member game SHALL remain addable when it has any selected genre. When both a text query and genre selections are active, the game SHALL satisfy the text query and at least one selected genre. Filtering SHALL never change collection membership by itself. The text query SHALL match a game's name or any known genre label, ignoring case, and offered games SHALL be presented in the same strongest-match-first order the Library search uses. The Add games search field and the games it offers SHALL be positioned so that results remain visible while the user is typing into the field.

#### Scenario: No genre selected
- **WHEN** the user has selected no genre filter
- **THEN** every non-member game allowed by the text query remains in the Add games pool

#### Scenario: One genre selected
- **WHEN** the user selects one genre
- **THEN** only non-member games carrying that genre and satisfying any active text query are offered

#### Scenario: Additional genre selected additively
- **WHEN** the user selects another genre while one or more genres are already selected
- **THEN** the pool expands to include non-member games carrying any selected genre rather than requiring every selected genre

#### Scenario: Text and genre filters combine
- **WHEN** the user has both a text query and selected genres
- **THEN** a game is offered only when it matches the text through its name or a genre label,
  ignoring case, and it carries at least one selected genre

#### Scenario: Offered games ranked by match strength
- **WHEN** a text query is active and several non-member games match it in different ways
- **THEN** they are offered strongest-match first, by the same ranking the Library search applies

#### Scenario: Results visible while typing
- **WHEN** the user types into the Add games search field
- **THEN** the offered games remain visible without the user first dismissing the keyboard or
  scrolling away from the field

#### Scenario: Unknown genre under an active genre filter
- **WHEN** a non-member game has unknown or empty genres while a genre filter is active
- **THEN** that game is not offered until the genre filter is cleared or matching metadata becomes available

#### Scenario: Adding a filtered game preserves filters
- **WHEN** the user adds an offered game while text or genre filters are active
- **THEN** that game becomes a draft member and the active filters remain available for continued curation

#### Scenario: Clearing selected genres
- **WHEN** the user clears all selected genres
- **THEN** genre filtering is removed without changing draft collection membership or the text query

#### Scenario: Filtering never bulk-adds games
- **WHEN** the user selects or clears a genre
- **THEN** no game is added to or removed from the draft collection automatically

### Requirement: Custom dark visual theme
The system SHALL render all screens using a hand-authored dark color scheme (charcoal/
navy surfaces with a single gold/amber accent) and SHALL NOT use Android dynamic
(wallpaper-derived) color, so the app's appearance is identical across devices.

#### Scenario: Consistent appearance regardless of device wallpaper
- **WHEN** the app is launched on an Android 12+ device with dynamic color available
- **THEN** the app renders using the custom color scheme, not a wallpaper-derived one

#### Scenario: Consistent appearance across OS versions
- **WHEN** the app is launched on a device below Android 12
- **THEN** the app renders using the same custom color scheme used on newer devices

### Requirement: Display typography for numeral moments
The system SHALL render level number, streak count, and XP total text using a distinct
display font, and SHALL render all other text using a bundled brand body font. No text
SHALL fall back to the platform system default font.

#### Scenario: Level number uses display font
- **WHEN** the Home screen shows the player's level number
- **THEN** it is rendered in the display font, not the body font or the system default font

#### Scenario: Body text uses the brand body font
- **WHEN** any screen renders body, caption, title, or label text (e.g. section
  headers, list rows, dialog text, navigation labels)
- **THEN** it is rendered in the bundled brand body font, not the platform system default font

#### Scenario: Offline availability preserved
- **WHEN** the app runs with no network connectivity
- **THEN** both the display font and the brand body font render from bundled resources,
  with no downloadable-font dependency

### Requirement: App-wide icon set
The system SHALL represent navigation destinations and status indicators using icons
from a single icon library, and SHALL NOT use emoji characters for these purposes.

#### Scenario: Navigation bar icons
- **WHEN** the bottom navigation bar is shown
- **THEN** Home, Library, and History are each represented by an icon from the chosen
  icon library instead of an emoji character

#### Scenario: Status glyphs
- **WHEN** a status indicator is shown (streak, quest completion state)
- **THEN** it is represented by an icon from the chosen icon library instead of an
  emoji character

### Requirement: Game art loading and error states
The system SHALL display a themed placeholder while a game's icon image is loading,
and a themed fallback icon if the image fails to load, on both the Library screen's
goal and backlog game rows.

#### Scenario: Icon still loading
- **WHEN** a game row is displayed and its icon image has not finished loading
- **THEN** a themed placeholder is shown in place of the icon

#### Scenario: Icon fails to load
- **WHEN** a game row's icon image fails to load (network error or invalid URL)
- **THEN** a themed fallback icon is shown instead of a blank space

### Requirement: Celebratory inline animations
The system SHALL play an inline animation within the Home screen's Level card when the
player's level increments, and SHALL play an inline animation within the Home screen's
Streak card when the current streak reaches a milestone interval of every 7 days.

#### Scenario: Level increments
- **WHEN** the player's level increases from its previous value
- **THEN** an inline animation plays within the Level card

#### Scenario: Streak reaches a weekly milestone
- **WHEN** the current streak's day count is a positive multiple of 7
- **THEN** an inline animation plays within the Streak card

#### Scenario: Streak not at a milestone
- **WHEN** the current streak's day count is not a multiple of 7
- **THEN** no milestone animation plays within the Streak card

### Requirement: Game list display density
The system SHALL let the user choose the display density of a list of games, offering a full-detail
list and at least two grid densities showing progressively more games with progressively less detail
per game. Each surface offering a density choice SHALL remember its own choice between visits,
independently of any other surface's.

Density SHALL govern only how much of a game's information is shown, never which games are shown or
in what order. Detail SHALL be dropped in a fixed order as density increases, so a denser view is
always a strict subset of a less dense one:

1. the game's identity — its name and its icon — SHALL be shown at every density;
2. playtime SHALL be shown at every density except the densest;
3. completion progress against a HowLongToBeat length SHALL be shown in the list and the least dense
   grid;
4. achievement and XP badges SHALL be shown in the list only.

A game's currently-playing state SHALL remain visible at every density, since it is a live signal
rather than detail.

#### Scenario: Choosing a density
- **WHEN** the user chooses a display density for a game list
- **THEN** that list re-renders at the chosen density without changing which games it contains or
  their order

#### Scenario: Density remembered
- **WHEN** the user leaves a surface whose density they changed and returns to it
- **THEN** the list is still shown at the chosen density

#### Scenario: Densities remembered independently
- **WHEN** the user chooses different densities on two surfaces that each offer a density choice
- **THEN** each surface keeps its own choice and neither affects the other

#### Scenario: Identity always shown
- **WHEN** a game list is shown at any density
- **THEN** every game shows its name and its icon

#### Scenario: Denser views are strict subsets
- **WHEN** the user increases the density of a game list
- **THEN** the information shown per game is a subset of what the previous density showed, with
  nothing newly appearing

#### Scenario: Currently-playing survives every density
- **WHEN** a game is currently being played and its list is shown at the densest setting
- **THEN** that game is still distinguishable as currently playing

#### Scenario: Selection available at every density
- **WHEN** a list supports selecting games and is shown at any density
- **THEN** games can still be selected and the selected state is visible

#### Scenario: Unrecognized stored density
- **WHEN** a stored density value cannot be recognized
- **THEN** the list falls back to its default density rather than failing to render

### Requirement: Library screen
The system SHALL provide a Library screen separating a curated, actively-tracked set of games from
the rest of the library, and SHALL allow adding a game to that set and removing it. Any game SHALL
display progress against a HowLongToBeat-sourced completion length when one is available and the
chosen display density shows completion progress, whether or not it belongs to the curated set, and
SHALL display no completion-based progress when no length is available. The curated set SHALL be
labelled in terms of active tracking rather than in terms of a user-entered target, since no such
target is collected, and the remaining games SHALL be labelled without implying that they are
unplayed or awaiting play. The Library SHALL offer a display density choice for its game lists.

#### Scenario: Game with an HLTB length shows progress
- **WHEN** the Library is shown at a density that includes completion progress and a game has a
  HowLongToBeat-sourced completion length
- **THEN** the game displays its name, icon, and playtime, and a progress indicator measuring its
  playtime against that completion length, regardless of whether it belongs to the curated set

#### Scenario: Progress omitted at denser settings
- **WHEN** the Library is shown at a density that does not include completion progress
- **THEN** a game with a HowLongToBeat-sourced completion length shows no progress indicator, and the
  indicator returns when a density that includes it is chosen

#### Scenario: Game played past its completion length
- **WHEN** a game's playtime exceeds its HowLongToBeat-sourced completion length
- **THEN** its progress indicator represents the whole playtime, showing the completion length and
  the excess beyond it as visually distinct portions of one full indicator, rather than resting at
  full with the excess unrepresented

#### Scenario: Game without an HLTB length shows no progress
- **WHEN** the Library is shown and a game has no HowLongToBeat-sourced completion length yet
- **THEN** the game displays its name and icon, displays its playtime at any density that includes
  playtime, and does not display completion-based progress at any density

#### Scenario: Adding a game to the tracked set
- **WHEN** the user adds a game to the tracked set, or removes one from it
- **THEN** the game moves between the tracked section and the rest of the library and the change
  persists, without prompting for a typed target

#### Scenario: Managing the tracked set at every density
- **WHEN** the Library is shown at any density
- **THEN** a game can still be added to or removed from the tracked set

#### Scenario: Tracked games appear once
- **WHEN** a game belongs to the tracked set
- **THEN** it appears only in the tracked section and not also among the remaining games

#### Scenario: Sections preserved across densities
- **WHEN** the user changes the Library's display density
- **THEN** the tracked section and the remaining-games section keep their headings and their
  contents, each rendered at the chosen density

#### Scenario: Labelling free of an implied target
- **WHEN** the tracked section and its actions are presented
- **THEN** their labels describe active tracking, and no label implies a completion target set by the
  user

#### Scenario: Remaining games labelled without implying they are unplayed
- **WHEN** the section holding games outside the tracked set is presented
- **THEN** its label does not describe those games as a backlog or as awaiting play, since a game with
  substantial playtime and visible completion progress can belong to it

#### Scenario: Tracked minutes still accounted separately
- **WHEN** playtime is recorded for a game in the tracked set
- **THEN** it continues to be accounted separately in per-day progress and reflected in History, as
  it is today

### Requirement: Per-list Library sorting
The system SHALL let the user choose the sort order of each Library list independently, offering at
least playtime, name, recent activity, and contributed XP, and SHALL remember each list's chosen order
between visits.

#### Scenario: Sorting a list
- **WHEN** the user chooses a sort order for a Library list
- **THEN** that list is reordered accordingly and the other list's order is unaffected

#### Scenario: Available orders
- **WHEN** the sort options for a list are presented
- **THEN** they include ordering by playtime, by name, by recent activity, and by contributed XP

#### Scenario: Order remembered
- **WHEN** the user leaves the Library and returns
- **THEN** each list is still ordered as the user last chose

#### Scenario: Default orders
- **WHEN** the user has never chosen a sort order
- **THEN** each list uses its existing default order

#### Scenario: Stable ordering
- **WHEN** two games compare equal under the chosen sort key
- **THEN** their relative order is determined consistently rather than arbitrarily

#### Scenario: Games missing the sort key
- **WHEN** a list is sorted by a key that some games have no value for
- **THEN** those games are ordered last rather than being omitted or placed arbitrarily

#### Scenario: Sorting combined with search
- **WHEN** a search filter is active
- **THEN** the matching games are presented strongest-match first, and the chosen sort order
  arranges games that matched the query equally strongly

#### Scenario: Chosen sort restored when search is cleared
- **WHEN** the user clears an active search
- **THEN** each list returns to being ordered solely by its chosen sort order

### Requirement: Library search
The system SHALL provide a search that filters the Library by game name or any known genre label,
ignoring case and preserving the section structure for sections that still contain matches. The
search field SHALL communicate that both games and genres are searchable.

Matches SHALL be presented in order of how closely they matched the query, strongest first: an exact
name match, then a name beginning with the query, then a name containing a word beginning with the
query, then a name containing the query elsewhere, then a match on a genre label alone. Ranking
SHALL ignore case. The search SHALL also offer a genre filter that narrows results to games carrying
any selected genre. That genre selection SHALL apply to the current visit only and SHALL NOT be
remembered between visits, unlike each list's chosen sort order.

The search field SHALL keep a stable width and a legible input while it is focused and while text is
entered, so neither focusing the field nor typing into it changes the size of the field or of the
text within it.

#### Scenario: Filtering by name
- **WHEN** the user enters text contained in a game's name in the Library search
- **THEN** that game is shown regardless of whether genre metadata is available

#### Scenario: Filtering by genre
- **WHEN** the user enters text contained in one or more known genre labels
- **THEN** games carrying any matching genre are shown

#### Scenario: One game matches name and genre
- **WHEN** the same game matches the query through both its name and a genre label
- **THEN** the game is shown once in its existing section, ranked by its name match

#### Scenario: Stronger name match ranked first
- **WHEN** one game's name begins with the query and another game's name contains the query only
  in the middle of a word
- **THEN** the game whose name begins with the query is presented first, regardless of either
  game's playtime or other sort values

#### Scenario: Word prefix outranks a mid-word match
- **WHEN** the query matches the beginning of a word inside one game's name and matches only the
  middle of a word in another game's name
- **THEN** the game matching at a word boundary is presented first

#### Scenario: Name match outranks a genre-only match
- **WHEN** one game matches through its name and another matches only through a genre label
- **THEN** the game matching by name is presented first

#### Scenario: Sections preserved while filtering
- **WHEN** a filter is active and matches exist in more than one section
- **THEN** each section with matches keeps its heading

#### Scenario: Genre filter narrows the search
- **WHEN** the user selects one or more genres in the Library search
- **THEN** only games carrying at least one selected genre are shown, ranked as above

#### Scenario: Genre filter not remembered between visits
- **WHEN** the user selects genres in the Library search, leaves the Library, and returns
- **THEN** no genre filter is active and the full Library is shown, while each list's chosen sort
  order is still remembered

#### Scenario: Field stable under focus and input
- **WHEN** the user focuses the Library search field and types
- **THEN** the field's width and the size of the text within it are unchanged from their unfocused,
  empty state

#### Scenario: No matches
- **WHEN** a filter matches no game name or known genre
- **THEN** an empty state explains that no games match, rather than showing a blank list

#### Scenario: Clearing the filter
- **WHEN** the user clears the search
- **THEN** the full Library is shown again

### Requirement: Per-game XP contribution badge
The system SHALL show, for each game in the Library, the total XP that game has contributed to the
player's total — its tapered playtime XP plus the XP from its unlocked achievements — such that the
displayed values are consistent with the player's total XP.

#### Scenario: Showing contributed XP
- **WHEN** a game has contributed XP
- **THEN** its row displays that contribution as a compact badge

#### Scenario: Game with no tracked playtime
- **WHEN** a game has no tracked playtime and no imported history and no unlocked achievements
- **THEN** its badge reflects a zero contribution rather than being derived from lifetime Steam
  playtime

#### Scenario: Contribution is not proportional to lifetime playtime
- **WHEN** a game's lifetime Steam playtime greatly exceeds its tracked playtime, or its playtime
  XP has been tapered
- **THEN** the badge still reflects the game's actual XP contribution, and is labelled as
  contributed XP rather than implying a per-hour rate

### Requirement: Batch HowLongToBeat refresh progress
The system SHALL show the progress of a running HowLongToBeat batch refresh, including how many
games have been processed out of the total, and a log of each processed game with its outcome.

#### Scenario: Progress while refreshing
- **WHEN** a batch refresh is running
- **THEN** the Library shows the number of games processed out of the total as a progress indicator

#### Scenario: Per-game log
- **WHEN** each game in the batch is processed
- **THEN** a log entry names the game and its outcome: matched, needs review, no match, or lookup
  failed

#### Scenario: Progress survives leaving the screen
- **WHEN** the user leaves the Library while a refresh is running and returns
- **THEN** the progress indicator reflects the refresh's current position, and the log resumes from
  that point rather than showing entries from before the screen was left

#### Scenario: Refresh completes
- **WHEN** the batch refresh finishes
- **THEN** the progress indicator resolves and the controls become available again

### Requirement: Stopping a batch HowLongToBeat refresh
The system SHALL let the user stop a running batch refresh, SHALL keep the data already fetched by
the stopped run, and SHALL NOT re-fetch that data on a subsequent ordinary refresh.

#### Scenario: Stopping a running refresh
- **WHEN** the user stops a running batch refresh
- **THEN** the refresh ends, the controls become available again, and every game already processed
  keeps the data it received

#### Scenario: Resuming after a stop
- **WHEN** the user starts an ordinary batch refresh after stopping one
- **THEN** the games the stopped run already fetched are not queried again, so the new run
  continues from where the stopped one ended

#### Scenario: Forced refresh still starts over
- **WHEN** the user starts a forced refresh after stopping one
- **THEN** every game is queried again, since a forced refresh deliberately ignores how recently
  data was fetched

### Requirement: Targeted HowLongToBeat refresh
The system SHALL let the user select multiple games in the Library and run a HowLongToBeat refresh
over only that selection.

#### Scenario: Entering selection mode
- **WHEN** the user long-presses a game row
- **THEN** selection mode is entered with that game selected, and the number of selected games is
  shown

#### Scenario: Refreshing the selection
- **WHEN** the user runs the HowLongToBeat lookup on a selection
- **THEN** only the selected games are refreshed

#### Scenario: Selection preserved while filtering
- **WHEN** a search filter hides a selected game
- **THEN** it remains part of the selection, and the visible selected count continues to include it

#### Scenario: Leaving selection mode
- **WHEN** the user clears the selection or navigates away
- **THEN** selection mode is exited and no selection is retained

#### Scenario: Tap behavior unchanged
- **WHEN** the user taps a game row while not in selection mode
- **THEN** the game's detail screen opens as it does today

### Requirement: Refresh HowLongToBeat library trigger
The system SHALL provide a manual control to refresh HowLongToBeat data across the library, reflect that a refresh is running, and report its completion.

#### Scenario: Triggering a refresh
- **WHEN** the user triggers "Refresh HLTB library"
- **THEN** a batch refresh is enqueued and the user can leave the screen while it continues running

#### Scenario: Refresh completes
- **WHEN** a batch refresh completes
- **THEN** the user is informed of completion and any games needing review become available in the review surface

### Requirement: Per-game HowLongToBeat status and refresh
The system SHALL show each game's HowLongToBeat lookup state in the Library and SHALL let the user trigger a fresh single-game lookup, distinguishing a lookup in progress, a failed lookup, and a stored match result.

#### Scenario: Per-game status is visible
- **WHEN** the Library shows a game that has stored HowLongToBeat data (matched, needing review, or no match) or an in-progress lookup
- **THEN** the game displays its current HowLongToBeat state

#### Scenario: Refreshing a single game
- **WHEN** the user triggers a HowLongToBeat refresh for a single game
- **THEN** the system performs a fresh lookup for that game regardless of cached data and reflects the in-progress state while it runs

#### Scenario: Single-game lookup fails
- **WHEN** a single-game HowLongToBeat lookup fails
- **THEN** the failure is surfaced for that game and its cached HowLongToBeat data is not overwritten or cleared

### Requirement: Inline HowLongToBeat match selection
When a single game's HowLongToBeat lookup yields ambiguous candidates, the system SHALL let the user
choose among those candidates from the library itself, without navigating to a separate screen.

#### Scenario: Choosing a candidate in place
- **WHEN** a single-game lookup started from the game's menu reports an ambiguous match
- **THEN** the candidates are presented for selection without navigating to a separate screen

#### Scenario: Selection resolves immediately
- **WHEN** the user selects a candidate
- **THEN** the match is resolved, the game's status reflects the resolution, and no separate
  confirmation step is required

#### Scenario: Changing an already-resolved match
- **WHEN** a game's HowLongToBeat match is already resolved
- **THEN** changing the match is offered, and choosing it presents candidates to select from

#### Scenario: An offered change is abandoned
- **WHEN** the user asks to change a resolved match and then dismisses the picker without selecting
- **THEN** the previously resolved match remains in effect, unchanged

#### Scenario: Lookup in flight
- **WHEN** a lookup is running for the picker
- **THEN** the picker reflects the in-flight state and the selection action is unavailable until it
  completes

#### Scenario: Lookup finds a single confident match
- **WHEN** a single-game lookup resolves confidently on its own
- **THEN** no candidate selection is presented and the resolved match is reported

#### Scenario: Many candidates
- **WHEN** more candidates are available than fit on screen
- **THEN** the candidate list scrolls within the picker rather than overflowing it, and every
  candidate is reachable

### Requirement: Candidate cover art
The system SHALL present cover art alongside each HowLongToBeat candidate, wherever candidates are
shown, so visually similar titles can be distinguished.

#### Scenario: Art shown for candidates
- **WHEN** candidates are presented for selection
- **THEN** each candidate shows its cover art alongside its name and completion length

#### Scenario: Art unavailable
- **WHEN** a candidate has no stored image, or the image fails to load
- **THEN** a themed placeholder is shown in its place and the candidate remains selectable

### Requirement: HLTB match review
The system SHALL provide a surface listing games flagged as needing an HLTB match, and SHALL let the
user open a flagged game and select the correct HowLongToBeat entry from its candidates. This surface
serves the batch case; the entry point to it SHALL be presented only when at least one game is
flagged as needing review.

#### Scenario: Reviewing flagged games
- **WHEN** the user opens the match-review surface and games are flagged as needing review
- **THEN** each flagged game is listed with its candidate HowLongToBeat entries available for selection

#### Scenario: Confirming a match
- **WHEN** the user selects the correct candidate for a flagged game
- **THEN** the game is marked resolved, its completion length becomes available to the goal and gamification features, and it is removed from the review list

#### Scenario: No games need review
- **WHEN** the user opens the match-review surface and no games are flagged
- **THEN** the surface indicates there is nothing to review

#### Scenario: Entry point hidden when nothing is flagged
- **WHEN** no games are flagged as needing review
- **THEN** no entry point to the match-review surface is presented

#### Scenario: Entry point shown with a count
- **WHEN** one or more games are flagged as needing review
- **THEN** the entry point is presented and indicates how many games are awaiting review

### Requirement: Game detail screen with achievements
The system SHALL provide a game detail screen, reachable by selecting a game from the
Library or by selecting a game tile in a collection overview, that lists that game's
achievements with each achievement's unlock state, rarity tier, and the XP it contributes,
using its display name and icon when available. The screen SHALL also show the game's current
Steam concurrent-player count when available, and SHALL show no such line when it is not. The
screen SHALL present the game's summary above the achievement list, so a game with no
achievement data still shows its own information rather than only an empty state. The screen
SHALL present the same content regardless of which entry point opened it.

#### Scenario: Opening a game's detail
- **WHEN** the user selects a game in the Library
- **THEN** a detail screen for that game is shown listing its achievements

#### Scenario: Opening a game's detail from a collection
- **WHEN** the user selects a game tile in a collection overview
- **THEN** a detail screen for that game is shown listing its achievements, with the same
  summary and achievement content the Library entry point produces

#### Scenario: Achievement rarity and XP shown
- **WHEN** the detail screen shows an unlocked achievement that has a rarity snapshot
- **THEN** it displays the achievement's rarity tier and the XP it contributes

#### Scenario: Locked achievement shown without XP
- **WHEN** the detail screen shows a locked achievement
- **THEN** it is displayed as locked and shows no XP contribution

#### Scenario: Game without achievement data
- **WHEN** the user opens the detail for a game that has no stored achievements
- **THEN** the game's summary is still shown, and the achievement area indicates there are no
  achievements to show rather than appearing broken

#### Scenario: Current player count shown
- **WHEN** the detail screen opens and Steam reports a current player count for the game
- **THEN** the summary displays that count

#### Scenario: Current player count unavailable
- **WHEN** the detail screen opens and no current player count is available (lookup failed or
  Steam has none for that app)
- **THEN** the summary shows no player-count line, rather than a zero or a placeholder

#### Scenario: Player count does not block the rest of the summary
- **WHEN** the detail screen opens and the player-count lookup has not yet resolved
- **THEN** the rest of the summary and the achievement list render immediately from local data,
  and the player count appears afterward if and when it resolves

### Requirement: Game detail presentation by entry point
The system SHALL present game detail as a full screen when it is opened from the Library, and
as a partial-height overlay rising from the bottom of the screen when it is opened from a
collection overview. The overlay SHALL leave part of the collection overview visible above it,
so the collection remains the evident context, and SHALL be dismissible by a downward swipe and
by system back, both returning to that collection overview. Dismissing the overlay SHALL NOT
leave the collection overview scrolled or otherwise repositioned.

#### Scenario: Collection entry point presents an overlay
- **WHEN** the user selects a game tile in a collection overview
- **THEN** game detail rises from the bottom as a partial-height overlay and part of the
  collection overview remains visible above it

#### Scenario: Library entry point presents a full screen
- **WHEN** the user selects a game in the Library
- **THEN** game detail is presented as a full screen, not as an overlay

#### Scenario: Dismissing the overlay by swipe
- **WHEN** the user swipes the game detail overlay downward
- **THEN** the overlay is dismissed and the collection overview it was opened from is shown

#### Scenario: Dismissing the overlay by system back
- **WHEN** the game detail overlay is shown and the user triggers system back
- **THEN** the overlay is dismissed and the collection overview it was opened from is shown,
  rather than the collection itself being closed

#### Scenario: Scrolling the overlay's content
- **WHEN** the game detail overlay is shown and the user scrolls its achievement list away from
  the list's top
- **THEN** the list scrolls and the overlay is not dismissed

### Requirement: Game detail accent wash containment
The game detail screen derives a muted accent color from the game's header art and paints it as
a background wash. When game detail is presented as a full screen, the wash SHALL span the app
shell so it renders behind the shell's profile header as well as the screen's own content. When
game detail is presented as an overlay, the wash SHALL be confined to the overlay's own bounds
and SHALL NOT tint the collection overview behind it. The wash SHALL NOT persist on any screen
after game detail is left or dismissed.

#### Scenario: Full-screen wash spans the shell
- **WHEN** game detail is opened from the Library for a game whose header art resolves
- **THEN** the accent wash renders behind the app shell's profile header as well as the screen
  content

#### Scenario: Overlay wash stays inside the overlay
- **WHEN** game detail is opened as an overlay from a collection overview for a game whose
  header art resolves
- **THEN** the accent wash renders only within the overlay, and the collection overview visible
  above it is not tinted

#### Scenario: Wash cleared on dismissal
- **WHEN** the user leaves game detail by any means from either entry point
- **THEN** no accent wash from that game remains on the screen that is shown next

#### Scenario: Game without resolvable header art
- **WHEN** game detail is opened for a game whose header art is absent or fails to load
- **THEN** no accent wash is painted, in either presentation, and the screen renders on the
  theme's own background

### Requirement: Game summary section
The game detail screen SHALL present a summary of the game above its achievement list, showing the
game's art, its playtime, its known HowLongToBeat completion lengths, its achievement completion,
and its XP contribution. The summary SHALL offer a link to the game's Steam store page, presented
directly below the summary's own content, which opens that page outside the app.

#### Scenario: Viewing the summary
- **WHEN** the game detail screen is opened
- **THEN** a summary section above the achievement list shows the game's art, playtime, achievement
  completion, and XP contribution

#### Scenario: HowLongToBeat lengths shown when known
- **WHEN** the game has resolved HowLongToBeat data
- **THEN** the summary presents its known completion lengths

#### Scenario: HowLongToBeat data absent
- **WHEN** the game has no HowLongToBeat data
- **THEN** the summary omits completion lengths rather than showing empty or zero values

#### Scenario: Imported history distinguished
- **WHEN** a game's playtime includes imported historical playtime
- **THEN** the summary distinguishes tracked playtime from imported playtime

#### Scenario: XP contribution consistent with the Library
- **WHEN** the summary shows the game's XP contribution
- **THEN** it is the same value the Library shows for that game

#### Scenario: Opening the game on Steam
- **WHEN** the user selects the Steam link below the summary
- **THEN** that game's Steam store page is opened outside the app, and the game detail screen is left
  as it was so returning to the app resumes where the user left off

#### Scenario: Steam link identifies the game
- **WHEN** the summary's Steam link is presented
- **THEN** it targets the store page for the game being shown, not a generic store destination

#### Scenario: Steam link independent of other data
- **WHEN** the game has no HowLongToBeat data, no achievements, and no player count
- **THEN** the Steam link is still presented, since it depends only on the game's identity

### Requirement: Game detail manual refresh
The game detail screen SHALL provide a pull-down gesture that refreshes the game's current Steam
player count on demand, in addition to the screen's existing periodic polling. The gesture SHALL
indicate that a refresh is in progress and SHALL indicate its completion. A manual refresh SHALL NOT
be immediately followed by an already-scheduled periodic poll.

A refresh that fails or returns no count SHALL leave the screen showing no player-count line, in the
same omit-rather-than-placeholder manner as a failed periodic poll, rather than surfacing an error
state over the rest of the summary.

#### Scenario: Refreshing the player count
- **WHEN** the user pulls down on the game detail screen
- **THEN** the game's current player count is fetched again and the summary shows the new value when
  it resolves

#### Scenario: Refresh in progress
- **WHEN** a manual refresh is in flight
- **THEN** the screen indicates that a refresh is happening, and indicates when it has finished

#### Scenario: Refresh completion is independent of periodic polling
- **WHEN** the selected game's current-player response resolves
- **THEN** the manual refresh indicator stops immediately, while the next periodic poll remains
  scheduled relative to that response and does not keep the manual refresh active

#### Scenario: Manual refresh resets the polling interval
- **WHEN** the user manually refreshes the player count
- **THEN** the next periodic poll is scheduled relative to the manual refresh, rather than firing
  immediately afterwards from the previous schedule

#### Scenario: Refresh fails
- **WHEN** a manual refresh fails or Steam reports no count for the game
- **THEN** the summary shows no player-count line, and no error state is presented over the rest of
  the summary

#### Scenario: Refresh does not disturb local content
- **WHEN** a manual refresh is in flight or has failed
- **THEN** the summary's locally-derived content and the achievement list remain rendered and usable
  throughout

#### Scenario: Refresh scope
- **WHEN** the user performs the pull-down refresh
- **THEN** only the player count is refreshed, and no library sync, achievement fetch, or
  HowLongToBeat lookup is triggered by the gesture

### Requirement: Game detail genre tiles
The game detail summary SHALL display every known genre for the game as compact, non-interactive tiles that wrap across available width. The tiles SHALL use the cached Store order and SHALL be omitted when the game has no known genres.

#### Scenario: Game has known genres
- **WHEN** the user opens game detail for a game with one or more cached genres
- **THEN** all known genre labels appear as wrapping tiles in the summary above the achievement list

#### Scenario: Game has no known genres
- **WHEN** the user opens game detail for a game whose genres are unknown or empty
- **THEN** no genre-tile section or genre error placeholder is shown

#### Scenario: Genre tile is informational
- **WHEN** the user taps a genre tile in the initial genre release
- **THEN** no navigation or filter change occurs

### Requirement: Achievement sorting
The game detail screen SHALL let the user sort achievements by date achieved or by rarity, and SHALL
group locked achievements after unlocked ones in both orders.

#### Scenario: Default order
- **WHEN** the game detail screen is opened
- **THEN** achievements are ordered by date achieved, most recent first

#### Scenario: Sorting by rarity
- **WHEN** the user sorts by rarity
- **THEN** achievements are ordered from rarest to most common

#### Scenario: Locked achievements grouped last
- **WHEN** a game has both unlocked and locked achievements
- **THEN** unlocked achievements are listed first in the chosen order, followed by locked ones

#### Scenario: Sort not persisted
- **WHEN** the user leaves the screen and returns
- **THEN** the default order is applied again

### Requirement: Achievement unlock rate
The game detail screen SHALL show, on each achievement, the share of players who have unlocked it,
using the same percentage that determined that achievement's rarity tier so the two never disagree.

#### Scenario: Rate shown for an unlocked achievement
- **WHEN** an unlocked achievement has a stored rarity snapshot
- **THEN** its row displays that snapshot as the share of players who have unlocked it, consistent
  with the rarity tier shown on the same row

#### Scenario: Rate shown for a locked achievement
- **WHEN** a locked achievement has a known global unlock percentage
- **THEN** its row displays that percentage as the share of players who have unlocked it

#### Scenario: Rate unknown
- **WHEN** an achievement has neither a rarity snapshot nor a known global unlock percentage
- **THEN** its row displays no unlock rate rather than showing a zero or placeholder value

#### Scenario: Rate agrees with the rarity sort
- **WHEN** achievements are sorted by rarity
- **THEN** the order follows the same percentages the rows display

### Requirement: Rarity Standing section
The achievements UI SHALL present a Rarity Standing section stating the player's provable standing
among owners of that game, their unlocked count against the average owner's, and the caveat that the
population includes owners who never played the game.

#### Scenario: Standing shown
- **WHEN** a game has achievements, the player has unlocked at least one, and a bound is derivable
- **THEN** the section presents the bound as a ceiling on the share of owners at or above the player's
  count, alongside the player's count, the game's total, and the average owner's count

#### Scenario: Population caveat always present
- **WHEN** the section is shown
- **THEN** it states that the figures are based on all Steam owners, including unplayed copies

#### Scenario: Phrased as a ceiling, never as a rank
- **WHEN** the bound is presented
- **THEN** it is phrased as an upper bound — a standing of "or better" — and never as an exact
  percentile or rank

#### Scenario: Uninformative bound suppressed
- **WHEN** the derived bound is at or above half of all owners
- **THEN** the bound is not presented, and only the player's count against the average owner's count
  is shown

#### Scenario: No bound derivable
- **WHEN** the player has unlocked no achievements, or too few unlock rates are known to derive a
  bound
- **THEN** the bound is not presented, and only the player's count against the average owner's count
  is shown

#### Scenario: Game without achievement data
- **WHEN** a game has no achievements, or no achievement data has been stored for it
- **THEN** the section is not shown at all

### Requirement: Rarity Standing rounding never overstates
A presented bound SHALL never be tighter than the derived one, and SHALL remain legible at very small
values.

#### Scenario: Rounded away from zero
- **WHEN** a derived bound is displayed at reduced precision
- **THEN** it is rounded away from zero, so the displayed figure is still an upper bound

#### Scenario: Precision by magnitude
- **WHEN** a bound below one tenth of all owners is displayed
- **THEN** it is shown to one decimal place; larger bounds are shown as whole numbers

#### Scenario: Extremely small bound
- **WHEN** a derived bound is smaller than the smallest displayable value
- **THEN** it is presented at that smallest displayable value rather than as zero

### Requirement: Achievement descriptions
The game detail screen SHALL show each achievement's description beneath its name when one is
known, and SHALL indicate when an achievement is hidden by Steam rather than showing empty space.

#### Scenario: Description shown
- **WHEN** an achievement has a stored description
- **THEN** it is displayed beneath the achievement's name

#### Scenario: Description not yet available
- **WHEN** an achievement has no stored description
- **THEN** the row displays the achievement's name without a description and without an error or
  placeholder text

#### Scenario: Hidden achievement
- **WHEN** an achievement is hidden by Steam and not yet unlocked
- **THEN** the row indicates that the achievement is hidden

#### Scenario: Hidden achievement once unlocked
- **WHEN** a hidden achievement has been unlocked and Steam supplies its description
- **THEN** the description is displayed normally

### Requirement: Per-game achievement count on Library rows
The system SHALL display, on each Library game row that has stored achievement data, a
compact count of unlocked achievements out of that game's total.

#### Scenario: Row shows unlocked-of-total count
- **WHEN** the Library shows a game with stored achievement data
- **THEN** the row displays how many of the game's achievements are unlocked out of its total

#### Scenario: Row without achievement data
- **WHEN** the Library shows a game with no stored achievement data
- **THEN** the row shows no achievement count and is otherwise unchanged

### Requirement: Distinct visual signal for a fully-completed game
The system SHALL visually distinguish a game whose achievements are all unlocked from one
that is merely in progress, both on its Library row and on its detail screen.

#### Scenario: Fully-completed game stands out on the Library row
- **WHEN** the Library shows a game whose unlocked achievement count equals its total (and
  that total is greater than zero)
- **THEN** the row displays a distinct "100% Completed" indicator in place of the plain
  unlocked-of-total count

#### Scenario: Fully-completed game is announced on its detail screen
- **WHEN** the user opens the detail screen for a game whose achievements are all unlocked
- **THEN** the screen displays a prominent completion banner distinct from the per-achievement
  list

#### Scenario: In-progress game shows no completion signal
- **WHEN** a game has stored achievement data but its unlocked count is less than its total
- **THEN** neither the Library row nor the detail screen displays the completion indicator

### Requirement: History screen
The system SHALL provide a History screen presenting play history grouped by day, where each day
expands into the games played that day and each game expands into its individual sessions. Each day
SHALL show its total played time, its goal-game time, and whether that day's quest was met. A
session's start time SHALL be presented as approximate, and its tracked playtime SHALL be presented
distinctly from that start time — never as a start–end range, since subtracting the two into a
duration can be misled by a difference that reflects how the tracked-minutes counter updates, not a
measurement error. Each day SHALL also show thumbnails for achievements
unlocked that day, capped at 5 with any excess collapsed into a count badge.

#### Scenario: Day-grouped history
- **WHEN** the History screen is shown and play history exists
- **THEN** history is presented as a list of days, most recent first, each showing that day's total
  played time and whether its quest was met

#### Scenario: Expanding a day
- **WHEN** the user expands a day
- **THEN** the games played that day are listed, each with its art and its total played time for that
  day

#### Scenario: Expanding a game within a day
- **WHEN** the user expands a game within a day
- **THEN** that game's individual sessions for that day are listed, each with its approximate start
  time and its tracked playtime

#### Scenario: Today expanded by default
- **WHEN** the History screen is opened
- **THEN** the current day is expanded and all earlier days are collapsed

#### Scenario: Bounded initial history
- **WHEN** the History screen is opened
- **THEN** at most 30 days are presented, and an action is offered to load earlier days

#### Scenario: Loading earlier days
- **WHEN** the user loads earlier days
- **THEN** further days are appended to the list, preserving the current expansion state

#### Scenario: Day total matches its contents
- **WHEN** a day is expanded
- **THEN** the total shown on that day's header equals the sum of the sessions listed beneath it

#### Scenario: Session spanning midnight
- **WHEN** a session began on one day and ended on the next
- **THEN** it is listed once, under the day it began, and is not divided between the two days

#### Scenario: Session times not presented as exact
- **WHEN** a session's start time is shown
- **THEN** it is presented as approximate, reflecting that session boundaries are derived from
  periodic polling rather than observed directly

#### Scenario: Tracked playtime never paired with an end time
- **WHEN** a session's tracked playtime is shown
- **THEN** it is shown alongside only the session's approximate start, never a start–end range, so a
  reader cannot subtract two displayed clock times into a duration that may disagree with the tracked
  minutes

#### Scenario: Session still in progress
- **WHEN** a session is still open
- **THEN** it is marked as in progress and its playtime is included in its day's total

#### Scenario: Day with achievements unlocked
- **WHEN** a day has 5 or fewer achievements unlocked across the games played that day
- **THEN** its header shows a thumbnail for each unlocked achievement and no overflow badge

#### Scenario: Day with more than 5 achievements unlocked
- **WHEN** a day has more than 5 achievements unlocked
- **THEN** its header shows 5 thumbnails followed by a badge stating the remaining count

#### Scenario: Day with no achievements unlocked
- **WHEN** a day has no achievements unlocked
- **THEN** its header shows no achievement thumbnail row

#### Scenario: Day with progress but no sessions
- **WHEN** a day has recorded progress but no individual sessions
- **THEN** its header is shown with its recorded state and offers nothing to expand

#### Scenario: Quest state remains authoritative
- **WHEN** a day's presented total differs from the stored per-day total that determined its quest
- **THEN** the quest state shown is the stored one, so the screen never contradicts whether a quest
  was met

#### Scenario: Game name unavailable
- **WHEN** a session's game is not present in the stored library
- **THEN** the session is still listed under a fallback label rather than being omitted

#### Scenario: No history yet
- **WHEN** no sessions and no daily progress exist
- **THEN** an empty state explains that history appears after playing and syncing

### Requirement: Circular game thumbnails in compact rows
Where games are represented as small thumbnails in a horizontal row rather than as list rows, the
system SHALL render those thumbnails as circles. This SHALL apply to the member thumbnails on Home's
collection cards and to the game thumbnails on History day tiles. Achievement icons SHALL remain
non-circular, so that a row of games and a row of achievements are distinguishable at a glance
without reading either.

Full-size game icons in list rows, game detail, and the most-played list SHALL be unaffected and
SHALL keep their existing shape.

#### Scenario: Collection teaser thumbnails are circular
- **WHEN** a Home collection card shows member thumbnails
- **THEN** each thumbnail is rendered as a circle

#### Scenario: History day thumbnails are circular
- **WHEN** a History day tile shows game thumbnails
- **THEN** each thumbnail is rendered as a circle

#### Scenario: Achievement icons stay distinguishable
- **WHEN** a surface shows both game thumbnails and achievement icons
- **THEN** the achievement icons are not circular, so the two rows are distinguishable by shape alone

#### Scenario: List rows unaffected
- **WHEN** a game is shown as a full list row, in game detail, or in the most-played list
- **THEN** its icon keeps its existing non-circular shape

#### Scenario: Thumbnail without artwork
- **WHEN** a game thumbnail has no artwork or its artwork fails to load
- **THEN** its themed fallback is rendered in the same circular shape rather than reverting to a
  square

### Requirement: History day game thumbnails
A History day tile SHALL show thumbnails of the games played on that day, in a horizontal row, so a
day can be identified without expanding it. The row SHALL show a bounded number of thumbnails and
SHALL indicate how many further games the day holds, following the same capped-with-overflow
treatment the day tile's achievement row already uses. Thumbnails SHALL be ordered consistently with
the day's expanded game list.

#### Scenario: Day tile shows its games
- **WHEN** a History day tile represents a day with one or more games played
- **THEN** the tile shows a horizontal row of those games' thumbnails without the day being expanded

#### Scenario: Thumbnail overflow
- **WHEN** a day holds more games than the row's cap
- **THEN** the row shows the capped number of thumbnails followed by a count of the remaining games

#### Scenario: Day with no games
- **WHEN** a History day tile represents a day with recorded progress but no games played
- **THEN** no thumbnail row is shown, rather than an empty row

#### Scenario: Thumbnail order matches the expanded list
- **WHEN** the user expands a day whose thumbnails are shown
- **THEN** the expanded game list begins with the games whose thumbnails were shown, in the same
  order

#### Scenario: Games and achievements distinguishable on one tile
- **WHEN** a day tile shows both game thumbnails and achievement icons
- **THEN** the two rows are visually distinct and separately identifiable

### Requirement: Analytics screen
The system SHALL provide an Analytics screen, reachable as a top-level destination, that
summarizes the player's tracked play over a user-selected window using a daily
playtime bar chart, a streak summary, a session-insights summary, a time-of-day pattern, an
achievement-rarity breakdown, and a most-played-games list.

The window SHALL be selected at the screen level and SHALL consist of a length and an anchor
period. The offered lengths SHALL include at least two weeks, 30 days, one month, 90 days, and one
year, and SHALL always include two weeks so that a figure comparable to Steam's own two-week
playtime is always available. Calendar lengths — one month and one year — SHALL denote calendar
periods, and rolling lengths — two weeks, 30 days, and 90 days — SHALL denote durations counted back
from the anchor's end. The length selection SHALL make clear which lengths are calendar periods and
which are rolling durations, since 30 days and one month otherwise appear interchangeable while
stepping differently.

The anchor SHALL be movable to earlier periods so previous months and years are reachable, and
SHALL default to the period ending today. Moving the anchor SHALL step by one calendar period for a
calendar length and by the selected length for a rolling length. The anchor SHALL NOT be movable
earlier than the period containing the earliest tracked session, so periods that no data could ever
populate are unreachable.

The window SHALL apply to every figure derived from tracked sessions: the daily playtime chart, the
most-played-games list, the session-insights summary, the count of quest-met days, and the
time-of-day pattern. The current streak, the longest streak, and the achievement-rarity breakdown
SHALL NOT follow the window — they are player-level and all-time figures respectively — and the
screen SHALL make that distinction evident rather than presenting them as describing the selected
period. Every windowed figure SHALL derive from the same resolved window bounds.

The chart SHALL offer omitting zero-minute dates as a display option, independent of the selected
window length. The screen SHALL render purely
from locally stored state so it is usable offline, and SHALL present an empty state when no tracked
sessions exist in the window. The daily playtime chart SHALL draw one bar per local day and SHALL
mark the configured daily-quest threshold as a reference line, so met and unmet days are legible at
a glance. The streak summary SHALL show the current streak, the longest streak, and the count of
quest-met days within the window. The session-insights summary SHALL show the session count, the
average session length, and the longest session within the window. The time-of-day pattern SHALL
bucket tracked minutes into morning, afternoon, evening, and night and SHALL highlight the peak
bucket. The achievement-rarity breakdown SHALL show the count of unlocked achievements per rarity
tier as a stacked bar with a per-tier legend. The most-played-games list SHALL rank games by
tracked minutes within the window, distinct from the Library's lifetime playtime ordering, and
SHALL show at most five entries.

#### Scenario: Viewing analytics with data
- **WHEN** the Analytics screen is shown and tracked sessions exist within the selected window
- **THEN** the screen presents a daily playtime bar chart, a streak summary, and a most-played-games
  list, each derived from locally stored state

#### Scenario: Daily playtime chart
- **WHEN** the Analytics screen is shown with tracked minutes on one or more days in the window
- **THEN** the chart draws one bar per local day in the window, with the bar height proportional to
  that day's tracked minutes, and a horizontal reference line at the configured daily-quest
  threshold. The chart includes readable max, midpoint, and baseline labels, sparse date labels for
  the window endpoints, and a legend identifying the quest threshold

#### Scenario: Quest threshold reference line
- **WHEN** the daily-quest threshold is greater than zero
- **THEN** the chart draws a reference line at that threshold value, so days whose bar reaches or
  exceeds the line are legible as quest-met days

#### Scenario: Readable chart scale
- **WHEN** the daily playtime window contains a high-minute outlier
- **THEN** the chart uses a rounded ceiling with visible max, midpoint, and baseline labels so the
  remaining bars can be compared without an arbitrary peak value, while preserving proportional bar
  heights

#### Scenario: Inspecting a chart day
- **WHEN** the user taps a day in the daily playtime chart
- **THEN** that bar is visually selected and the chart presents the day's date, tracked minutes, and
  whether the configured daily goal was met

#### Scenario: Inspected day broken down by game
- **WHEN** the user taps a day in the daily playtime chart that has tracked minutes
- **THEN** the screen additionally lists the games played on that day and each game's tracked
  minutes on that day, ordered by minutes descending

#### Scenario: Inspecting a day with no tracked minutes
- **WHEN** the user taps a day in the daily playtime chart that has no tracked minutes
- **THEN** the day's date and zero total are presented without a game breakdown, rather than an
  empty list

#### Scenario: Distinguishing the chart baseline
- **WHEN** the daily playtime chart is shown
- **THEN** the zero-minute baseline is rendered as a visible solid axis beneath the bars and remains
  visually distinct from the dashed daily-goal reference line

#### Scenario: Selecting a window length
- **WHEN** the user selects a window length
- **THEN** the daily chart, most-played games, session insights, quest-met day count, and
  time-of-day pattern all update to describe that length, without requiring a network call

#### Scenario: Two-week length always offered
- **WHEN** the window length options are presented
- **THEN** a two-week option is among them, whichever anchor period is selected

#### Scenario: Moving the anchor to an earlier period
- **WHEN** the user moves the window anchor to an earlier period
- **THEN** every windowed figure describes that earlier period, derived from locally stored
  sessions, without requiring a network call

#### Scenario: Calendar length steps by calendar period
- **WHEN** a calendar length is selected and the user moves the anchor one period earlier
- **THEN** the window describes the immediately preceding calendar period in full, whatever its day
  count, rather than a fixed number of days back

#### Scenario: Rolling length steps by its own duration
- **WHEN** a rolling length is selected and the user moves the anchor one period earlier
- **THEN** the window describes the duration of that length immediately preceding the previous
  window, with no gap and no overlap

#### Scenario: Similar lengths step differently
- **WHEN** the user steps a 30-day window and a one-month window back from the same anchor
- **THEN** the 30-day window moves back exactly 30 days and the one-month window moves to the
  previous calendar month, and the length selection distinguishes the two

#### Scenario: Calendar periods of differing lengths
- **WHEN** the user steps a one-month window across months with different day counts
- **THEN** each window covers its whole calendar month, and every windowed figure describes exactly
  that month

#### Scenario: Anchor bounded by available history
- **WHEN** the earliest tracked session is more recent than an earlier period the user attempts to
  reach
- **THEN** the anchor cannot be moved to that period, rather than presenting a period that no data
  could populate

#### Scenario: Anchor with no sessions inside available history
- **WHEN** the selected anchor period lies within available history but contains no tracked sessions
- **THEN** the screen presents its empty state for that period, and the anchor remains movable back
  to a period that has data

#### Scenario: Streak summary
- **WHEN** the Analytics screen is shown
- **THEN** the streak summary shows the current streak, the longest streak, and the number of
  quest-met days within the selected window

#### Scenario: Streaks do not follow the window
- **WHEN** the user moves the anchor to an earlier period
- **THEN** the current and longest streak continue to report the player's present counters, and are
  presented so they are not read as describing the selected period, while the quest-met day count
  describes that period

#### Scenario: Most-played games
- **WHEN** the Analytics screen is shown and one or more games have tracked minutes in the window
- **THEN** up to five games are listed, ordered by tracked minutes in the window descending, each
  with its name, icon, and tracked minutes in the window

#### Scenario: Most-played games distinct from lifetime playtime
- **WHEN** a game's lifetime Steam playtime greatly exceeds its tracked minutes in the window
- **THEN** it is ranked by its tracked minutes in the window, not by its lifetime playtime

#### Scenario: Session insights
- **WHEN** the Analytics screen is shown and one or more sessions exist in the window
- **THEN** the session-insights summary shows the number of sessions, the average session length,
  and the longest session within the window

#### Scenario: Time-of-day pattern
- **WHEN** the Analytics screen is shown and one or more sessions exist in the window
- **THEN** the time-of-day pattern buckets tracked minutes into morning, afternoon, evening, and
  night, and highlights the bucket with the most minutes as the peak time

#### Scenario: Achievement rarity breakdown
- **WHEN** the Analytics screen is shown and one or more achievements are unlocked
- **THEN** the rarity breakdown shows each rarity tier's unlocked count as a segment of a stacked
  bar with a per-tier legend, using the same tier colors as the game-detail screen

#### Scenario: Rarity breakdown does not follow the window
- **WHEN** the user selects any window length or anchor
- **THEN** the achievement-rarity breakdown continues to describe all unlocked achievements, and is
  presented so it is not read as describing the selected period

#### Scenario: No tracked sessions in the window
- **WHEN** the Analytics screen is shown and no tracked sessions exist within the selected window
- **THEN** the screen presents an empty state explaining that analytics appear after playing and
  syncing, rather than showing empty charts

#### Scenario: Offline rendering
- **WHEN** the Analytics screen is shown without network
- **THEN** it renders from the last stored state without blocking

#### Scenario: Not configured
- **WHEN** Steam credentials are not configured
- **THEN** the Analytics screen presents a not-configured state rather than empty charts

### Requirement: Achievement rarity drill-down
The achievement-rarity breakdown SHALL expand to list the twenty rarest unlocked achievements,
ordered from rarest to least rare, each identified by its game and achievement name with the
rarity figure that ordered it. The list SHALL order by the same percent that determined each
achievement's rarity tier, so an achievement's position and its displayed tier cannot disagree.
Fewer than twenty unlocked achievements SHALL list all of them rather than padding the list.

#### Scenario: Expanding the rarity breakdown
- **WHEN** the user selects the achievement-rarity breakdown
- **THEN** the twenty rarest unlocked achievements are listed, rarest first

#### Scenario: Rarity figure consistent with tier
- **WHEN** a listed achievement shows a rarity tier
- **THEN** the rarity figure shown beside it is the one that determined that tier, so the ordering
  and the tier agree

#### Scenario: Fewer than twenty unlocked achievements
- **WHEN** the player has fewer than twenty unlocked achievements
- **THEN** all of them are listed, rarest first, with no placeholder entries

#### Scenario: No unlocked achievements
- **WHEN** the player has no unlocked achievements
- **THEN** the breakdown does not offer an expansion, rather than expanding to an empty list

#### Scenario: Achievement identified by game
- **WHEN** the rarest achievements span more than one game
- **THEN** each listed achievement names the game it belongs to, so identically-named achievements
  from different games are distinguishable

#### Scenario: Collapsing the drill-down
- **WHEN** the user collapses the expanded list
- **THEN** the stacked bar and its per-tier legend are shown as before, unchanged

### Requirement: Import Steam history control
The system SHALL provide a control that lets the user import their historical Steam playtime,
and SHALL reflect whether history has already been imported so the action is clearly one-time.

#### Scenario: Offering the import
- **WHEN** the user has not yet imported historical playtime
- **THEN** the UI presents a control to import Steam history

#### Scenario: Reflecting completed import
- **WHEN** historical playtime has already been imported
- **THEN** the control reflects the imported state rather than offering the import again as if unused

#### Scenario: Communicating the effect before importing
- **WHEN** the user is about to import history
- **THEN** the UI indicates that importing counts past playtime toward XP and is a one-time action

#### Scenario: Resetting a completed import
- **WHEN** historical playtime has been imported
- **THEN** the UI offers a control to reset the import, and after resetting the import is
  offered again

### Requirement: Notification permission requested in-app
On platform versions that require a runtime grant for posting notifications, the app SHALL request
that permission from within the app, so notification-bearing features work on a fresh install
without the user locating a system settings toggle unaided.

#### Scenario: Permission not yet granted
- **WHEN** the app runs on a platform version requiring a runtime notification grant and the
  permission has not been granted or denied
- **THEN** the app requests it

#### Scenario: Permission granted
- **WHEN** the notification permission is granted
- **THEN** the ongoing now-playing notification is posted and updated as specified

#### Scenario: Permission denied
- **WHEN** the user denies the notification permission
- **THEN** the app continues to function, presence tracking is unaffected, and no notification is
  posted

#### Scenario: Permission not re-requested
- **WHEN** the user has already responded to the request
- **THEN** the app does not repeatedly prompt on subsequent launches

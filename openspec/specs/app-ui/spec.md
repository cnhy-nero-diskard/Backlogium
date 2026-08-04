# app-ui

## Purpose

Defines the Android app's UI behavior: the app shell and navigation, the Steam profile
header, the Home screen,
visual theming, typography, iconography, game art states, celebratory animations, the
Library screen, the History screen, and sync feedback in the app shell.

## Requirements

### Requirement: App shell and navigation
The system SHALL present a Compose UI with navigation between Home, Library, History, and
Settings screens, and all screens SHALL render from locally stored state so the app
is fully usable offline.

#### Scenario: Offline launch
- **WHEN** the app is opened without network
- **THEN** all screens display the last synced state and never block on a network call

#### Scenario: Navigating between screens
- **WHEN** the user selects a destination from the app's navigation
- **THEN** the corresponding screen (Home, Library, History, or Settings) is shown

### Requirement: Steam profile header
The system SHALL present a persistent profile header at the top of the app shell showing the
player's Steam avatar, persona name, and current presence state, and the header SHALL render
from locally stored identity so it is populated on an offline launch.

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

#### Scenario: Hidden while unconfigured
- **WHEN** Steam credentials are not configured
- **THEN** no profile header is shown, and the onboarding takeover is presented as it is today

#### Scenario: App XP level not duplicated
- **WHEN** the header is shown
- **THEN** it does not present a level number, so the player's Steam level is never confused
  with the app's own XP level

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
in-game state. Home SHALL present progress content only: account, sync, and data-management
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

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection, each card
rendering the collection's name and its mode-specific banner. Tapping a collection card SHALL open the
collection's management screen. The collections section SHALL render from locally stored state so it is
usable offline, and SHALL present an empty state when no collections exist. The collections section SHALL
NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces on Home.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection, displaying its name and its mode-specific banner

#### Scenario: Opening a collection
- **WHEN** the user taps a collection card on Home
- **THEN** the collection's management screen is opened

#### Scenario: No collections
- **WHEN** the Home screen is shown and no collections exist
- **THEN** the collections section presents an empty state rather than an empty list

#### Scenario: Offline rendering
- **WHEN** the Home screen is shown without network
- **THEN** the collections section renders from the last stored state without blocking

#### Scenario: Existing Home surfaces preserved
- **WHEN** the collections section is added to Home
- **THEN** the level, XP, daily-quest, streak, and now-playing surfaces remain present and unchanged

### Requirement: Collection management screen
The system SHALL provide a collection management screen, reached as a pushed sub-destination from a Home
collection card, where the user can create a collection, choose its mode, name it, add and remove games,
set a target date for deadline-goal collections, reorder members for ordered-queue collections, and delete
the collection. The screen SHALL render from locally stored state.

#### Scenario: Creating a collection
- **WHEN** the user creates a new collection with a name and a mode
- **THEN** the collection is persisted and appears on the Home collections section

#### Scenario: Adding games to a collection
- **WHEN** the user adds games to a collection from the management screen
- **THEN** those games become members of the collection

#### Scenario: Removing games from a collection
- **WHEN** the user removes a game from a collection
- **THEN** that game is no longer a member of the collection

#### Scenario: Setting a deadline
- **WHEN** the user sets or changes a target date on a deadline-goal collection
- **THEN** the collection's banner reflects the updated countdown

#### Scenario: Reordering an ordered queue
- **WHEN** the user reorders members of an ordered-queue collection
- **THEN** the sequence is persisted and the next-game surface updates

#### Scenario: Deleting a collection
- **WHEN** the user deletes a collection
- **THEN** the collection and its memberships are removed, and it no longer appears on Home

#### Scenario: Empty collection on the management screen
- **WHEN** a collection has no members
- **THEN** the management screen presents an empty state with a control to add games

#### Scenario: Target date only for deadline mode
- **WHEN** the user is editing a collection whose mode is not deadline goal
- **THEN** no target date field is offered

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

### Requirement: Library screen
The system SHALL provide a Library screen separating a curated, actively-tracked set of games from
the rest of the library, and SHALL allow adding a game to that set and removing it. Any game SHALL
display progress against a HowLongToBeat-sourced completion length when one is available, whether or
not it belongs to the curated set, and SHALL display no completion-based progress when none is
available. The curated set SHALL be labelled in terms of active tracking rather than in terms of a
user-entered target, since no such target is collected, and the remaining games SHALL be labelled
without implying that they are unplayed or awaiting play.

#### Scenario: Game with an HLTB length shows progress
- **WHEN** the Library is shown and a game has a HowLongToBeat-sourced completion length
- **THEN** the game displays its name, icon, and playtime, and a progress indicator measuring its
  playtime against that completion length, regardless of whether it belongs to the curated set

#### Scenario: Game played past its completion length
- **WHEN** a game's playtime exceeds its HowLongToBeat-sourced completion length
- **THEN** its progress indicator represents the whole playtime, showing the completion length and
  the excess beyond it as visually distinct portions of one full indicator, rather than resting at
  full with the excess unrepresented

#### Scenario: Game without an HLTB length shows no progress
- **WHEN** the Library is shown and a game has no HowLongToBeat-sourced completion length yet
- **THEN** the game displays its name, icon, and playtime, and does not display completion-based
  progress

#### Scenario: Adding a game to the tracked set
- **WHEN** the user adds a game to the tracked set, or removes one from it
- **THEN** the game moves between the tracked section and the rest of the library and the change
  persists, without prompting for a typed target

#### Scenario: Tracked games appear once
- **WHEN** a game belongs to the tracked set
- **THEN** it appears only in the tracked section and not also among the remaining games

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
- **THEN** the matching games are presented in the chosen sort order

### Requirement: Library search
The system SHALL provide a name search that filters the Library, preserving the section structure
for sections that still contain matches.

#### Scenario: Filtering by name
- **WHEN** the user enters text in the Library search
- **THEN** only games whose names contain that text, ignoring case, are shown

#### Scenario: Sections preserved while filtering
- **WHEN** a filter is active and matches exist in more than one section
- **THEN** each section with matches keeps its heading

#### Scenario: No matches
- **WHEN** a filter matches no games
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

### Requirement: HLTB match review
The system SHALL provide a surface listing games flagged as needing an HLTB match, and SHALL let the user open a flagged game and select the correct HowLongToBeat entry from its candidates.

#### Scenario: Reviewing flagged games
- **WHEN** the user opens the match-review surface and games are flagged as needing review
- **THEN** each flagged game is listed with its candidate HowLongToBeat entries available for selection

#### Scenario: Confirming a match
- **WHEN** the user selects the correct candidate for a flagged game
- **THEN** the game is marked resolved, its completion length becomes available to the goal and gamification features, and it is removed from the review list

#### Scenario: No games need review
- **WHEN** the user opens the match-review surface and no games are flagged
- **THEN** the surface indicates there is nothing to review

### Requirement: Game detail screen with achievements
The system SHALL provide a game detail screen, reachable by selecting a game from the
Library, that lists that game's achievements with each achievement's unlock state, rarity
tier, and the XP it contributes, using its display name and icon when available. The screen
SHALL also show the game's current Steam concurrent-player count when available, and SHALL
show no such line when it is not. The screen SHALL present the game's summary above the
achievement list, so a game with no achievement data still shows its own information rather
than only an empty state.

#### Scenario: Opening a game's detail
- **WHEN** the user selects a game in the Library
- **THEN** a detail screen for that game is shown listing its achievements

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

### Requirement: Game summary section
The game detail screen SHALL present a summary of the game above its achievement list, showing the
game's art, its playtime, its known HowLongToBeat completion lengths, its achievement completion,
and its XP contribution.

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
The system SHALL provide a History screen listing recently synthesized sessions and
per-day play statistics.

#### Scenario: Recent sessions
- **WHEN** the History screen is shown
- **THEN** it lists recent sessions with game, date, and duration, most recent first

#### Scenario: Daily stats
- **WHEN** daily progress exists
- **THEN** the screen shows per-day totals and whether each day's quest was met

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

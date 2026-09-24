package com.example.backlogium.ui.screenshot

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.ui.analytics.AnalyticsContent
import com.example.backlogium.ui.analytics.AnalyticsDay
import com.example.backlogium.ui.analytics.AnalyticsGame
import com.example.backlogium.ui.analytics.AnalyticsHeadline
import com.example.backlogium.ui.analytics.AnalyticsRarityAchievement
import com.example.backlogium.ui.analytics.AnalyticsUiState
import com.example.backlogium.ui.analytics.AnalyticsWindow
import com.example.backlogium.ui.analytics.AnalyticsWindowLength
import com.example.backlogium.ui.analytics.RarityBreakdown
import com.example.backlogium.ui.analytics.SessionInsights
import com.example.backlogium.ui.analytics.TimeOfDayPattern
import com.example.backlogium.ui.home.HomeCollectionCard
import com.example.backlogium.ui.home.HomeCollectionGame
import com.example.backlogium.ui.home.HomeContent
import com.example.backlogium.ui.home.HomeNextAction
import com.example.backlogium.ui.home.HomeNextGame
import com.example.backlogium.ui.home.HomeSmartCollectionCard
import com.example.backlogium.ui.home.HomeUiState
import com.example.backlogium.ui.history.HistoryAchievements
import com.example.backlogium.ui.history.HistoryContent
import com.example.backlogium.ui.history.HistoryDayGroup
import com.example.backlogium.ui.history.HistoryGameGroup
import com.example.backlogium.ui.history.HistoryGameThumbnails
import com.example.backlogium.ui.history.HistorySessionUi
import com.example.backlogium.ui.history.HistoryUiState
import com.example.backlogium.ui.library.BacklogGameUi
import com.example.backlogium.ui.library.GoalGameUi
import com.example.backlogium.ui.library.LibraryBatchGame
import com.example.backlogium.ui.library.LibraryContent
import com.example.backlogium.ui.library.LibraryFilters
import com.example.backlogium.ui.library.LibraryUiState
import com.example.backlogium.ui.library.WishlistEntryUi
import com.example.backlogium.ui.library.WishlistPriceUi
import com.example.backlogium.ui.library.WishlistUiState
import com.example.backlogium.ui.settings.SettingsOverviewScreen
import com.example.backlogium.ui.settings.SettingsUiState
import com.example.backlogium.work.GenreEnrichmentStatus
import java.time.Instant
import java.time.LocalDate

/** Representative states used to cover the five primary destinations and high-risk alternatives. */
internal enum class MainFixtureKind {
    HOME_POPULATED,
    HOME_NOW_PLAYING,
    HOME_FIRST_LOAD,
    LIBRARY_POPULATED,
    LIBRARY_NO_RESULTS,
    LIBRARY_SELECTION,
    HISTORY_POPULATED,
    HISTORY_EMPTY,
    ANALYTICS_POPULATED,
    ANALYTICS_SELECTED_DAY,
    ANALYTICS_EMPTY_WINDOW,
    SETTINGS_OVERVIEW,
    SETTINGS_HEALTHY,
    SETTINGS_ATTENTION,
}

/**
 * Render the actual stateless production content with local, deterministic presentation state.
 * Coil's local loader is the test seam for all Steam art requests; its fetcher never accesses network.
 */
@Suppress("DEPRECATION") // A composition-scoped loader keeps the fake isolated to screenshot captures.
@Composable
internal fun MainScreenshotFixtureHost(fixture: MainScreenshotFixture) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(FixtureArtworkFetcher.Factory()) }
            .build()
    }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        when (fixture.kind) {
            MainFixtureKind.HOME_POPULATED -> HomeContent(populatedHomeState())
            MainFixtureKind.HOME_NOW_PLAYING -> HomeContent(
                state = nowPlayingHomeState(),
                nowMillis = { FIXED_SCREEN_TIME_MILLIS },
            )
            MainFixtureKind.HOME_FIRST_LOAD -> HomeContent(HomeUiState())
            MainFixtureKind.LIBRARY_POPULATED -> LibraryContent(
                state = populatedLibraryState(),
                wishlistState = screenshotWishlistState(),
            )
            MainFixtureKind.LIBRARY_NO_RESULTS -> LibraryContent(
                state = noResultsLibraryState(),
                wishlistState = screenshotWishlistState(),
            )
            MainFixtureKind.LIBRARY_SELECTION -> LibraryContent(
                state = populatedLibraryState().copy(
                    selectionMode = true,
                    selection = setOf(220L, 610L),
                ),
                wishlistState = screenshotWishlistState(),
            )
            MainFixtureKind.HISTORY_POPULATED -> HistoryContent(populatedHistoryState())
            MainFixtureKind.HISTORY_EMPTY -> HistoryContent(
                HistoryUiState(loading = false, configured = true, today = FIXED_TODAY.toString()),
            )
            MainFixtureKind.ANALYTICS_POPULATED -> AnalyticsContent(analyticsState())
            MainFixtureKind.ANALYTICS_SELECTED_DAY -> AnalyticsContent(analyticsState(selectedDay = true))
            MainFixtureKind.ANALYTICS_EMPTY_WINDOW -> AnalyticsContent(analyticsState(empty = true))
            MainFixtureKind.SETTINGS_OVERVIEW -> SettingsOverviewScreen(
                state = settingsState(),
                onOpenGroup = {},
            )
            MainFixtureKind.SETTINGS_HEALTHY -> SettingsOverviewScreen(
                state = settingsState(healthy = true),
                onOpenGroup = {},
            )
            MainFixtureKind.SETTINGS_ATTENTION -> SettingsOverviewScreen(
                state = settingsState(attention = true),
                onOpenGroup = {},
            )
        }
    }
}

private val FIXED_TODAY: LocalDate = LocalDate.of(2026, 1, 15)

private fun fixtureArt(label: String): String = "fixture://art/$label"

private fun populatedHomeState(): HomeUiState {
    val collectionMembers = listOf(
        CollectionMemberSignals(
            appId = 220L,
            name = "Outer Wilds",
            playtimeMinutes = 540,
            completionistMinutes = 1_200,
            achievementsUnlocked = 12,
            achievementsTotal = 31,
        ),
        CollectionMemberSignals(
            appId = 610L,
            name = "Celeste",
            playtimeMinutes = 360,
            completionistMinutes = 900,
            achievementsUnlocked = 15,
            achievementsTotal = 30,
        ),
    )
    val collection = HomeCollectionCard(
        collectionId = 41L,
        name = "Evening rotation",
        mode = CollectionMode.BASIC,
        accent = CollectionAccent.VIOLET,
        banner = CollectionSummary.derive(
            mode = CollectionMode.BASIC,
            sort = CollectionSort.NAME,
            targetDate = null,
            members = collectionMembers,
            today = FIXED_TODAY,
        ),
        games = listOf(
            HomeCollectionGame(220L, "Outer Wilds", fixtureArt("outer-wilds-icon")),
            HomeCollectionGame(610L, "Celeste", fixtureArt("celeste-icon")),
        ),
    )

    return HomeUiState(
        loading = false,
        hasRenderableContent = true,
        configured = true,
        level = 12,
        xpIntoLevel = 370,
        xpForNext = 500,
        totalXp = 6_370,
        questMet = false,
        todayMinutes = 24,
        questThreshold = 45,
        currentStreak = 4,
        longestStreak = 9,
        nextAction = HomeNextAction.ContinueFocus(
            HomeNextGame(220L, "Outer Wilds", fixtureArt("outer-wilds-action")),
        ),
        collections = listOf(collection),
        smartCollections = listOf(
            HomeSmartCollectionCard(SmartCollectionId.QUICK_WINS, memberCount = 3),
            HomeSmartCollectionCard(SmartCollectionId.NEVER_STARTED, memberCount = 5),
        ),
    )
}

private fun nowPlayingHomeState(): HomeUiState = populatedHomeState().copy(
    isInGame = true,
    nowPlayingName = "Hades II",
    nowPlayingIconUrl = fixtureArt("hades-icon"),
    nowPlayingHeaderUrl = fixtureArt("hades-header"),
    nowPlayingSessionStartedAt = FIXED_SCREEN_TIME_MILLIS - 26 * 60_000L,
    nowPlayingRecencyState = GameRecencyState.RETURNED,
)

private fun populatedLibraryState(): LibraryUiState = LibraryUiState(
    loading = false,
    configured = true,
    goalGames = listOf(
        GoalGameUi(
            appId = 440L,
            name = "Hades II",
            iconUrl = fixtureArt("hades-icon"),
            headerUrl = fixtureArt("hades-header"),
            heroCapsuleUrl = fixtureArt("hades-hero"),
            playtimeForever = 3_240,
            playtime2Weeks = 480,
            xpContributed = 1_160L,
            completionistMinutes = 5_040,
            hltbStatus = HltbMatchState.RESOLVED,
            achievementUnlocked = 18,
            achievementTotal = 49,
            isCurrentlyPlaying = true,
            recencyState = GameRecencyState.NEWLY_PLAYED,
        ),
    ),
    backlog = listOf(
        BacklogGameUi(
            appId = 220L,
            name = "Outer Wilds",
            iconUrl = fixtureArt("outer-wilds-icon"),
            headerUrl = fixtureArt("outer-wilds-header"),
            heroCapsuleUrl = fixtureArt("outer-wilds-hero"),
            playtimeForever = 1_875,
            playtime2Weeks = 0,
            xpContributed = 640L,
            completionistMinutes = 1_200,
            hltbStatus = HltbMatchState.RESOLVED,
            achievementUnlocked = 22,
            achievementTotal = 31,
        ),
        BacklogGameUi(
            appId = 610L,
            name = "Celeste",
            iconUrl = fixtureArt("celeste-icon"),
            headerUrl = fixtureArt("celeste-header"),
            heroCapsuleUrl = fixtureArt("celeste-hero"),
            playtimeForever = 845,
            playtime2Weeks = 0,
            xpContributed = 290L,
            completionistMinutes = 900,
            hltbStatus = HltbMatchState.RESOLVED,
            achievementUnlocked = 17,
            achievementTotal = 30,
        ),
        BacklogGameUi(
            appId = 730L,
            name = "Balatro",
            iconUrl = fixtureArt("balatro-icon"),
            headerUrl = fixtureArt("balatro-header"),
            heroCapsuleUrl = fixtureArt("balatro-hero"),
            playtimeForever = 490,
            playtime2Weeks = 90,
            xpContributed = 165L,
            hltbStatus = HltbMatchState.NOT_COVERED,
        ),
    ),
    allGames = listOf(
        LibraryBatchGame(440L, "Hades II", HltbMatchState.RESOLVED),
        LibraryBatchGame(220L, "Outer Wilds", HltbMatchState.RESOLVED),
        LibraryBatchGame(610L, "Celeste", HltbMatchState.RESOLVED),
        LibraryBatchGame(730L, "Balatro", HltbMatchState.NOT_COVERED),
    ),
    reviewCount = 1,
    matchCenterCount = 1,
    filters = LibraryFilters(),
    focusSort = LibrarySortKey.NAME,
    librarySort = LibrarySortKey.PLAYTIME,
    focusSortDirection = LibrarySortDirection.ASCENDING,
    librarySortDirection = LibrarySortDirection.DESCENDING,
    density = GameListDensity.LIST,
    availableGenres = listOf(GameGenre("strategy", "Strategy"), GameGenre("indie", "Indie")),
    libraryEmpty = false,
)

private fun noResultsLibraryState(): LibraryUiState = populatedLibraryState().copy(
    goalGames = emptyList(),
    backlog = emptyList(),
    filters = LibraryFilters(
        query = "unreleased",
        selectedGenreIds = setOf("strategy"),
        notCoveredOnly = true,
        familySharedOnly = true,
    ),
)

private fun screenshotWishlistState(): WishlistUiState = WishlistUiState(
    configured = true,
    expanded = false,
    entries = listOf(
        WishlistEntryUi(
            appId = 999L,
            name = "Silksong",
            artworkUrl = fixtureArt("wishlist-silksong"),
            price = WishlistPriceUi.Current(
                formatted = "$19.99",
                listFormatted = "$24.99",
                discountPercent = 20,
                observedAt = FIXED_SCREEN_TIME_MILLIS,
            ),
            storeUrl = "https://store.steampowered.com/app/999/",
        ),
    ),
    availability = WishlistAvailability.AVAILABLE,
)

private fun populatedHistoryState(): HistoryUiState {
    val todayGame = HistoryGameGroup(
        appId = 440L,
        name = "Hades II",
        iconUrl = fixtureArt("hades-icon"),
        minutesPlayed = 76,
        sessions = listOf(
            HistorySessionUi(
                id = 1L,
                startAt = Instant.parse("2026-01-15T18:20:00Z").toEpochMilli(),
                minutes = 42,
                open = false,
            ),
            HistorySessionUi(
                id = 2L,
                startAt = Instant.parse("2026-01-15T20:10:00Z").toEpochMilli(),
                minutes = 34,
                open = false,
            ),
        ),
    )
    val earlierGames = listOf(
        HistoryGameGroup(
            appId = 220L,
            name = "Outer Wilds",
            iconUrl = fixtureArt("outer-wilds-icon"),
            minutesPlayed = 85,
            sessions = listOf(
                HistorySessionUi(
                    id = 3L,
                    startAt = Instant.parse("2026-01-14T17:10:00Z").toEpochMilli(),
                    minutes = 85,
                    open = false,
                ),
            ),
        ),
        HistoryGameGroup(
            appId = 610L,
            name = "Celeste",
            iconUrl = fixtureArt("celeste-icon"),
            minutesPlayed = 37,
            sessions = listOf(
                HistorySessionUi(
                    id = 4L,
                    startAt = Instant.parse("2026-01-14T21:05:00Z").toEpochMilli(),
                    minutes = 37,
                    open = false,
                ),
            ),
        ),
    )
    val todayGroup = HistoryDayGroup(
        date = FIXED_TODAY.toString(),
        minutesPlayed = 76,
        goalMinutesPlayed = 42,
        questMet = true,
        games = listOf(todayGame),
        gameThumbnails = HistoryGameThumbnails(games = listOf(todayGame)),
        achievements = HistoryAchievements(
            iconUrls = listOf(fixtureArt("achievement-1")),
            overflowCount = 0,
        ),
    )
    val earlierGroup = HistoryDayGroup(
        date = "2026-01-14",
        minutesPlayed = 122,
        goalMinutesPlayed = 85,
        questMet = true,
        games = earlierGames,
        gameThumbnails = HistoryGameThumbnails(games = earlierGames),
        achievements = HistoryAchievements(iconUrls = emptyList(), overflowCount = 0),
    )
    return HistoryUiState(
        loading = false,
        configured = true,
        days = listOf(todayGroup, earlierGroup),
        today = FIXED_TODAY.toString(),
    )
}

private fun analyticsState(
    selectedDay: Boolean = false,
    empty: Boolean = false,
): AnalyticsUiState {
    val window = AnalyticsWindow(FIXED_TODAY, AnalyticsWindowLength.ONE_MONTH)
    val dates = (9L..15L).map { FIXED_TODAY.withDayOfMonth(it.toInt()) }
    val minutes = when {
        empty -> listOf(0, 0, 0, 0, 0, 0, 0)
        selectedDay -> listOf(0, 25, 40, 0, 0, 95, 0)
        else -> listOf(25, 45, 0, 75, 120, 95, 35)
    }
    val days = dates.zip(minutes).map { (date, value) -> AnalyticsDay(date, value) }
    val games = listOf(
        AnalyticsGame(
            appId = 440L,
            name = "Hades II",
            iconUrl = fixtureArt("hades-icon"),
            minutes = 165,
            headerUrl = fixtureArt("hades-header"),
            heroCapsuleUrl = fixtureArt("hades-hero"),
        ),
        AnalyticsGame(
            appId = 220L,
            name = "Outer Wilds",
            iconUrl = fixtureArt("outer-wilds-icon"),
            minutes = 120,
            headerUrl = fixtureArt("outer-wilds-header"),
            heroCapsuleUrl = fixtureArt("outer-wilds-hero"),
        ),
        AnalyticsGame(
            appId = 610L,
            name = "Celeste",
            iconUrl = fixtureArt("celeste-icon"),
            minutes = 70,
        ),
    ).takeUnless { empty }.orEmpty()
    val gamesByDate = if (empty) {
        emptyMap()
    } else {
        mapOf(
            FIXED_TODAY.minusDays(1) to listOf(games[0], games[1]),
            FIXED_TODAY.minusDays(3) to listOf(games[2]),
        )
    }
    return AnalyticsUiState(
        loading = false,
        configured = true,
        window = window,
        windowBounds = window.resolve(),
        earliestTrackedDate = FIXED_TODAY.minusDays(20),
        canStepEarlier = true,
        canStepLater = false,
        isCurrentWindow = true,
        headline = when {
            empty -> AnalyticsHeadline.NoData
            selectedDay -> AnalyticsHeadline.LeadingGame("Hades II", 95)
            else -> AnalyticsHeadline.Compared(currentMinutes = 395, previousMinutes = 320)
        },
        dailyMinutes = days,
        questThreshold = 45,
        currentStreak = 4,
        longestStreak = 9,
        questMetDaysCount = if (empty) 0 else 4,
        topGames = games,
        rarityBreakdown = if (empty) {
            RarityBreakdown()
        } else {
            RarityBreakdown(common = 87, uncommon = 24, rare = 8, epic = 3, legendary = 1)
        },
        rarestAchievements = if (empty) {
            emptyList()
        } else {
            listOf(
                AnalyticsRarityAchievement(
                    appId = 440L,
                    gameName = "Hades II",
                    achievementName = "A rare escape",
                    rarityPercent = 0.4,
                    tier = RarityTier.LEGENDARY,
                ),
                AnalyticsRarityAchievement(
                    appId = 220L,
                    gameName = "Outer Wilds",
                    achievementName = "The final loop",
                    rarityPercent = 1.8,
                    tier = RarityTier.EPIC,
                ),
            )
        },
        sessionInsights = if (empty) SessionInsights() else SessionInsights(8, 48, 112),
        timeOfDayPattern = if (empty) {
            TimeOfDayPattern()
        } else {
            TimeOfDayPattern(morningMinutes = 25, afternoonMinutes = 75, eveningMinutes = 180, nightMinutes = 115)
        },
        gamesByDate = gamesByDate,
        familySharedMinutes = if (empty) 0 else 35,
    )
}

private fun settingsState(
    healthy: Boolean = false,
    attention: Boolean = false,
): SettingsUiState = SettingsUiState(
    loading = false,
    configured = true,
    steamId = "76561198000000000",
    apiKeyMasked = "••••••••••••••••",
    lastSyncAt = FIXED_SCREEN_TIME_MILLIS - 47 * 60_000L,
    lastSyncError = if (attention) "Sync failed" else null,
    isSyncing = attention,
    isReconciling = attention,
    cloudHealthy = if (healthy) true else if (attention) false else null,
    genreEnrichmentStatus = when {
        attention -> GenreEnrichmentStatus.RETRYING
        healthy -> GenreEnrichmentStatus.IDLE
        else -> GenreEnrichmentStatus.QUEUED
    },
    storedSteamAssetCount = 328,
    storedSteamAssetBytes = 48_200_000L,
    hasSteamAssetInventory = true,
)

/** The fake owns every art request in this host, so screenshot composition never reaches the web. */
private class FixtureArtworkFetcher(private val uri: Uri) : Fetcher {
    override suspend fun fetch() = DrawableResult(
        drawable = ColorDrawable(fixtureArtworkColor(uri)),
        isSampled = false,
        dataSource = DataSource.MEMORY,
    )

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher =
            FixtureArtworkFetcher(data)
    }
}

private fun fixtureArtworkColor(uri: Uri): Int {
    val hash = uri.toString().hashCode()
    val red = 56 + (hash and 0x7f)
    val green = 56 + ((hash ushr 8) and 0x7f)
    val blue = 56 + ((hash ushr 16) and 0x7f)
    return AndroidColor.rgb(red, green, blue)
}

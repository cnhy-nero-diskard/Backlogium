package com.example.backlogium.ui.gamedetail

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.SuccessResult
import coil.request.CachePolicy
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.AccountDataWriteGuard
import com.example.backlogium.data.repo.GamePreferenceRepository
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.domain.*
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.ui.collections.CollectionMemberUi
import com.example.backlogium.ui.collections.collectionMemberItems
import com.example.backlogium.ui.home.*
import com.example.backlogium.ui.library.*
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real production entry controls and detail body, Room preferences, Android Back and saved state.
 * Steam responses are deterministic local fixtures; no live credentials or network are used. */
@RunWith(AndroidJUnit4::class)
class GameDetailBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    private lateinit var db: BacklogiumDatabase
    private lateinit var preferences: GamePreferenceRepository
    private lateinit var loader: ImageLoader
    private var artworkModel: GameArtworkViewModel? = null
    private var previousFontScale: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var availableUrl: String? = null
    private val requests = CopyOnWriteArrayList<String>()
    private val networkPolicies = CopyOnWriteArrayList<CachePolicy>()
    private val rows = listOf(
        AchievementUi("earned", "First steps", null, true, RarityTier.RARE, 120, 35.0,
            1_768_464_000_000, "Complete the first chapter.", false, 8.0),
        AchievementUi("locked", "The hidden route", null, false, null, 0, 1.5,
            null, null, true),
    )
    private val summary = GameSummaryUi(playtimeMinutes = 300, trackedMinutes = 60, importedMinutes = 240,
        mainStoryMinutes = 480, achievementsUnlocked = 1, achievementsTotal = 2, xpContributed = 120,
        lastPlayedAt = 1_768_464_000_000)

    @Before fun setup() = runBlocking {
        previousFontScale = shell("settings get system font_scale").trim()
        shell("settings put system font_scale 1.6")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        db = Room.inMemoryDatabaseBuilder(compose.activity, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        db.playerProfileDao().upsert(PlayerProfile(steamId = "fixture"))
        db.gameDao().upsert(Game(10, "Portal", "", 300, 0, 1))
        preferences = GamePreferenceRepository(db, object : AccountDataWriteGuard {
            override suspend fun capture() = "fixture"
            override suspend fun check(steamId: String) { check(steamId == "fixture") }
        })
        loader = ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val url = chain.request.data.toString()
                requests.add(url)
                networkPolicies.add(chain.request.networkCachePolicy)
                if (url == availableUrl) {
                    val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff386fa0.toInt()) }
                    SuccessResult(BitmapDrawable(compose.activity.resources, bitmap), chain.request, DataSource.MEMORY_CACHE)
                } else ErrorResult(null, chain.request, IllegalStateException("Unavailable fixture artwork"))
            })
        }.build()
    }
    @After fun close() {
        ScreenshotTestActivity.recreationContent = null
        try {
            artworkModel?.viewModelScope?.cancel()
            scope.cancel()
            if (::loader.isInitialized) loader.shutdown()
            if (::db.isInitialized) db.close()
        } finally {
            previousFontScale?.let { previous ->
                shell(if (previous == "null" || previous.isBlank()) "settings delete system font_scale"
                    else "settings put system font_scale $previous")
            }
        }
    }

    @Test fun libraryHomeAndCollectionEntryBackAndRecreationRetainVisitLenses() {
        for (origin in listOf("Library", "Home", "Collection")) {
            mount(origin)
            open(origin)
            scroll("Unlocked").performClick()
            val earned = scroll("First steps")
            try {
                compose.waitUntil(5_000) { runCatching { earned.assertIsDisplayed() }.isSuccess }
            } finally { capture("${origin.lowercase()}-unlocked") }
            earned.assertIsDisplayed()
            compose.onNodeWithText("The hidden route").assertDoesNotExist()
            scroll("Locked").performClick()
            scroll("Current rarity").performClick()
            scroll("The hidden route").assertIsDisplayed()
            compose.onNodeWithText("First steps").assertDoesNotExist()
            scroll("1/2 achievements", substring = true).assertIsDisplayed()
            capture("${origin.lowercase()}-detail")
            if (origin == "Library") {
                compose.activityRule.scenario.recreate()
                scroll("Locked").assertIsSelected()
                compose.onNodeWithText("Current rarity").assertIsSelected()
                scroll("The hidden route").assertIsDisplayed()
            }
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
            open(origin)
            scroll("All").assertIsSelected()
            compose.onNodeWithText("Date achieved").assertIsSelected()
            androidx.test.espresso.Espresso.pressBack()
        }
    }

    @Test fun placeholderChooserSelectionResetAndFavoritePersistAtLargeFont() {
        val artwork = GameArtworkViewModel(preferences).also { artworkModel = it }
        val favorite = FavoriteActionController(scope, preferences::favorite, preferences::setFavorite)
        compose.runOnIdle { artwork.show(10); favorite.show(10) }
        compose.setContent {
            Frame(largeFont = true) {
                val art by artwork.state.collectAsState()
                val heart by favorite.state.collectAsState()
                GameDetailContent(GameDetailUiState(loading = false, gameName = "Portal", summary = summary,
                    achievements = rows), 10, favoriteState = heart, artworkState = art,
                    actions = GameDetailActions(onArtwork = artwork::select, onFavorite = favorite::toggle))
            }
        }
        compose.waitUntil { artwork.state.value.preference != null && favorite.state.value.favorite != null }
        compose.waitUntil { requests.size >= 5 }
        compose.onNodeWithTag("game-detail-cover").assertHeightIsEqualTo(120.dp)
        scroll("Add to favorites").performClick()
        compose.waitUntil { favorite.state.value.favorite?.isFavorite == true }
        availableUrl = SteamIconMapper.artworkUrl(10, GameArtworkVariant.WIDE_CAPSULE)
        scroll("Choose cover").performClick()
        compose.onNodeWithTag("steam-cover-HEADER").assertIsNotEnabled()
        compose.onNodeWithTag("steam-cover-WIDE_CAPSULE").performScrollTo()
        compose.waitUntil { compose.onAllNodes(hasTestTag("steam-cover-WIDE_CAPSULE") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("steam-cover-WIDE_CAPSULE").performClick()
        compose.waitUntil { artwork.state.value.preference?.variant == GameArtworkVariant.WIDE_CAPSULE }
        scroll("Manage cover").assertIsDisplayed()
        capture("cover-selected-large-font")
        scroll("Reset cover").performClick()
        compose.waitUntil { artwork.state.value.preference?.variant == null && !artwork.state.value.pending }
        scroll("Remove from favorites").assertIsDisplayed()
        runBlocking { assertEquals(null, db.gamePreferenceDao().get(10)?.artworkVariant); assertTrue(db.gamePreferenceDao().get(10)!!.isFavorite) }
    }

    @Test fun refreshPendingAndEveryOutcomeKeepCachedRowsAndPlayerRefreshSeparate() {
        val refresh = mutableStateOf(AchievementRefreshActionState())
        var requests = 0
        var players = 0
        compose.setContent { Frame {
            GameDetailContent(GameDetailUiState(loading = false, gameName = "Portal", summary = summary,
                achievements = rows, achievementRefresh = refresh.value), 10,
                actions = GameDetailActions(onRefreshAchievements = { requests++; refresh.value = AchievementRefreshActionState(pending = true) },
                    onRefreshPlayerCount = { players++ }))
        } }
        scroll("Refresh achievements").performClick()
        compose.onNodeWithText("Refreshing achievements…").assertIsNotEnabled()
        assertEquals(1, requests); assertEquals(0, players)
        for (outcome in AchievementRefreshOutcome.entries) {
            compose.runOnIdle { refresh.value = AchievementRefreshActionState(outcome = outcome) }
            compose.onNodeWithTag("game-detail-list").performScrollToNode(hasText("First steps"))
            compose.onNodeWithText("First steps").assertIsDisplayed()
            compose.onNodeWithText("Current: 35.0%", substring = true).assertIsDisplayed()
            val expected = when (outcome) {
                AchievementRefreshOutcome.UPDATED -> "Achievements updated."
                AchievementRefreshOutcome.NO_CHANGE -> "Achievements are up to date. No changes."
                AchievementRefreshOutcome.NO_USABLE_DATA -> "Steam returned no usable"
                AchievementRefreshOutcome.FAILED -> "Could not refresh achievements."
                AchievementRefreshOutcome.NEEDS_CREDENTIALS -> "Configure Steam credentials"
                AchievementRefreshOutcome.DISCARDED -> "Account or game changed."
            }
            scroll(expected, substring = true).assertIsDisplayed()
            capture("refresh-${outcome.name.lowercase()}")
        }
        compose.runOnIdle { refresh.value = AchievementRefreshActionState(outcome = AchievementRefreshOutcome.FAILED) }
        scroll("Retry achievement refresh").performClick()
        assertEquals(2, requests); assertEquals(0, players)
    }

    @Test fun filteredEmptyStateKeepsCompletionTotals() {
        compose.setContent { Frame(largeFont = true) {
            GameDetailContent(GameDetailUiState(loading = false, gameName = "Portal",
                summary = summary.copy(achievementsUnlocked = 2), filter = AchievementFilter.LOCKED), 10)
        } }
        scroll("No locked achievements.").assertIsDisplayed()
        scroll("Every achievement unlocked").assertIsDisplayed()
        scroll("2/2 achievements", substring = true).assertIsDisplayed()
        capture("completed-filter-large-font")
    }

    @Test fun actualFallbackImagePublishesAccentAndOverlayKeepsItLocal() {
        availableUrl = SteamIconMapper.artworkUrl(10, GameArtworkVariant.LIBRARY_HERO)
        var accent: Color? = null
        val overlay = mutableStateOf(false)
        var publications = 0
        compose.setContent { Frame {
            GameDetailContent(GameDetailUiState(loading = false, gameName = "Portal", summary = summary), 10,
                presentation = if (overlay.value) GameDetailPresentation.COLLECTION_OVERLAY else GameDetailPresentation.FULL_DESTINATION,
                onAccentColorChanged = { accent = it; publications++ })
        } }
        compose.waitUntil { accent != null }
        assertEquals(SteamIconMapper.artworkUrl(10, GameArtworkVariant.HEADER), requests.first())
        assertTrue(accent!!.blue > accent!!.red)
        compose.onNodeWithTag("game-detail-cover").assertHeightIsEqualTo(120.dp)
        val prior = publications
        compose.runOnIdle { overlay.value = true }
        compose.waitForIdle()
        assertEquals(prior, publications)
    }

    @Test fun offlineChooserOffersCachedVariantAndUnavailableSelectionReset() {
        val wifi = shell("settings get global wifi_on")
        val mobile = shell("settings get global mobile_data")
        try {
            shell("svc wifi disable"); shell("svc data disable")
            val connectivity = compose.activity.getSystemService(ConnectivityManager::class.java)
            compose.waitUntil(10_000) { connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true }
            availableUrl = SteamIconMapper.artworkUrl(10, GameArtworkVariant.WIDE_CAPSULE)
            var selected: GameArtworkVariant? = GameArtworkVariant.HEADER
            var reset = false
            compose.setContent { Frame(largeFont = true) {
                SteamArtworkChooser(10, GameArtworkVariant.HEADER, false, {}, { selected = it; reset = it == null })
            } }
            compose.onNodeWithText("Offline: only cached covers are available.").assertIsDisplayed()
            compose.onNodeWithTag("steam-cover-HEADER").assertIsNotEnabled()
            compose.onNodeWithTag("steam-cover-WIDE_CAPSULE").performScrollTo()
            compose.waitUntil { compose.onAllNodes(hasTestTag("steam-cover-WIDE_CAPSULE") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("steam-cover-WIDE_CAPSULE").performClick()
            assertEquals(GameArtworkVariant.WIDE_CAPSULE, selected)
            assertTrue(networkPolicies.isNotEmpty()); assertTrue(networkPolicies.all { it == CachePolicy.DISABLED })
            capture("cover-offline-large-font")
            compose.onNodeWithText("Reset cover").performClick()
            assertTrue(reset)
        } finally {
            if (wifi == "1") shell("svc wifi enable")
            if (mobile == "1") shell("svc data enable")
        }
    }

    @Test fun sharedAndMissingDataExplainSourcesWithoutInventedValues() {
        val shared = mutableStateOf(true)
        compose.setContent { Frame {
            GameDetailContent(GameDetailUiState(loading = false, gameName = "Portal", summary = if (shared.value)
                GameSummaryUi(isFamilyShared = true, trackedMinutes = 60, manualMinutes = 120) else GameSummaryUi()), 10)
        } }
        scroll("1h tracked · 2h manual estimate").assertIsDisplayed()
        capture("shared-provenance")
        scroll("Tracked time is what Backlogium observed", substring = true).assertIsDisplayed()
        scroll("Edit hours played").performClick()
        compose.onNodeWithText("Your own estimate", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { shared.value = false }
        scroll("No completion estimates cached.").assertIsDisplayed()
        scroll("No genre metadata cached.").assertIsDisplayed()
        scroll("Never played").assertIsDisplayed()
        capture("missing-data")
        scroll("No achievements to show for this game yet.").assertIsDisplayed()
        scroll("View on Steam").assertIsDisplayed()
    }

    @Suppress("DEPRECATION")
    @Composable private fun Frame(largeFont: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalImageLoader provides loader,
            LocalDensity provides Density(density.density, if (largeFont) 1.6f else density.fontScale)) {
            BacklogiumTheme(darkTheme = true) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { content() } }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun mount(origin: String) {
        val content: @Composable () -> Unit = {
            val host = LocalContext.current as androidx.activity.ComponentActivity
            val model = ViewModelProvider(host, object : AbstractSavedStateViewModelFactory(host, null) {
                override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                    @Suppress("UNCHECKED_CAST") return VisitModel(handle) as T
                }
            })["detail-$origin", VisitModel::class.java]
            val lens by model.visit.lens.collectAsState()
            val open by model.open.collectAsState()
            val launch = { _: Long -> model.launch() }
            BackHandler(open && origin != "Collection", model::dismiss)
            Frame(largeFont = origin == "Collection") {
                val detail: @Composable () -> Unit = {
                    GameDetailContent(GameDetailUiState(loading = false, gameName = "Portal", summary = summary,
                        achievements = rows.visibleThrough(lens), sort = lens.sort, filter = lens.filter), 10,
                        presentation = if (origin == "Collection") GameDetailPresentation.COLLECTION_OVERLAY else GameDetailPresentation.FULL_DESTINATION,
                        actions = GameDetailActions(onSort = model.visit::setSort, onFilter = model.visit::setFilter))
                }
                if (open && origin != "Collection") detail() else {
                    when (origin) {
                        "Library" -> LibraryContent(LibraryUiState(loading = false, libraryEmpty = false,
                            filters = LibraryFilters("Portal"), backlog = listOf(BacklogGameUi(10, "Portal", "", playtimeForever = 300))),
                            actions = LibraryContentActions(onOpenGameDetail = launch))
                        "Home" -> HomeContent(HomeUiState(loading = false, hasRenderableContent = true,
                            nextAction = HomeNextAction.ContinueFocus(HomeNextGame(10, "Portal"))), HomeContentActions(onOpenGame = launch))
                        else -> {
                            val accent = MaterialTheme.colorScheme.primary
                            LazyColumn(Modifier.fillMaxSize()) { collectionMemberItems(listOf(CollectionMemberUi(10, "Portal", "")),
                                GameListDensity.LIST, accent, false, launch) }
                        }
                    }
                    if (open) ModalBottomSheet(onDismissRequest = model::dismiss,
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { detail() }
                }
            }
        }
        ScreenshotTestActivity.recreationContent = content
        if (origin == "Library") compose.setContent(content) else {
            compose.activityRule.scenario.recreate()
        }
    }
    class VisitModel(private val handle: SavedStateHandle) : ViewModel() {
        internal val visit = DetailVisitState(handle)
        val open = handle.getStateFlow("fixture-detail-open", false)
        fun launch() { visit.open(java.util.UUID.randomUUID().toString()); handle["fixture-detail-open"] = true }
        fun dismiss() { handle["fixture-detail-open"] = false }
    }
    private fun open(origin: String) {
        if (origin == "Home") compose.onNodeWithTag(HOME_NEXT_ACTION_PRIMARY_TAG).performScrollTo().performClick()
        else if (origin == "Library") {
            val card = hasClickAction() and !hasSetTextAction() and
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(card)
            compose.onNode(card).performScrollTo().performClick()
        } else compose.onNodeWithContentDescription("Open Portal details").performScrollTo().performClick()
    }
    private fun scroll(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        // Lazy prefetch can expose an unplaced node; scroll to a placed match before assertions.
        val placed = SemanticsMatcher("placed detail node") { it.layoutInfo.isPlaced }
        compose.onNodeWithTag("game-detail-list").performScrollToNode(hasText(text, substring = substring) and placed)
        return compose.onNodeWithText(text, substring = substring).performScrollTo()
    }
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText().trim()
        }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(compose.activity.getExternalFilesDir(null), "game-detail/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

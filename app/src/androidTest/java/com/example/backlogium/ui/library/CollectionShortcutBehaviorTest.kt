package com.example.backlogium.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.AccountDataWriteGuard
import com.example.backlogium.data.repo.CollectionMembershipRepository
import com.example.backlogium.data.repo.GamePreferenceRepository
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.domain.*
import com.example.backlogium.ui.collections.CollectionMemberUi
import com.example.backlogium.ui.collections.collectionMemberItems
import com.example.backlogium.ui.gamedetail.FavoriteActionController
import com.example.backlogium.ui.gamedetail.GameFavoriteAction
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.ui.components.DerivedCollectionCard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Production controls backed by real Room writes, exercised offline in an Android process. */
@RunWith(AndroidJUnit4::class)
class CollectionShortcutBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    private lateinit var db: BacklogiumDatabase
    private lateinit var memberships: CollectionMembershipRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identity = object : AccountDataWriteGuard {
        override suspend fun capture() = "fixture"
        override suspend fun check(steamId: String) { check(steamId == "fixture") }
    }
    private var collectionId = 0L
    private val library = mutableStateOf(LibraryUiState(loading = false, libraryEmpty = false,
        filters = LibraryFilters("Portal"), backlog = listOf(BacklogGameUi(10, "Portal", "", playtimeForever = 1))))

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(compose.activity, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        db.playerProfileDao().upsert(PlayerProfile(steamId = "fixture"))
        db.gameDao().upsert(Game(10, "Portal", "", 1, 0, 1))
        collectionId = db.collectionDao().insert(Collection(name = "Weekend", mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionSort.MANUAL_SEQUENCE, createdAt = 1))
        memberships = CollectionMembershipRepository(db, identity)
    }
    @After fun close() { scope.cancel(); db.close() }

    @Test fun libraryPickerAddsOnceAndWishlistHasNoMenuInEveryDensity() {
        val picker = CollectionPickerViewModel(memberships)
        compose.setContent {
            BacklogiumTheme {
                LibraryContent(library.value, wishlistState = WishlistUiState(configured = true, expanded = true,
                    availability = WishlistAvailability.AVAILABLE, entries = listOf(WishlistEntryUi(20, "Portal wanted", "",
                        WishlistPriceUi.Retained("$5", null, 0, 1_000), "https://store.steampowered.com/app/20"))),
                    actions = LibraryContentActions(onAddToCollection = picker::open))
                val state by picker.state.collectAsState()
                if (state.appId != null) CollectionPickerDialog(state, picker::add, picker::close, picker::close)
            }
        }
        for (density in GameListDensity.entries) {
            compose.runOnIdle { library.value = library.value.copy(density = density) }
            compose.onNodeWithContentDescription("Actions for Portal").performScrollTo().performClick()
            compose.onNodeWithText("Add to collection").performClick()
            compose.waitUntil { picker.state.value.picker != null }
            if (density == GameListDensity.LIST) {
                compose.onNodeWithTag("collection-target-$collectionId").performClick()
                compose.waitUntil { picker.state.value.feedback != null }
                compose.onNodeWithText("Portal added to Weekend").assertIsDisplayed()
            }
            compose.onNodeWithTag("collection-target-$collectionId").assertIsNotEnabled()
            capture("picker-${density.name}")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithContentDescription("Actions for Portal wanted").assertDoesNotExist()
            assertEquals("Portal", library.value.query)
        }
        runBlocking { assertEquals(listOf(10L), db.collectionDao().getMembers(collectionId).map { it.appId }) }
    }

    @Test fun emptyPickerCreationReturnsToTheSameLibraryVisit() {
        runBlocking { db.collectionDao().deleteAll() }
        val picker = CollectionPickerViewModel(memberships)
        val visit = LibraryVisitState()
        var creating by mutableStateOf(false)
        compose.setContent {
            BacklogiumTheme {
                if (creating) TextButton(onClick = { creating = false }) { Text("Back to Library") }
                else {
                    LibraryContent(library.value, visit = visit, actions = LibraryContentActions(onAddToCollection = picker::open))
                    val state by picker.state.collectAsState()
                    if (state.appId != null) CollectionPickerDialog(state, picker::add, picker::close,
                        onCreate = { picker.close(); creating = true })
                }
            }
        }
        compose.onNodeWithContentDescription("Actions for Portal").performScrollTo().performClick()
        compose.onNodeWithText("Add to collection").performClick()
        compose.onNodeWithText("No custom collections yet. Create one to group your games.").assertIsDisplayed()
        compose.onNodeWithText("New collection").performClick()
        compose.onNodeWithText("Back to Library").performClick()
        compose.onNodeWithContentDescription("Actions for Portal").assertIsDisplayed()
        assertEquals("Portal", library.value.query)
    }

    @Test fun heartCommitsOfflineAndWorksAtLargeFontScale() {
        val preferences = GamePreferenceRepository(db, identity)
        val controller = FavoriteActionController(scope, preferences::favorite, preferences::setFavorite)
        compose.runOnIdle { controller.show(10) }
        compose.setContent {
            BacklogiumTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    val state by controller.state.collectAsState()
                    Column {
                        GameFavoriteAction(state, controller::toggle)
                        DerivedCollectionCard("Favorites", "Games you marked with a heart.",
                            if (state.favorite?.isFavorite == true) 1 else 0, emptyList(), {})
                    }
                }
            }
        }
        compose.waitUntil { controller.state.value.favorite != null }
        compose.onNodeWithText("Add to favorites").performClick()
        compose.waitUntil { controller.state.value.favorite?.isFavorite == true }
        compose.onNodeWithText("Remove from favorites").assertIsDisplayed()
        capture("favorite-large-font")
        compose.onNodeWithText("Remove from favorites").performClick()
        compose.waitUntil { controller.state.value.favorite?.isFavorite == false && !controller.state.value.pending }
        runBlocking { assertFalse(db.gamePreferenceDao().getAll().single().isFavorite) }
    }

    @Test fun customRemovalKeepsDetailNavigationAndDerivedMembersHaveNoRemovalControl() {
        runBlocking { memberships.add(collectionId, 10, "fixture") }
        val density = mutableStateOf(GameListDensity.LIST)
        val derived = mutableStateOf(false)
        var opened: Long? = null
        var removed = false
        compose.setContent {
            BacklogiumTheme {
                val members by db.collectionDao().observeMembers(collectionId).collectAsState(initial = emptyList())
                val accent = MaterialTheme.colorScheme.primary
                LazyColumn(Modifier.fillMaxSize()) {
                    collectionMemberItems(members.map { CollectionMemberUi(it.appId, "Portal", "") }, density.value,
                        accent, true, { opened = it },
                        onRemoveMember = if (derived.value) null else { appId -> scope.launch {
                            memberships.remove(collectionId, appId, "fixture"); removed = true
                        }; Unit })
                }
            }
        }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Open Portal details").fetchSemanticsNodes().isNotEmpty() }
        for (value in GameListDensity.entries) {
            compose.runOnIdle { density.value = value }
            compose.onNodeWithContentDescription("Open Portal details").performClick()
            assertEquals(10L, opened)
            compose.onNodeWithContentDescription("Remove Portal from this collection").assertIsDisplayed()
            capture("custom-${value.name}")
        }
        compose.runOnIdle { derived.value = true }
        compose.onNodeWithContentDescription("Remove Portal from this collection").assertDoesNotExist()
        compose.runOnIdle { derived.value = false }
        compose.onNodeWithContentDescription("Remove Portal from this collection").performClick()
        compose.waitUntil { removed }
        compose.onNodeWithContentDescription("Open Portal details").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Dialog windows have Android animations outside Compose's test clock.
        android.os.SystemClock.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(compose.activity.getExternalFilesDir(null), "collection-shortcuts/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

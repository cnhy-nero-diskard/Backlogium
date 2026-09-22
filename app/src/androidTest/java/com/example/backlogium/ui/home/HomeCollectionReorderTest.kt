package com.example.backlogium.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSummary
import com.example.backlogium.domain.defaultSort
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeCollectionReorderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ordinaryCardTap_opensCollectionOutsideReorderMode() {
        var openedCollection: Long? = null

        composeRule.setContent {
            BacklogiumTheme {
                CollectionCard(
                    card = queueCard(7L),
                    onClick = { openedCollection = 7L },
                )
            }
        }

        composeRule.onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "7")
            .assertIsDisplayed()
            .performClick()

        assertEquals(7L, openedCollection)
    }

    @Test
    fun reorderEntryAndExit_areVisibleAndToggleTheMode() {
        var toggles = 0
        var reorderMode by mutableStateOf(false)

        composeRule.setContent {
            BacklogiumTheme {
                HomeCollectionsHeader(
                    onCreateCollection = {},
                    onOpenCollections = {},
                    onPlanGap = {},
                    reorderMode = reorderMode,
                    onToggleReorder = {
                        toggles++
                        reorderMode = !reorderMode
                    },
                )
            }
        }

        composeRule.onNodeWithText("Reorder").assertIsDisplayed().performClick()
        assertEquals(1, toggles)
        composeRule.onNodeWithText("Done").assertIsDisplayed()

        composeRule.onNodeWithText("Done").performClick()
        assertEquals(2, toggles)
    }

    @Test
    fun reorderMode_showsHandleAndOnlyAvailableMoveActions() {
        composeRule.setContent {
            BacklogiumTheme {
                CollectionCard(
                    card = queueCard(7L),
                    onClick = {},
                    reorderMode = true,
                    position = 1,
                    totalCount = 3,
                    onMoveUp = { true },
                    onMoveDown = { true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Drag to reorder Queue")
            .assertIsDisplayed()
        val actions = composeRule
            .onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "7")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]

        assertEquals(listOf("Move up", "Move down"), actions.map { it.label })
        assertEquals("Position 2 of 3", composeRule
            .onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "7")
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription])
    }

    @Test
    fun firstPosition_exposesNoMoveUpAction_andMoveActionCanPersist() {
        var movedDown = false

        composeRule.setContent {
            BacklogiumTheme {
                CollectionCard(
                    card = queueCard(7L),
                    onClick = {},
                    reorderMode = true,
                    position = 0,
                    totalCount = 2,
                    onMoveDown = {
                        movedDown = true
                        true
                    },
                )
            }
        }

        val action = composeRule
            .onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "7")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single()
        assertEquals("Move down", action.label)
        assertTrue(action.action())
        assertTrue(movedDown)
    }

    @Test
    fun movedOrder_isRetainedAfterLeavingAndReenteringHome() {
        var persistedOrder by mutableStateOf(listOf(7L, 8L))
        var homeVisible by mutableStateOf(true)

        composeRule.setContent {
            BacklogiumTheme {
                if (homeVisible) {
                    CollectionsSection(
                        cards = persistedOrder.map(::queueCard),
                        onOpenCollection = {},
                        onCreateCollection = {},
                        onOpenCollections = {},
                        onPlanGap = {},
                        scrollState = rememberScrollState(),
                        scrollViewport = null,
                        onReorderCollections = { persistedOrder = it },
                    )
                }
            }
        }

        val moveDown = composeRule
            .onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "7")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single()
        assertEquals("Move down", moveDown.label)
        assertTrue(moveDown.action())
        composeRule.waitForIdle()
        assertEquals(listOf(8L, 7L), persistedOrder)

        homeVisible = false
        composeRule.waitForIdle()
        homeVisible = true
        composeRule.waitForIdle()

        val firstCardTop = composeRule
            .onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "8")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val secondCardTop = composeRule
            .onNodeWithTag(HOME_COLLECTION_CARD_TAG_PREFIX + "7")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue(firstCardTop < secondCardTop)
    }

    private fun queueCard(id: Long) = HomeCollectionCard(
        collectionId = id,
        name = "Queue",
        mode = CollectionMode.ORDERED_QUEUE,
        accent = null,
        banner = CollectionSummary.derive(
            mode = CollectionMode.ORDERED_QUEUE,
            sort = CollectionMode.ORDERED_QUEUE.defaultSort(),
            targetDate = null,
            members = listOf(
                CollectionMemberSignals(
                    appId = 42L,
                    name = "Hades",
                    playtimeMinutes = 0,
                    completionistMinutes = 100,
                    achievementsUnlocked = null,
                    achievementsTotal = null,
                ),
            ),
            today = LocalDate.of(2026, 9, 22),
        ),
        games = listOf(HomeCollectionGame(42L, "Hades", null)),
    )
}

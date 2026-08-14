package com.example.backlogium.ui.collections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.ui.theme.BacklogiumTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test

class CollectionFormBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addingGame_keepsSearchFocusedAndOtherResultsVisible() {
        var state by mutableStateOf(formState())

        composeRule.setContent {
            BacklogiumTheme {
                CollectionFormContent(
                    state = state,
                    actions = CollectionFormActions(
                        onAddGame = { appId ->
                            val game = state.libraryGames.single { it.appId == appId }
                            state = state.copy(
                                members = state.members + CollectionMemberUi(
                                    appId = game.appId,
                                    name = game.name,
                                    iconUrl = game.iconUrl,
                                ),
                            )
                        },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("collection-add-search")
            .performScrollTo()
            .performClick()
            .performTextInput("a")
        composeRule.onNodeWithText("Alpha")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collection-add-search").assertIsFocused()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun saveAction_remainsVisibleWhenAFormFieldIsFocused() {
        composeRule.setContent {
            BacklogiumTheme {
                CollectionFormContent(
                    state = formState(),
                    actions = CollectionFormActions(),
                )
            }
        }

        composeRule.onNodeWithTag("collection-name")
            .performScrollTo()
            .performClick()
            .performTextInput(" updated")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collection-name").assertIsFocused()
        composeRule.onNodeWithTag("collection-save").assertIsDisplayed()
    }

    @Test
    fun addingGameWithoutSearchFocus_doesNotCrashWhenSearchIsOffscreen() {
        var state by mutableStateOf(
            formState(
                libraryGames = (1L..20L).map { appId ->
                    LibraryGame(
                        appId = appId,
                        name = "Game $appId",
                        iconUrl = "",
                        playtimeForever = 0,
                    )
                },
            ),
        )

        composeRule.setContent {
            BacklogiumTheme {
                CollectionFormContent(
                    state = state,
                    actions = CollectionFormActions(
                        onAddGame = { appId ->
                            val game = state.libraryGames.single { it.appId == appId }
                            state = state.copy(
                                members = state.members + CollectionMemberUi(
                                    appId = game.appId,
                                    name = game.name,
                                    iconUrl = game.iconUrl,
                                ),
                            )
                        },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("collection-form-list")
            .performScrollToNode(hasText("Game 20"))
        composeRule.onNodeWithText("Game 20").performClick()
        composeRule.waitForIdle()

        check(state.members.any { it.appId == 20L })
    }

    private fun formState(
        libraryGames: List<LibraryGame> = listOf(
            LibraryGame(appId = 1L, name = "Alpha", iconUrl = "", playtimeForever = 0),
            LibraryGame(appId = 2L, name = "Beta", iconUrl = "", playtimeForever = 0),
            LibraryGame(appId = 3L, name = "Gamma", iconUrl = "", playtimeForever = 0),
        ),
    ) = CollectionUiState(
        loading = false,
        name = "My collection",
        libraryGames = libraryGames,
    )
}

package com.example.backlogium.ui.updates

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.example.backlogium.data.updates.ReleaseNoteSection
import com.example.backlogium.data.updates.ReleaseNotesContract
import com.example.backlogium.data.updates.ReleaseNotesPresentation
import com.example.backlogium.data.updates.AvailableUpdate
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Rule
import org.junit.Test

class AppUpdateSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun structuredReleaseShowsNativeSectionsAndValidatedChangelogAction() {
        composeRule.setContent {
            BacklogiumTheme {
                AppUpdateSheet(
                    state = AppUpdateUiState(available = update(structuredNotes = structuredNotes())),
                    onUpdate = {},
                    onLater = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("app-update-product-heading").assertIsDisplayed()
        composeRule.onNodeWithText("Features").assertIsDisplayed()
        composeRule.onNodeWithText("A readable update.").assertIsDisplayed()
        composeRule.onNodeWithText("View full changelog").assertIsDisplayed()
        composeRule.onNodeWithText("raw technical detail").assertDoesNotExist()
    }

    @Test
    fun maintenanceReleaseShowsAnHonestNoChangesMessage() {
        composeRule.setContent {
            BacklogiumTheme {
                AppUpdateSheet(
                    state = AppUpdateUiState(available = update(structuredNotes = null, releaseNotes = "")),
                    onUpdate = {},
                    onLater = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("app-update-maintenance-message").assertIsDisplayed()
        composeRule.onNodeWithText("No release notes were provided.").assertDoesNotExist()
    }

    @Test
    fun legacyBodyIsSanitizedAndLongStructuredContentRemainsScrollable() {
        composeRule.setContent {
            BacklogiumTheme {
                AppUpdateSheet(
                    state = AppUpdateUiState(
                        available = update(
                            releaseNotes = "## Changed\n* fix: Keep offline progress by @user\nhttps://example.test",
                        ),
                    ),
                    onUpdate = {},
                    onLater = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Keep offline progress").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.test").assertDoesNotExist()

        composeRule.setContent {
            BacklogiumTheme {
                AppUpdateSheet(
                    state = AppUpdateUiState(available = update(structuredNotes = longNotes())),
                    onUpdate = {},
                    onLater = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("app-update-sheet-content")
            .performScrollToNode(hasText("Last item", substring = true))
        composeRule.onNodeWithText("Last item 12").assertIsDisplayed()
    }

    @Test
    fun downloadingAndFailureStatesKeepTheirExistingControls() {
        composeRule.setContent {
            BacklogiumTheme {
                AppUpdateSheet(
                    state = AppUpdateUiState(
                        available = update(structuredNotes = structuredNotes()),
                        operation = UpdateOperation.Downloading(10L, 100L),
                    ),
                    onUpdate = {},
                    onLater = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading update", substring = true).assertIsDisplayed()

        composeRule.setContent {
            BacklogiumTheme {
                AppUpdateSheet(
                    state = AppUpdateUiState(
                        available = update(structuredNotes = structuredNotes()),
                        operation = UpdateOperation.Failed("The update failed."),
                    ),
                    onUpdate = {},
                    onLater = {},
                    onCancel = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("The update failed.").assertIsDisplayed()
        composeRule.onNodeWithText("Update").assertIsDisplayed()
    }

    private fun update(
        structuredNotes: ReleaseNotesPresentation? = structuredNotes(),
        releaseNotes: String = "Legacy fallback",
    ) = AvailableUpdate(
        tag = "v1.8.0",
        versionName = "1.8.0",
        versionCode = 1_008_000L,
        releaseName = "Backlogium 1.8.0",
        releaseNotes = releaseNotes,
        apkName = "Backlogium-1.8.0.apk",
        apkUrl = "https://example.test/app.apk",
        checksumUrl = "https://example.test/app.sha256",
        structuredNotes = structuredNotes,
    )

    private fun structuredNotes() = ReleaseNotesPresentation(
        schemaVersion = ReleaseNotesContract.SCHEMA_VERSION,
        tag = "v1.8.0",
        sections = listOf(
            ReleaseNoteSection("features", "Features", listOf("A readable update.")),
            ReleaseNoteSection("maintenance", "Maintenance", emptyList()),
        ),
        fullChangelogUrl = "https://github.com/cnhy-nero-diskard/Backlogium/compare/v1.7.0...v1.8.0",
    )

    private fun longNotes() = structuredNotes().copy(
        sections = listOf(
            ReleaseNoteSection(
                "features",
                "Features",
                (1..12).map { "Last item $it" },
            ),
        ),
    )
}

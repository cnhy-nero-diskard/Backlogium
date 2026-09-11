package com.example.backlogium.ui.updates

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
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
    fun legacyBodyIsSanitized() {
        // `structuredNotes = null` is what actually selects the legacy path: structured sections
        // win whenever they are present, so leaving the helper's default in place rendered
        // "A readable update." and this test never exercised sanitization at all.
        setSheet(
            AppUpdateUiState(
                available = update(
                    structuredNotes = null,
                    releaseNotes = "## Changed\n* fix: Keep offline progress by @user\nhttps://example.test",
                ),
            ),
        )

        scrollTo(hasText("Keep offline progress", substring = true))
        composeRule.onNodeWithText("Keep offline progress").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.test").assertDoesNotExist()
    }

    /**
     * Long release notes are rendered in full rather than truncated.
     *
     * Presence, not visibility, is what this can honestly assert. `ModalBottomSheet` opens
     * partially expanded and measures its content column at the sheet's full potential height, so
     * with twelve items the column runs roughly a thousand pixels past the bottom of the screen
     * while reporting a scroll range of zero — there is nothing for an inner scroll to move, and
     * the remaining items are reached by dragging the sheet up instead.
     *
     * Asserting `isDisplayed` on the last item would therefore be asserting the sheet's expansion
     * state and the height of the test device, neither of which is what this test is about. The
     * real risk with a long changelog is items being dropped or cut off, and that is what is
     * checked here.
     */
    @Test
    fun longStructuredContentIsRenderedInFull() {
        setSheet(AppUpdateUiState(available = update(structuredNotes = longNotes())))

        (1..12).forEach { index ->
            composeRule.onNodeWithText("Last item $index").assertExists()
        }
        // The container is a scrollable one, so the content that overflows is reachable rather
        // than clipped away.
        composeRule.onNodeWithTag(SHEET_CONTENT).assertExists()
    }

    @Test
    fun downloadingStateKeepsItsCancelControl() {
        setSheet(
            AppUpdateUiState(
                available = update(structuredNotes = structuredNotes()),
                operation = UpdateOperation.Downloading(10L, 100L),
            ),
        )

        scrollTo(hasText("Downloading update", substring = true))
        composeRule.onNodeWithText("Downloading update", substring = true).assertIsDisplayed()
        scrollTo(hasText("Cancel"))
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun failureStateSurfacesTheErrorAndKeepsTheUpdateAction() {
        setSheet(
            AppUpdateUiState(
                available = update(structuredNotes = structuredNotes()),
                operation = UpdateOperation.Failed("The update failed."),
            ),
        )

        scrollTo(hasText("The update failed."))
        composeRule.onNodeWithText("The update failed.").assertIsDisplayed()
        scrollTo(hasText("Update"))
        composeRule.onNodeWithText("Update").assertIsDisplayed()
    }

    /**
     * One state per test.
     *
     * `createComposeRule` permits a single `setContent` per test, so a test that rendered two
     * states in sequence threw `Cannot call setContent twice per test!` before reaching its second
     * set of assertions — which also meant those assertions had never actually run.
     */
    private fun setSheet(state: AppUpdateUiState) = composeRule.setContent {
        BacklogiumTheme {
            AppUpdateSheet(
                state = state,
                onUpdate = {},
                onLater = {},
                onCancel = {},
                onDismiss = {},
            )
        }
    }

    /**
     * The whole sheet — notes, progress, and the action row alike — sits in one vertically
     * scrolling column, so on a short enough screen any of it can be present but off view.
     * `assertIsDisplayed` distinguishes those two, so reaching the node first is what makes these
     * assertions about content rather than about the height of the test device.
     */
    private fun scrollTo(matcher: SemanticsMatcher) =
        composeRule.onNodeWithTag(SHEET_CONTENT).performScrollToNode(matcher)

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

/** The sheet’s scrolling container, shared by every assertion that has to reach into it. */
private const val SHEET_CONTENT = "app-update-sheet-content"

package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.domain.SmartCollectionId

/** Home route-level presentation for durable progress events. */
@Composable
fun HomeRoute(
    onAccentColorChanged: (Color?) -> Unit = {},
    onOpenCollection: (Long) -> Unit = {},
    onCreateCollection: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenSmartCollection: (SmartCollectionId) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            onAccentColorChanged = onAccentColorChanged,
            onOpenCollection = onOpenCollection,
            onCreateCollection = onCreateCollection,
            onOpenCollections = onOpenCollections,
            onOpenSmartCollection = onOpenSmartCollection,
            viewModel = viewModel,
        )

        val broken = state.pendingStreakBreak
        if (broken != null && broken.previousLength > 0) {
            StreakBrokenOverlay(
                previousLength = broken.previousLength,
                onDismiss = { viewModel.acknowledgeProgressEvent(broken) },
            )
        }

        // Only when no streak break is being acknowledged. Both are top-anchored non-modal cards,
        // and two stacked in the same place would overlap into something unreadable — an earned
        // streak break is the more consequential of the two, so it holds the slot.
        val acquired = state.acquiredGames
        val shared = state.sharedGameAnnouncement
        if (broken == null && shared != null) {
            SharedGameAnnouncementBanner(
                announcement = shared,
                onViewLibrary = {
                    viewModel.dismissSharedGameAnnouncement()
                    onOpenLibrary()
                },
                onDismiss = viewModel::dismissSharedGameAnnouncement,
            )
        } else if (broken == null && acquired != null) {
            AcquiredGamesBanner(
                acquired = acquired,
                onViewLibrary = {
                    // Acting on the announcement retires it. Coming back to Home and being told
                    // again about games you just went and looked at reads as a bug, and having
                    // done the thing is a stronger acknowledgement than declining to.
                    viewModel.dismissAcquiredGames()
                    onOpenLibrary()
                },
                onDismiss = viewModel::dismissAcquiredGames,
            )
        }
    }
}

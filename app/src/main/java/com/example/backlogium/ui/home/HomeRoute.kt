package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Home route-level presentation for durable progress events. */
@Composable
fun HomeRoute(
    onAccentColorChanged: (Color?) -> Unit = {},
    onOpenCollection: (Long) -> Unit = {},
    onCreateCollection: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        HomeScreen(
            onAccentColorChanged = onAccentColorChanged,
            onOpenCollection = onOpenCollection,
            onCreateCollection = onCreateCollection,
            viewModel = viewModel,
        )

        val broken = state.pendingStreakBreak
        if (broken != null && broken.previousLength > 0) {
            StreakBrokenOverlay(
                previousLength = broken.previousLength,
                onDismiss = { viewModel.acknowledgeProgressEvent(broken) },
            )
        }
    }
}

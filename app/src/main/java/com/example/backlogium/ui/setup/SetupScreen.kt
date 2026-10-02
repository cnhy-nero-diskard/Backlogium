package com.example.backlogium.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.backlogium.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Settings route into setup: the same checklist the onboarding step presents, built from the
 * same registry, with each stage's last recorded outcome and nothing selected by default.
 *
 * Skipping setup during onboarding is a legitimate choice, and making it unrecoverable except by
 * clearing credentials would turn a reasonable "not now" into a trap. Verification is deliberately
 * absent: credentials that are already stored have been verified, and Settings has its own
 * credential-editing path.
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.prepare(applyDefaults = false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Run setup",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Pick the steps you want to run. Each one is the same job as its own control " +
                "elsewhere in the app, and each reports its own result.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SetupChecklist(
            state = state,
            onToggle = viewModel::toggle,
            onRetry = viewModel::retry,
            onReobserve = viewModel::reobserve,
            showRetry = true,
        )

        if (state.credentialsConfigured) {
            SetupActions(
                state = state,
                startLabel = stringResource(if (state.running) R.string.setup_working else R.string.setup_start_selected),
                onStart = viewModel::start,
                // End only the foreground wait. Admitted jobs and successful siblings survive.
                onSkip = if (state.running) viewModel::continueLater else null,
            )
        }

        if (state.finished) SetupSummary(state)
    }
}

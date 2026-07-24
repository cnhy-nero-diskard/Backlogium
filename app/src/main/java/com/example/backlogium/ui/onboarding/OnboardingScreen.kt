package com.example.backlogium.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.ExternalLink

/** The Steam Web API key page the user copies their key from. */
private const val STEAM_API_KEY_URL = "https://steamcommunity.com/dev/apikey"

/**
 * Full-screen onboarding: Step 1 captures the Steam Web API key (with a link to the Steam key
 * page); Step 2 captures the SteamID64 via a raw-ID / profile-URL toggle with inline resolution
 * feedback. On completion [onCompleted] is invoked so the host can dismiss the takeover / pop the
 * route. The API key is entered behind a password transformation and never displayed in clear.
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.completed) {
        if (state.completed) onCompleted()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Connect your Steam account",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Step ${if (state.step == OnboardingStep.API_KEY) 1 else 2} of 2",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        when (state.step) {
            OnboardingStep.API_KEY -> ApiKeyStep(state, viewModel)
            OnboardingStep.STEAM_ID -> SteamIdStep(state, viewModel)
        }
    }
}

@Composable
private fun ApiKeyStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val uriHandler = LocalUriHandler.current
    var showKey by remember { mutableStateOf(false) }

    Text(
        text = "Enter your Steam Web API key. It stays encrypted on this device and is only " +
            "used to read your own library and status.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = state.apiKey,
        onValueChange = viewModel::onApiKeyChange,
        label = { Text(if (state.hasExistingKey) "New API key (leave blank to keep current)" else "Steam Web API key") },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showKey = !showKey }) {
                Text(if (showKey) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    TextButton(onClick = { uriHandler.openUri(STEAM_API_KEY_URL) }) {
        Icon(TablerIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Where do I get a key?")
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = viewModel::advanceToSteamId,
        enabled = state.canAdvanceFromApiKey,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
}

@Composable
private fun SteamIdStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text(
        text = "Now your SteamID. Paste the raw 17-digit ID, or switch to profile URL and paste " +
            "your Steam profile link.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.entryMode == SteamIdEntryMode.RAW_ID,
            onClick = { viewModel.setEntryMode(SteamIdEntryMode.RAW_ID) },
            label = { Text("SteamID64") },
        )
        FilterChip(
            selected = state.entryMode == SteamIdEntryMode.PROFILE_URL,
            onClick = { viewModel.setEntryMode(SteamIdEntryMode.PROFILE_URL) },
            label = { Text("Profile URL") },
        )
    }

    val isRaw = state.entryMode == SteamIdEntryMode.RAW_ID
    OutlinedTextField(
        value = state.steamIdInput,
        onValueChange = viewModel::onSteamIdInputChange,
        label = { Text(if (isRaw) "SteamID64 (17 digits)" else "Steam profile URL") },
        placeholder = {
            Text(if (isRaw) "7656119…" else "https://steamcommunity.com/id/yourname")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isRaw) KeyboardType.Number else KeyboardType.Uri,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    ResolveFeedback(state.resolve)

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = viewModel::backToApiKey) { Text("Back") }
        Spacer(Modifier.width(0.dp))
        if (state.isResolved) {
            Button(
                onClick = viewModel::finish,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Finish")
                }
            }
        } else {
            Button(
                onClick = viewModel::resolveSteamId,
                enabled = state.steamIdInput.isNotBlank() && state.resolve != ResolveState.Resolving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.resolve == ResolveState.Resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (isRaw) "Verify" else "Resolve")
                }
            }
        }
    }
}

@Composable
private fun ResolveFeedback(resolve: ResolveState) {
    when (resolve) {
        ResolveState.Idle, ResolveState.Resolving -> Unit
        is ResolveState.Resolved -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                TablerIcons.CircleCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "SteamID ${resolve.steamId64}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is ResolveState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                TablerIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = resolve.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

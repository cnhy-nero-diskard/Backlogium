package com.example.backlogium.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
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
import com.example.backlogium.ui.setup.SetupActions
import com.example.backlogium.ui.setup.SetupChecklist
import com.example.backlogium.ui.setup.SetupSummary
import com.example.backlogium.ui.setup.SetupViewModel
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
    val identityExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportIdentityChange) }

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
            text = if (state.step == OnboardingStep.SETUP) {
                "Set up your library"
            } else {
                "Connect your Steam account"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        // Derived from the flow rather than a hardcoded total, so adding a credential step cannot
        // leave the count lying about how many there are.
        state.step.credentialStepNumber?.let { number ->
            Text(
                text = "Step $number of ${OnboardingStep.credentialStepCount}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when (state.step) {
            OnboardingStep.API_KEY -> ApiKeyStep(state, viewModel)
            // Verification has no surface of its own: it is the pending state of the SteamID step's
            // final action, using that step's existing inline treatment.
            OnboardingStep.STEAM_ID, OnboardingStep.VERIFY -> SteamIdStep(state, viewModel)
            OnboardingStep.SETUP -> SetupStep(onDone = viewModel::onSetupDone)
        }
    }

    state.identityChange?.let { identityChange ->
        IdentityChangeDialog(
            state = identityChange,
            applying = state.saving,
            onExport = {
                identityExportLauncher.launch(
                    "backlogium-account-${identityChange.storedSteamId}-${System.currentTimeMillis()}.json",
                )
            },
            onConfirm = viewModel::confirmIdentityChange,
            onDismiss = viewModel::declineIdentityChange,
        )
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

    // A key Steam rejected sends the user back here, and the message belongs beside the field it is
    // about — hence the step check, not just the state check.
    (state.verify as? VerifyState.Rejected)
        ?.takeIf { it.step == OnboardingStep.API_KEY }
        ?.let { rejected -> VerifyMessage(rejected.message) }

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
    VerifyFeedback(state.verify, onRetry = viewModel::retryVerification)

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
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
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

/**
 * Verification's inline feedback on the SteamID step.
 *
 * The pending state is deliberately absent here: it is already the Finish button's spinner, and a
 * second progress mechanism for the same wait would be two things to keep in agreement.
 */
@Composable
private fun VerifyFeedback(verify: VerifyState, onRetry: () -> Unit) {
    when (verify) {
        VerifyState.Idle, VerifyState.Verifying -> Unit
        // Only the rejection this step's own value caused. A key rejection is rendered by the key
        // step; repeating it here would point the user at the value Steam did not object to.
        is VerifyState.Rejected ->
            if (verify.step == OnboardingStep.STEAM_ID) VerifyMessage(verify.message) else Unit
        // A failure to reach Steam is not a verdict on the credentials, so it gets a retry rather
        // than an error that implies something needs correcting.
        VerifyState.Unreachable -> Column {
            VerifyMessage("Couldn't reach Steam. Check your connection.")
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun VerifyMessage(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            TablerIcons.AlertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * The staged setup checklist, as the last step of a first run.
 *
 * The same composable the Settings entry uses, with each stage's declared default applied — so a
 * stage registered later appears here without this file being touched. Entry into the app is offered
 * as soon as every in-screen stage has settled; detached stages keep going in their own
 * notifications, and "Skip setup" is available throughout so the step can never become a trap.
 */
@Composable
private fun SetupStep(onDone: () -> Unit) {
    val viewModel: SetupViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.prepare(applyDefaults = true) }

    Text(
        text = "Your account is connected. These steps fill the app with your library — pick the " +
            "ones you want now, and run the rest later from Settings.",
        style = MaterialTheme.typography.bodyMedium,
    )

    SetupChecklist(
        state = state,
        onToggle = viewModel::toggle,
        onRetry = viewModel::retry,
        // Nothing has an outcome worth retrying until this run has finished, and the summary below
        // plus the Settings entry are where a retry belongs.
        showRetry = false,
    )

    if (state.finished || state.running) SetupSummary(state)

    Spacer(Modifier.height(8.dp))
    val started = state.running || state.finished
    if (started && state.inScreenSettled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.detachedStillRunning) "Continue to the app" else "Done")
            }
        }
    } else {
        SetupActions(
            state = state,
            startLabel = if (state.running) "Setting up…" else "Start setup",
            onStart = viewModel::start,
            // Offered before the run and during it, so the step can never hold anyone. Before
            // anything has started it records every stage skipped, which is then the truth. Once a
            // run is under way it only leaves — relabelling a stage that is actually running as
            // "skipped" would put a false outcome in front of the Settings list that reads them.
            onSkip = {
                if (!started) viewModel.skip()
                onDone()
            },
        )
    }
}

/** Names the destructive consequence and keeps the complete export action beside confirmation. */
@Composable
private fun IdentityChangeDialog(
    state: IdentityChangeUiState,
    applying: Boolean,
    onExport: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!applying && !state.exporting) onDismiss() },
        title = { Text("Switch Steam account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your current account is ${state.storedSteamId}, and the new account is " +
                        "${state.incomingSteamId}.",
                )
                Text(
                    "Switching will discard the current library, sessions, daily progress, " +
                        "achievements, collections, sync history, and XP/streak profile.",
                )
                Text(
                    "HowLongToBeat data, rules, display preferences, and automatic snapshots " +
                        "will be kept.",
                )
                state.exportMessage?.let { message ->
                    Text(
                        text = message,
                        color = if (message.startsWith("Backup export failed")) {
                            androidx.compose.material3.MaterialTheme.colorScheme.error
                        } else {
                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = onExport,
                    enabled = !applying && !state.exporting,
                ) {
                    if (state.exporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (state.exportMessage == null) "Export backup first" else "Export again")
                    }
                }
                Button(onClick = onConfirm, enabled = !applying && !state.exporting) {
                    Text("Discard and switch")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !applying && !state.exporting) {
                Text("Cancel")
            }
        },
    )
}

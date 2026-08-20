package com.example.backlogium.ui.updates

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.updates.releaseNoteSections
import com.example.backlogium.data.updates.validatedFullChangelogUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateSheet(
    state: AppUpdateUiState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val update = state.available ?: return
    val context = LocalContext.current
    val sections = update.releaseNoteSections()
    val fullChangelogUrl = update.validatedFullChangelogUrl()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("app-update-sheet-content")
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Backlogium",
                modifier = Modifier.testTag("app-update-product-heading"),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text("Update available", style = MaterialTheme.typography.titleLarge)
            Text(
                "${BuildConfig.VERSION_NAME} → ${update.versionName}",
                modifier = Modifier.testTag("app-update-version-transition"),
                style = MaterialTheme.typography.titleMedium,
            )
            if (sections.isEmpty()) {
                Text(
                    "This is a maintenance update with no user-visible changes.",
                    modifier = Modifier.testTag("app-update-maintenance-message"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                sections.forEach { section ->
                    Text(
                        section.title,
                        modifier = Modifier
                            .testTag("app-update-section-${section.key}")
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    section.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("app-update-item-${section.key}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Text(item, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (fullChangelogUrl != null) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(fullChangelogUrl)),
                            )
                        }
                    },
                    modifier = Modifier.testTag("app-update-full-changelog"),
                ) {
                    Text("View full changelog")
                }
            }

            when (val operation = state.operation) {
                is UpdateOperation.Downloading -> {
                    if (operation.totalBytes != null && operation.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = {
                                (operation.bytesRead.toFloat() / operation.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text("Downloading update…", style = MaterialTheme.typography.bodySmall)
                }
                UpdateOperation.VerifyingDigest -> Text("Verifying download…")
                UpdateOperation.VerifyingSigner -> Text("Verifying release signature…")
                UpdateOperation.Installing -> Text("Opening Android installer…")
                UpdateOperation.PermissionRequired -> Text(
                    "Allow Backlogium to install updates in Android Settings, then choose Update again.",
                )
                is UpdateOperation.Failed -> Text(
                    operation.message,
                    color = MaterialTheme.colorScheme.error,
                )
                UpdateOperation.Idle -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.operation) {
                    is UpdateOperation.Downloading,
                    UpdateOperation.VerifyingDigest,
                    UpdateOperation.VerifyingSigner,
                    -> TextButton(onClick = onCancel) { Text("Cancel") }
                    else -> TextButton(onClick = onLater) { Text("Later") }
                }
                Spacer(Modifier.weight(1f))
                when (state.operation) {
                    UpdateOperation.Idle,
                    is UpdateOperation.Failed,
                    UpdateOperation.PermissionRequired,
                    -> Button(onClick = onUpdate) { Text("Update") }
                    UpdateOperation.Installing -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    else -> Unit
                }
            }
        }
    }
}

package com.example.backlogium.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.work.SteamAssetDownloadStatus

@Composable
internal fun OfflineSteamAssetsCard(
    state: SettingsUiState,
    onStart: (SteamAssetDownloadMode) -> Unit,
    onCancel: () -> Unit,
) {
    var choosingMode by remember { mutableStateOf(false) }
    val active = state.steamAssetStatus in setOf(
        SteamAssetDownloadStatus.QUEUED,
        SteamAssetDownloadStatus.PREPARING,
        SteamAssetDownloadStatus.RUNNING,
    )
    Card(modifier = Modifier.fillMaxWidth().testTag("offline-steam-assets")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline Steam assets", style = MaterialTheme.typography.titleMedium)
            Text(
                "Store the Steam images already known to your library for offline viewing. This never starts a Steam sync.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${state.storedSteamAssetCount} stored • ${state.storedSteamAssetBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.lastSteamAssetRun?.let { run ->
                Text(
                    "Last run: ${run.storedCount} downloaded, ${run.alreadyPresentCount} already present, ${run.unavailableCount} unavailable, ${run.failedCount} failed",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (active) {
                Text(
                    when (state.steamAssetStatus) {
                        SteamAssetDownloadStatus.QUEUED -> "Queued for network and available storage"
                        SteamAssetDownloadStatus.PREPARING -> "Preparing image inventory"
                        else -> "Downloading Steam assets"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.steamAssetProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.processed.toFloat() / progress.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.processed} / ${progress.total} • ${progress.stored} downloaded, ${progress.unavailable} unavailable, ${progress.failed} failed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onCancel) { Text("Stop download") }
            } else {
                if (!state.hasSteamAssetInventory) {
                    Text("Sync a Steam library first to discover images.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { choosingMode = true },
                    enabled = state.hasSteamAssetInventory,
                ) { Text("Download Steam assets") }
            }
        }
    }
    if (choosingMode) {
        AlertDialog(
            onDismissRequest = { choosingMode = false },
            title = { Text("Download Steam assets") },
            text = { Text("Choose whether to keep valid files or re-download every known Steam image.") },
            confirmButton = {
                TextButton(onClick = { choosingMode = false; onStart(SteamAssetDownloadMode.DOWNLOAD_MISSING) }) {
                    Text("Download missing assets")
                }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { choosingMode = false; onStart(SteamAssetDownloadMode.REFRESH_ALL) }) {
                        Text("Refresh all assets")
                    }
                    Spacer(Modifier.height(2.dp))
                    TextButton(onClick = { choosingMode = false }) { Text("Cancel") }
                }
            },
        )
    }
}

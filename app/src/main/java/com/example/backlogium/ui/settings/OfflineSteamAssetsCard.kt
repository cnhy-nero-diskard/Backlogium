package com.example.backlogium.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.backlogium.R
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
            Text(stringResource(R.string.settings_assets_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_assets_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                pluralStringResource(
                    R.plurals.settings_assets_stored,
                    state.storedSteamAssetCount,
                    state.storedSteamAssetCount,
                    state.storedSteamAssetBytes / (1024 * 1024),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.lastSteamAssetRun?.let { run ->
                Text(
                    stringResource(
                        R.string.settings_assets_last_run,
                        run.storedCount,
                        run.alreadyPresentCount,
                        run.unavailableCount,
                        run.failedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (active) {
                Text(
                    when (state.steamAssetStatus) {
                        SteamAssetDownloadStatus.QUEUED -> stringResource(R.string.settings_assets_queued)
                        SteamAssetDownloadStatus.PREPARING -> stringResource(R.string.settings_assets_preparing)
                        else -> stringResource(R.string.settings_assets_downloading)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.steamAssetProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.processed.toFloat() / progress.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.settings_assets_progress,
                            progress.processed,
                            progress.total,
                            progress.stored,
                            progress.unavailable,
                            progress.failed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_assets_stop))
                }
            } else {
                if (!state.hasSteamAssetInventory) {
                    Text(
                        stringResource(R.string.settings_assets_need_sync),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = { choosingMode = true },
                    enabled = state.hasSteamAssetInventory,
                ) { Text(stringResource(R.string.settings_assets_download)) }
            }
        }
    }
    if (choosingMode) {
        AlertDialog(
            onDismissRequest = { choosingMode = false },
            title = { Text(stringResource(R.string.settings_assets_download)) },
            text = { Text(stringResource(R.string.settings_assets_choose_mode)) },
            confirmButton = {
                TextButton(onClick = { choosingMode = false; onStart(SteamAssetDownloadMode.DOWNLOAD_MISSING) }) {
                    Text(stringResource(R.string.settings_assets_missing))
                }
            },
            dismissButton = {
                TextButton(onClick = { choosingMode = false; onStart(SteamAssetDownloadMode.REFRESH_ALL) }) {
                    Text(stringResource(R.string.settings_assets_refresh))
                }
            },
        )
    }
}

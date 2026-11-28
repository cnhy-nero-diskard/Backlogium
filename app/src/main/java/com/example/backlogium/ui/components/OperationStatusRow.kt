package com.example.backlogium.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.backlogium.R
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus

@Composable
internal fun OperationStatusRow(title: String, operation: OperationProgress, modifier: Modifier = Modifier) {
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val status = stringResource(when (operation.status) {
            OperationStatus.QUEUED -> R.string.operation_queued
            OperationStatus.WAITING -> R.string.operation_waiting
            OperationStatus.RUNNING -> when (operation.phase) {
                OperationPhase.FETCHING_LIBRARY -> R.string.operation_fetching_library
                OperationPhase.ACHIEVEMENTS -> R.string.operation_fetching_achievements
                OperationPhase.SAVING -> R.string.operation_saving
                else -> R.string.operation_preparing
            }
            OperationStatus.RETRYING -> R.string.operation_retrying
            OperationStatus.CANCELLED -> R.string.operation_cancelled
            OperationStatus.SUCCEEDED -> R.string.operation_succeeded
            OperationStatus.PARTIAL -> R.string.operation_partial
            OperationStatus.FAILED -> R.string.operation_failed
        })
        Text(stringResource(R.string.operation_status, title, status), style = MaterialTheme.typography.bodySmall)
        val counts = operation.counts
        counts?.let {
            Text(stringResource(R.string.operation_counts, it.processed, it.total, it.skipped, it.failed),
                style = MaterialTheme.typography.bodySmall)
        }
        operation.updated?.let {
            Text(stringResource(R.string.operation_updated, it), style = MaterialTheme.typography.bodySmall)
        }
        if (operation.active) {
            if (operation.status == OperationStatus.RUNNING && counts != null && counts.total > 0 && operation.phase == OperationPhase.ACHIEVEMENTS) {
                LinearProgressIndicator(progress = { (counts.processed.toFloat() / counts.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

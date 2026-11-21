package com.example.backlogium.ui.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.backlogium.R
import com.example.backlogium.domain.*
import com.example.backlogium.ui.util.UiFormat
import java.text.NumberFormat

@Composable
internal fun PersonalMomentumCard(state: MomentumUiState, onOpenGame: (Long) -> Unit) {
    val result = state.read.result
    var explain by rememberSaveable(state.read.key?.accountId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("personal-momentum")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.momentum_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.momentum_scope), style = MaterialTheme.typography.bodySmall)
            if (state.read.updating || result == null) {
                Text(stringResource(R.string.momentum_updating), modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                })
            }
            if (result != null) {
                val weeks = result.weeks
                Text(stringResource(R.string.momentum_current_dates, UiFormat.date(weeks.currentStart),
                    UiFormat.date(weeks.currentEnd)), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("momentum-current-dates"))
                Text(stringResource(R.string.momentum_baseline_dates, UiFormat.date(weeks.baselineStart),
                    UiFormat.date(weeks.baselineEnd)), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("momentum-baseline-dates"))
                when (result.availability) {
                    MomentumAvailability.LEARNING -> Text(stringResource(R.string.momentum_learning))
                    MomentumAvailability.EMPTY_LIBRARY -> Text(stringResource(R.string.momentum_empty))
                    MomentumAvailability.NO_INCREASE -> Text(stringResource(R.string.momentum_no_increase))
                    MomentumAvailability.CANDIDATES -> {
                        for (kind in MomentumKind.entries) {
                            val rows = result.candidates.filter { it.kind == kind }
                            if (rows.isNotEmpty()) {
                                HorizontalDivider()
                                Text(stringResource(if (kind == MomentumKind.GROWTH) R.string.momentum_growth_group
                                    else R.string.momentum_new_group), fontWeight = FontWeight.Bold)
                                rows.forEach { row -> MomentumRow(row, onOpenGame) }
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.momentum_caveat), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.detailUnavailable) Text(stringResource(R.string.momentum_unavailable),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { explain = true }, modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.momentum_explain))
            }
        }
    }
    if (explain) AlertDialog(onDismissRequest = { explain = false },
        title = { Text(stringResource(R.string.momentum_explain)) },
        text = { Text(stringResource(R.string.momentum_criteria),
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { explain = false }) {
            Text(stringResource(android.R.string.ok))
        } })
}

@Composable
private fun MomentumRow(row: MomentumCandidate, onOpenGame: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("momentum-game-${row.game.appId}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onOpenGame(row.game.appId) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("momentum-open-${row.game.appId}"),
            shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.momentum_open_game, row.game.name), fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.momentum_recorded_amounts, UiFormat.localizedMinutes(row.currentMinutes),
            UiFormat.localizedMinutes(row.baselineMinutes)), style = MaterialTheme.typography.bodyMedium)
        val reason = if (row.kind == MomentumKind.GROWTH) {
            val percentage = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }
                .format(row.additionalMinutes.toDouble() / row.baselineMinutes)
            stringResource(R.string.momentum_growth_reason, UiFormat.localizedMinutes(row.additionalMinutes), percentage)
        } else stringResource(R.string.momentum_new_reason)
        Text(reason, style = MaterialTheme.typography.bodySmall)
    }
}

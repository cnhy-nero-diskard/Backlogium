package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.backlogium.R
import com.example.backlogium.domain.DailyActivity
import com.example.backlogium.domain.DailyActivityKey
import com.example.backlogium.ui.util.UiFormat
import kotlin.math.abs

@Composable
internal fun DailyActivityDisclosure(
    key: DailyActivityKey?,
    activity: DailyActivity?,
    unavailable: Boolean,
    onOpenGame: (Long) -> Unit,
) {
    var expanded by rememberSaveable(key?.accountId, key?.date?.toString()) { mutableStateOf(false) }
    val expansionState = stringResource(if (expanded) R.string.activity_expanded else R.string.activity_collapsed)
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expansionState },
    ) { Text(stringResource(if (expanded) R.string.activity_hide_games else R.string.activity_show_games)) }
    if (expanded) {
        if (activity == null) Text(stringResource(R.string.activity_loading))
        else DailyActivityContent(activity, onOpenGame)
        if (unavailable) Text(stringResource(R.string.activity_detail_unavailable))
    }
}

@Composable
internal fun DailyActivityContent(activity: DailyActivity, onOpenGame: (Long) -> Unit) {
    var explanation by rememberSaveable(activity.accountId, activity.date.toString()) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(UiFormat.date(activity.date), style = MaterialTheme.typography.labelLarge)
        if (activity.updating) Text(stringResource(R.string.activity_updating))
        Text(stringResource(R.string.activity_recorded_total, UiFormat.count(activity.recordedMinutes)))
        Text(activity.creditedMinutes?.let { stringResource(R.string.activity_credited_total, UiFormat.count(it)) }
            ?: stringResource(R.string.activity_credit_unavailable))
        val difference = activity.differenceMinutes
        if (difference != null && difference != 0L) {
            Text(stringResource(
                if (difference > 0) R.string.activity_credit_difference else R.string.activity_recorded_difference,
                UiFormat.count(abs(difference)),
            ))
        }
        if (activity.games.isEmpty()) {
            Text(stringResource(if ((activity.creditedMinutes ?: 0) > 0)
                R.string.activity_allocation_unavailable else R.string.activity_no_records))
        }
        activity.games.forEach { game ->
            val name = game.name ?: stringResource(R.string.activity_app_fallback, UiFormat.count(game.appId))
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.activity_game_minutes, name, UiFormat.count(game.minutes)))
                if (game.detailAvailable) {
                    TextButton(onClick = { onOpenGame(game.appId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.activity_open_game, name))
                    }
                } else Text(stringResource(R.string.activity_detail_unavailable))
            }
        }
        TextButton(onClick = { explanation = true }) { Text(stringResource(R.string.activity_measurement_help)) }
    }
    if (explanation) AlertDialog(
        onDismissRequest = { explanation = false },
        title = { Text(stringResource(R.string.activity_measurement_help)) },
        text = { Text(stringResource(R.string.activity_measurement_explanation)) },
        confirmButton = { TextButton(onClick = { explanation = false }) { Text(stringResource(R.string.activity_close)) } },
    )
}

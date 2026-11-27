package com.example.backlogium.ui.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.backlogium.R
import com.example.backlogium.ui.util.UiFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun CorePlayInsights(state: AnalyticsUiState) {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun range(bounds: AnalyticsWindowBounds) = "${bounds.start.format(formatter)} – ${bounds.endInclusive.format(formatter)}"
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.analytics_core_insights), style = MaterialTheme.typography.titleSmall)
            state.comparison?.let {
                Text(stringResource(R.string.analytics_period_facts, range(it.currentBounds), UiFormat.count(it.current.minutes), it.current.activeDays, it.current.distinctGames))
                Text(stringResource(R.string.analytics_period_facts, range(it.previousBounds), UiFormat.count(it.previous.minutes), it.previous.activeDays, it.previous.distinctGames))
                it.minutesChangePercent?.let { change -> Text(stringResource(R.string.analytics_minutes_change, percent.format(change))) }
            }
            Text(stringResource(R.string.analytics_distinct_games, state.variety.distinctGames))
            fun names(ids: List<Long>) = ids.joinToString { id ->
                state.gamesByDate.values.flatten().firstOrNull { it.appId == id }?.name
                    ?: state.topGames.firstOrNull { it.appId == id }?.name ?: "App $id"
            }
            if (state.variety.firstRecorded.isNotEmpty()) Text(stringResource(R.string.analytics_first_recorded, names(state.variety.firstRecorded)))
            if (state.variety.returned.isNotEmpty()) Text(stringResource(R.string.analytics_returned, names(state.variety.returned)))
            state.playMix.forEach { share ->
                val name = share.appId?.let { id -> state.topGames.firstOrNull { it.appId == id }?.name ?: "App $id" }
                    ?: stringResource(R.string.analytics_remaining_games)
                Text(stringResource(R.string.analytics_play_share, name, UiFormat.count(share.minutes), percent.format(share.percent)))
            }
            val focus = state.focusShare
            Text(stringResource(R.string.analytics_focus_share, UiFormat.count(focus.minutes), UiFormat.count(focus.visibleMinutes)))
            val quest = state.activeQuestConsistency
            Text(stringResource(R.string.analytics_active_quest_consistency, quest.metDays, quest.availableDays, quest.unavailableDays))
            state.sessionInsights.medianMinutes?.let { median ->
                Text(stringResource(R.string.analytics_median_duration, percent.format(median)))
                val labels = listOf(R.string.analytics_session_under_30, R.string.analytics_session_30_59,
                    R.string.analytics_session_60_119, R.string.analytics_session_120_plus)
                labels.zip(state.sessionInsights.durationBuckets).forEach { (label, count) ->
                    Text(stringResource(label, count))
                }
            }
        }
    }
}

/** Calendar clicks and month navigation feed the existing inspected-date state. */
@Composable
internal fun AnalyticsActivityCalendar(days: List<AnalyticsDay>, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    if (days.isEmpty() || selected == null) return
    val month = YearMonth.from(selected)
    val first = days.first().date
    val last = days.last().date
    val byDate = days.associateBy { it.date }
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.analytics_activity_calendar), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth()) {
            TextButton(modifier = Modifier.fillMaxWidth(), enabled = month > YearMonth.from(first), onClick = { onSelect(maxOf(first, month.minusMonths(1).atDay(1))) }) {
                Text(stringResource(R.string.analytics_calendar_previous))
            }
            TextButton(modifier = Modifier.fillMaxWidth(), enabled = month < YearMonth.from(last), onClick = { onSelect(minOf(last, month.plusMonths(1).atDay(1))) }) {
                Text(stringResource(R.string.analytics_calendar_next))
            }
        }
        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy")))
        Row(Modifier.fillMaxWidth()) {
            java.time.DayOfWeek.entries.forEach { day ->
                Text(day.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                    }, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = List<LocalDate?>(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { index ->
                    val date = week.getOrNull(index)
                    val day = byDate[date]
                    if (day == null) Spacer(Modifier.weight(1f).height(48.dp))
                    else {
                        val description = stringResource(R.string.analytics_calendar_day, day.date.format(formatter), UiFormat.localizedMinutes(day.minutes))
                        TextButton(onClick = { onSelect(day.date) },
                            colors = ButtonDefaults.textButtonColors(containerColor = if (day.date == selected)
                                MaterialTheme.colorScheme.primaryContainer else Color.Transparent), contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                contentDescription = description
                                this.selected = day.date == selected
                            }) { Text(UiFormat.count(day.date.dayOfMonth)) }
                    }
                }
            }
        }
    }
}

/** Long trends are calendar weeks/months; their daily detail remains independently selectable. */
internal fun analyticsTrendDays(days: List<AnalyticsDay>, length: AnalyticsWindowLength): List<AnalyticsDay> {
    if (length != AnalyticsWindowLength.NINETY_DAYS && length != AnalyticsWindowLength.ONE_YEAR) return days
    return days.groupBy { day ->
        if (length == AnalyticsWindowLength.ONE_YEAR) day.date.withDayOfMonth(1)
        else day.date.minusDays((day.date.dayOfWeek.value - 1).toLong())
    }.map { (_, group) -> AnalyticsDay(group.first().date, group.sumOf { it.minutes }, group.sumOf { it.estimatedMinutes }) }
}

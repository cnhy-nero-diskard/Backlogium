package com.example.backlogium.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.R
import com.example.backlogium.ui.util.UiFormat
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle
import java.util.Locale

/** Exact text values accompany both visual distributions; each card retains its snapshot scope. */
@Composable
internal fun AdvancedPlayInsights(state: AnalyticsUiState) {
    val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    val unknown = stringResource(R.string.analytics_genre_unknown)
    val bounds = state.dailyMinutes.let { days ->
        days.minOfOrNull { it.date }?.let { AnalyticsWindowBounds(it, days.maxOf { day -> day.date }) }
    } ?: state.windowBounds
    val scope = stringResource(R.string.analytics_represented_dates, UiFormat.date(bounds.start), UiFormat.date(bounds.endInclusive))
    fun name(id: Long) = state.gameNames[id] ?: state.topGames.firstOrNull { it.appId == id }?.name ?: "App $id"
    fun date(at: Long) = UiFormat.date(Instant.ofEpochMilli(at).atZone(state.insightsZone).toLocalDate())

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.analytics_genre_title), style = MaterialTheme.typography.titleSmall)
            Text(scope, Modifier.testTag("analytics-advanced-genre-scope"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.analytics_genre_basis))
            val mix = state.genreMix
            Text(stringResource(R.string.analytics_genre_coverage, mix.coveredGames, UiFormat.count(mix.knownMinutes), UiFormat.count(mix.totalMinutes)))
            if (mix.shares.isEmpty()) Text(stringResource(R.string.analytics_genre_empty))
            else {
                val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)
                Row(Modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics { }) {
                    mix.shares.forEachIndexed { index, share ->
                        Box(Modifier.weight(share.percent.toFloat().coerceAtLeast(0.0001f)).fillMaxHeight().background(colors[index % colors.size]))
                    }
                }
                mix.shares.forEach { share ->
                    Text(stringResource(R.string.analytics_genre_share, share.genre?.label ?: unknown, number.format(share.minutes), number.format(share.percent)))
                }
            }
            if (state.genreDrift.isEmpty()) Text(stringResource(R.string.analytics_genre_drift_missing))
            else {
                state.comparison?.let { Text(stringResource(R.string.analytics_advanced_comparison_dates,
                    UiFormat.date(it.previousBounds.start), UiFormat.date(it.previousBounds.endInclusive),
                    UiFormat.date(it.currentBounds.start), UiFormat.date(it.currentBounds.endInclusive))) }
                state.genreDrift.forEach { drift ->
                    Text(stringResource(R.string.analytics_genre_drift, drift.genre?.label ?: unknown, number.format(drift.percentagePoints)))
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.analytics_clock_title), style = MaterialTheme.typography.titleSmall)
            Text(scope, Modifier.testTag("analytics-advanced-clock-scope"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.analytics_clock_basis, state.insightsZone.id))
            Text(stringResource(R.string.analytics_clock_coverage, UiFormat.count(state.clockMatrix.cells.sumOf { it.minutes }), state.clockMatrix.activeDates))
            val labels = listOf(R.string.analytics_morning, R.string.analytics_afternoon, R.string.analytics_evening, R.string.analytics_night).map { stringResource(it) }
            Text(stringResource(R.string.analytics_clock_hours))
            if (state.clockMatrix.cells.isEmpty()) Text(stringResource(R.string.analytics_clock_empty))
            else {
                val cells = state.clockMatrix.cells.associateBy { it.weekday to it.bucket }
                val max = state.clockMatrix.cells.maxOf { it.minutes }.toFloat()
                DayOfWeek.entries.forEach { day ->
                    Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TimeOfDayBucket.entries.forEachIndexed { index, bucket ->
                            val minutes = cells[day to bucket]?.minutes ?: 0L
                            val description = stringResource(R.string.analytics_clock_cell, day.getDisplayName(TextStyle.FULL, Locale.getDefault()), labels[index], UiFormat.count(minutes))
                            Box(Modifier.weight(1f).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f + 0.65f * minutes / max))
                                .padding(8.dp).semantics { contentDescription = description }) {
                                Text(UiFormat.count(minutes), Modifier.clearAndSetSemantics { })
                            }
                        }
                    }
                }
                var textValues by rememberSaveable(state.window) { mutableStateOf(false) }
                TextButton(onClick = { textValues = !textValues }) { Text(stringResource(if (textValues) R.string.analytics_hide_clock_values else R.string.analytics_show_clock_values)) }
                if (textValues) DayOfWeek.entries.forEach { day -> TimeOfDayBucket.entries.forEachIndexed { index, bucket ->
                    Text(stringResource(R.string.analytics_clock_cell, day.getDisplayName(TextStyle.FULL, Locale.getDefault()), labels[index], UiFormat.count(cells[day to bucket]?.minutes ?: 0L)))
                } }
            }
            state.clockMatrix.dominant?.let { cell -> Text(stringResource(R.string.analytics_clock_dominant,
                cell.weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()), labels[cell.bucket.ordinal])) }
                ?: Text(stringResource(R.string.analytics_clock_sparse))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.analytics_achievement_activity), style = MaterialTheme.typography.titleSmall)
            Text(scope, Modifier.testTag("analytics-advanced-achievement-scope"), style = MaterialTheme.typography.bodySmall)
            val activity = state.achievementActivity
            Text(stringResource(R.string.analytics_dated_unlocks, activity.unlocks.size))
            Text(stringResource(R.string.analytics_dated_completions, activity.completions.size))
            activity.completions.forEach { Text(stringResource(R.string.analytics_completion_observed, name(it.appId), date(it.observedAt))) }
            activity.previousCount?.let { count ->
                state.comparison?.let { Text(stringResource(R.string.analytics_advanced_comparison_dates,
                    UiFormat.date(it.previousBounds.start), UiFormat.date(it.previousBounds.endInclusive),
                    UiFormat.date(it.currentBounds.start), UiFormat.date(it.currentBounds.endInclusive))) }
                Text(stringResource(R.string.analytics_unlock_comparison, count, activity.unlocks.size))
            } ?: Text(stringResource(R.string.analytics_unlock_comparison_missing))
            activity.rarity.forEach { (tier, count) -> Text(stringResource(R.string.analytics_window_rarity, analyticsRarityLabel(tier), count)) }
            Text(stringResource(R.string.analytics_unlock_coverage, activity.missingRarity, activity.unsupportedUnlocks, activity.unsupportedCompletions))
            Text(stringResource(R.string.analytics_achievement_scope_note))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.analytics_records_title), style = MaterialTheme.typography.titleSmall)
            Text(scope, Modifier.testTag("analytics-advanced-records-scope"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.analytics_records_basis))
            var expanded by rememberSaveable(state.window) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.analytics_hide_records else R.string.analytics_show_records)) }
            if (expanded) {
                val records = state.records
                records.longestSession?.let { Text(stringResource(R.string.analytics_record_session, name(it.appId), it.minutes, date(it.startAt))) }
                    ?: Text(stringResource(R.string.analytics_record_session_missing))
                records.busiestDay?.let { Text(stringResource(R.string.analytics_record_busy, UiFormat.date(it.date), UiFormat.count(it.amount))) }
                records.mostVariedDay?.let { Text(stringResource(R.string.analytics_record_varied, UiFormat.date(it.date), UiFormat.count(it.amount))) }
                if (records.gameRuns.isEmpty()) Text(stringResource(R.string.analytics_record_days_missing))
                records.gameRuns.forEach { Text(stringResource(R.string.analytics_record_run, name(it.appId), UiFormat.count(it.days), UiFormat.date(it.start), UiFormat.date(it.end))) }
                records.longestReturn?.let { Text(stringResource(R.string.analytics_record_return, name(it.appId), UiFormat.count(it.quietDates), UiFormat.date(it.previous), UiFormat.date(it.returned))) }
                    ?: Text(stringResource(R.string.analytics_record_return_missing))
                records.rarestUnlock?.let { Text(stringResource(R.string.analytics_record_rarest, it.name, name(it.appId), number.format(it.rarityPercent), date(it.at))) }
                    ?: Text(stringResource(R.string.analytics_record_rarest_missing))
            }
        }
    }
}

@Composable
private fun analyticsRarityLabel(tier: RarityTier): String = stringResource(when (tier) {
    RarityTier.COMMON -> R.string.analytics_rarity_common
    RarityTier.UNCOMMON -> R.string.analytics_rarity_uncommon
    RarityTier.RARE -> R.string.analytics_rarity_rare
    RarityTier.EPIC -> R.string.analytics_rarity_epic
    RarityTier.LEGENDARY -> R.string.analytics_rarity_legendary
})

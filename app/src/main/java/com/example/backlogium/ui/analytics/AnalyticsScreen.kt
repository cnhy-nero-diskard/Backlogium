package com.example.backlogium.ui.analytics

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.Flame
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.configured) {
        EmptyState(
            title = "Steam not configured",
            message = "Connect your Steam account from the Home screen to see your analytics.",
        )
        return
    }

    if (!state.loading && !state.hasData) {
        EmptyState(
            title = "No analytics yet",
            message = "Play a game and, after the next sync, your trends will appear here.",
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Last 30 days",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        DailyPlaytimeChart(
            days = state.dailyMinutes,
            questThreshold = state.questThreshold,
            modifier = Modifier.fillMaxWidth(),
        )

        StreakSummaryCard(
            currentStreak = state.currentStreak,
            longestStreak = state.longestStreak,
            questMetDaysCount = state.questMetDaysCount,
            windowDays = state.dailyMinutes.size,
        )

        MostPlayedGamesCard(games = state.topGames)
    }
}

/**
 * The daily playtime bar chart, hand-rolled on [Canvas] so no charting dependency is added. One
 * bar per day in the window, a dashed horizontal reference line at the configured quest threshold,
 * and a max-value axis label. Zero-minute days render as a hairline baseline tick so the chart
 * keeps a continuous axis rather than collapsing gaps.
 */
@Composable
private fun DailyPlaytimeChart(
    days: List<AnalyticsDay>,
    questThreshold: Int,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thresholdColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chartHeight = 160.dp

    val maxMinutes = maxOf(days.maxOfOrNull { it.minutes } ?: 0, questThreshold, 1)
    val dayFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("Daily playtime", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                // Left axis: the max value label, rotated-style compact.
                Column(
                    modifier = Modifier.height(chartHeight),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = UiFormat.minutes(maxMinutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(chartHeight),
                ) {
                    val w = size.width
                    val h = size.height
                    if (days.isEmpty()) return@Canvas

                    // Baseline.
                    drawLine(
                        color = trackColor,
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1f,
                    )

                    val barSlot = w / days.size
                    val barWidth = (barSlot * 0.7f).coerceAtLeast(1f)
                    val gap = (barSlot - barWidth) / 2f

                    days.forEachIndexed { index, day ->
                        val barHeight = if (maxMinutes <= 0) 0f else (day.minutes.toFloat() / maxMinutes) * h
                        val left = index * barSlot + gap
                        val top = h - barHeight
                        if (day.minutes > 0) {
                            drawRect(
                                color = barColor,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, barHeight),
                            )
                        } else {
                            // Hairline tick for zero-minute days so the axis reads as continuous.
                            drawLine(
                                color = trackColor,
                                start = Offset(left + barWidth / 2f, h - 1f),
                                end = Offset(left + barWidth / 2f, h - 4f),
                                strokeWidth = 1f,
                            )
                        }
                    }

                    // Quest threshold reference line (dashed).
                    if (questThreshold > 0) {
                        val y = h - (questThreshold.toFloat() / maxMinutes) * h
                        drawLine(
                            color = thresholdColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Window endpoints as axis labels.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                days.firstOrNull()?.let {
                    Text(
                        text = it.date.format(dayFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
                days.lastOrNull()?.let {
                    Text(
                        text = it.date.format(dayFormatter),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreakSummaryCard(
    currentStreak: Int,
    longestStreak: Int,
    questMetDaysCount: Int,
    windowDays: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Flame,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Streak summary", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SummaryStat(label = "Current", value = "$currentStreak")
                SummaryStat(label = "Longest", value = "$longestStreak")
                SummaryStat(
                    label = "Quest met",
                    value = "$questMetDaysCount/$windowDays",
                )
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MostPlayedGamesCard(games: List<AnalyticsGame>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Most played", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (games.isEmpty()) {
                Text(
                    text = "No tracked playtime in the last 30 days yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                games.forEach { game ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GameIcon(iconUrl = game.iconUrl, iconSize = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = game.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = UiFormat.minutes(game.minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

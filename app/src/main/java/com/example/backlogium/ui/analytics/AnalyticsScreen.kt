package com.example.backlogium.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.R
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.components.GameHeroCapsule
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.theme.rarityHalo
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.Bolt
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.Clock
import compose.icons.tablericons.Flame
import compose.icons.tablericons.Trophy
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class AnalyticsActions(
    val onLengthSelected: (AnalyticsWindowLength) -> Unit = {},
    val onStepEarlier: () -> Unit = {},
    val onStepLater: () -> Unit = {},
    val onReturnToCurrent: () -> Unit = {},
)

internal const val TAG_ANALYTICS_EARLIER = "analytics-earlier"
internal const val TAG_ANALYTICS_LATER = "analytics-later"
internal const val TAG_ANALYTICS_CURRENT = "analytics-current"
internal const val TAG_ANALYTICS_WINDOW_OPTIONS = "analytics-window-options"
internal const val TAG_ANALYTICS_CHART_OPTIONS = "analytics-chart-options"
internal const val TAG_ANALYTICS_CHART = "analytics-chart"
internal const val TAG_ANALYTICS_PREVIOUS_DAY = "analytics-previous-day"
internal const val TAG_ANALYTICS_NEXT_DAY = "analytics-next-day"

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AnalyticsContent(
        state = state,
        actions = AnalyticsActions(
            onLengthSelected = viewModel::selectWindowLength,
            onStepEarlier = viewModel::stepAnchorEarlier,
            onStepLater = viewModel::stepAnchorLater,
            onReturnToCurrent = viewModel::returnToCurrentWindow,
        ),
    )
}

@Composable
internal fun AnalyticsContent(
    state: AnalyticsUiState,
    actions: AnalyticsActions = AnalyticsActions(),
) {
    var omitZeroDays by remember { mutableStateOf(true) }
    var windowOptionsExpanded by remember { mutableStateOf(false) }
    var chartOptionsExpanded by remember { mutableStateOf(false) }

    if (!state.configured) {
        EmptyState(
            title = stringResource(R.string.analytics_steam_not_configured),
            message = stringResource(R.string.analytics_steam_not_configured_message),
        )
        return
    }

    val chartDays = if (omitZeroDays) {
        state.dailyMinutes.filter { it.minutes > 0 }
    } else {
        state.dailyMinutes
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.analytics_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.analytics_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Loading has no snapshot yet: keep the period context below visible but do not
        // present empty figures as if they described the window.
        if (!state.loading) {
            AnalyticsOverviewCard(
                days = state.dailyMinutes,
                window = state.window,
                familySharedMinutes = state.familySharedMinutes,
                headline = state.headline,
                leadingGame = state.topGames.firstOrNull(),
            )
        }

        AnalyticsPeriodHeader(
            window = state.window,
            bounds = state.windowBounds,
            canStepEarlier = state.canStepEarlier,
            canStepLater = state.canStepLater,
            isCurrentWindow = state.isCurrentWindow,
            updating = state.updating,
            onStepEarlier = actions.onStepEarlier,
            onStepLater = actions.onStepLater,
            onReturnToCurrent = actions.onReturnToCurrent,
        )

        AnalyticsSecondaryControls(
            windowOptionsExpanded = windowOptionsExpanded,
            onWindowOptionsExpandedChange = { windowOptionsExpanded = it },
            chartOptionsExpanded = chartOptionsExpanded,
            onChartOptionsExpandedChange = { chartOptionsExpanded = it },
        )

        if (windowOptionsExpanded) {
            AnalyticsWindowOptions(
                window = state.window,
                controlsEnabled = !state.updating,
                onLengthSelected = actions.onLengthSelected,
            )
        }

        ChartDisplaySelector(
            omitZeroDays = omitZeroDays,
            onOmitZeroDaysChanged = { omitZeroDays = it },
            optionsExpanded = chartOptionsExpanded,
        )

        if (state.loading || state.updating) {
            Text(
                text = stringResource(R.string.analytics_updating_window),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (state.loading) {
            // Metric area stays in an explicit loading state; the period context above
            // remains visible while the new bounded query lands.
            return@Column
        }

        if (!state.hasData) {
            EmptyState(
                title = stringResource(R.string.analytics_empty_title),
                message = stringResource(R.string.analytics_empty_message),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
            StreakSummaryCard(
                currentStreak = state.currentStreak,
                longestStreak = state.longestStreak,
                questMetDaysCount = state.questMetDaysCount,
                windowDays = state.representedDayCount,
            )
            RarityBreakdownCard(
                breakdown = state.rarityBreakdown,
                achievements = state.rarestAchievements,
            )
        } else {
            DailyPlaytimeChart(
                days = chartDays,
                questThreshold = state.questThreshold,
                gamesByDate = state.gamesByDate,
                modifier = Modifier.fillMaxWidth(),
            )

            StreakSummaryCard(
                currentStreak = state.currentStreak,
                longestStreak = state.longestStreak,
                questMetDaysCount = state.questMetDaysCount,
                windowDays = state.representedDayCount,
            )

            SessionInsightsCard(
                sessionCount = state.sessionInsights.sessionCount,
                averageMinutes = state.sessionInsights.averageMinutes,
                longestMinutes = state.sessionInsights.longestMinutes,
            )

            TimeOfDayCard(
                pattern = state.timeOfDayPattern,
                periodLabel = windowPeriodLabel(state.window, state.windowBounds),
            )

            RarityBreakdownCard(
                breakdown = state.rarityBreakdown,
                achievements = state.rarestAchievements,
            )

            MostPlayedGamesCard(
                games = state.topGames,
                periodLabel = windowPeriodLabel(state.window, state.windowBounds),
            )
        }
    }
}

@Composable
private fun AnalyticsPeriodHeader(
    window: AnalyticsWindow,
    bounds: AnalyticsWindowBounds,
    canStepEarlier: Boolean,
    canStepLater: Boolean,
    isCurrentWindow: Boolean,
    updating: Boolean,
    onStepEarlier: () -> Unit,
    onStepLater: () -> Unit,
    onReturnToCurrent: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.analytics_selected_period),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = windowPeriodLabel(window, bounds),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = windowLengthLabel(window.length),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A dead row of three disabled buttons costs a full viewport row before the chart
        // while communicating nothing. Omit navigation entirely when no move is valid;
        // the spec allows disabled or omitted, and omission keeps the chart in reach.
        if (canStepEarlier || canStepLater || !isCurrentWindow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = canStepEarlier && !updating,
                    onClick = onStepEarlier,
                    modifier = Modifier.testTag(TAG_ANALYTICS_EARLIER),
                ) {
                    Text(stringResource(R.string.analytics_earlier))
                }
                TextButton(
                    enabled = canStepLater && !updating,
                    onClick = onStepLater,
                    modifier = Modifier.testTag(TAG_ANALYTICS_LATER),
                ) {
                    Text(stringResource(R.string.analytics_later))
                }
                TextButton(
                    enabled = !isCurrentWindow && !updating,
                    onClick = onReturnToCurrent,
                    modifier = Modifier.testTag(TAG_ANALYTICS_CURRENT),
                ) {
                    Text(stringResource(R.string.analytics_current))
                }
            }
        }
    }
}

@Composable
private fun AnalyticsSecondaryControls(
    windowOptionsExpanded: Boolean,
    onWindowOptionsExpandedChange: (Boolean) -> Unit,
    chartOptionsExpanded: Boolean,
    onChartOptionsExpandedChange: (Boolean) -> Unit,
) {
    val windowState = if (windowOptionsExpanded) {
        stringResource(R.string.analytics_expanded)
    } else {
        stringResource(R.string.analytics_collapsed)
    }
    val chartState = if (chartOptionsExpanded) {
        stringResource(R.string.analytics_expanded)
    } else {
        stringResource(R.string.analytics_collapsed)
    }
    // One shared row for both disclosures keeps two stacked full-width buttons from
    // pushing the chart below the first viewport on a narrow phone.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onWindowOptionsExpandedChange(!windowOptionsExpanded) },
            modifier = Modifier
                .weight(1f)
                .testTag(TAG_ANALYTICS_WINDOW_OPTIONS)
                .semantics { stateDescription = windowState },
        ) {
            Text(
                text = if (windowOptionsExpanded) {
                    stringResource(R.string.analytics_hide_window_options)
                } else {
                    stringResource(R.string.analytics_show_window_options)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = { onChartOptionsExpandedChange(!chartOptionsExpanded) },
            modifier = Modifier
                .weight(1f)
                .testTag(TAG_ANALYTICS_CHART_OPTIONS)
                .semantics {
                    role = Role.Button
                    stateDescription = chartState
                },
        ) {
            Text(
                text = if (chartOptionsExpanded) {
                    stringResource(R.string.analytics_hide_chart_options)
                } else {
                    stringResource(R.string.analytics_show_chart_options)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnalyticsWindowOptions(
    window: AnalyticsWindow,
    onLengthSelected: (AnalyticsWindowLength) -> Unit,
    controlsEnabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.analytics_rolling_durations),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsWindowLength.entries
                .filter { it.kind == AnalyticsWindowKind.ROLLING }
                .forEach { length ->
                    FilterChip(
                        selected = length == window.length,
                        enabled = controlsEnabled,
                        onClick = { onLengthSelected(length) },
                        label = { Text(windowLengthLabel(length)) },
                    )
                }
        }
        Text(
            text = stringResource(R.string.analytics_calendar_periods),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsWindowLength.entries
                .filter { it.kind == AnalyticsWindowKind.CALENDAR }
                .forEach { length ->
                    FilterChip(
                        selected = length == window.length,
                        enabled = controlsEnabled,
                        onClick = { onLengthSelected(length) },
                        label = { Text(windowLengthLabel(length)) },
                    )
                }
        }
    }
}

@Composable
private fun ChartDisplaySelector(
    omitZeroDays: Boolean,
    onOmitZeroDaysChanged: (Boolean) -> Unit,
    optionsExpanded: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (omitZeroDays) {
                stringResource(R.string.analytics_active_days_default)
            } else {
                stringResource(R.string.analytics_all_days_selected)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!optionsExpanded) return@Column
        Text(
            text = stringResource(R.string.analytics_chart_display),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = omitZeroDays,
                onClick = { onOmitZeroDaysChanged(true) },
                label = { Text(stringResource(R.string.analytics_active_days_only)) },
            )
            FilterChip(
                selected = !omitZeroDays,
                onClick = { onOmitZeroDaysChanged(false) },
                label = { Text(stringResource(R.string.analytics_all_days)) },
            )
        }
    }
}

@Composable
private fun windowLengthLabel(length: AnalyticsWindowLength): String = stringResource(
    when (length) {
        AnalyticsWindowLength.TWO_WEEKS -> R.string.analytics_length_two_weeks
        AnalyticsWindowLength.THIRTY_DAYS -> R.string.analytics_length_thirty_days
        AnalyticsWindowLength.ONE_MONTH -> R.string.analytics_length_one_month
        AnalyticsWindowLength.NINETY_DAYS -> R.string.analytics_length_ninety_days
        AnalyticsWindowLength.ONE_YEAR -> R.string.analytics_length_one_year
    },
)

@Composable
private fun analyticsHeadlineText(headline: AnalyticsHeadline): String = when (headline) {
    AnalyticsHeadline.NoData -> stringResource(R.string.analytics_headline_no_data)
    is AnalyticsHeadline.Compared -> {
        val current = UiFormat.localizedMinutes(headline.currentMinutes)
        val previous = UiFormat.localizedMinutes(headline.previousMinutes)
        when {
            headline.changeMinutes > 0 -> stringResource(R.string.analytics_headline_up, current, previous)
            headline.changeMinutes < 0 -> stringResource(R.string.analytics_headline_down, current, previous)
            else -> stringResource(R.string.analytics_headline_unchanged, current)
        }
    }
    is AnalyticsHeadline.LeadingGame -> stringResource(
        R.string.analytics_headline_leading_game,
        headline.gameName,
        UiFormat.localizedMinutes(headline.minutes),
    )
    is AnalyticsHeadline.ActiveDays -> stringResource(
        R.string.analytics_headline_active_days,
        headline.activeDays,
        headline.totalDays,
    )
}

private fun windowPeriodLabel(window: AnalyticsWindow, bounds: AnalyticsWindowBounds): String {
    return formatAnalyticsWindowPeriod(window, bounds)
}

@Composable
private fun AnalyticsOverviewCard(
    days: List<AnalyticsDay>,
    window: AnalyticsWindow,
    familySharedMinutes: Int,
    headline: AnalyticsHeadline,
    leadingGame: AnalyticsGame?,
) {
    val activeDays = days.count { it.minutes > 0 }
    val totalMinutes = days.sumOf { it.minutes }
    val averageMinutes = if (activeDays == 0) 0 else totalMinutes / activeDays
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val headlineDescription = analyticsHeadlineText(headline)
    val leadingHeadline = headline as? AnalyticsHeadline.LeadingGame

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.analytics_play_snapshot),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
                Text(
                    text = windowLengthLabel(window.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(10.dp))
            if (leadingHeadline != null) {
                // A long title inside a full sentence wraps over several large lines on a
                // narrow phone. Split the hierarchy instead: artwork plus name on one line
                // and its time below, with the full sentence kept as the TalkBack
                // description so assistive tech still hears the complete fact.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = headlineDescription
                        },
                ) {
                    val heroCapsuleUrl = leadingGame?.heroCapsuleUrl.orEmpty()
                    if (heroCapsuleUrl.isNotBlank() && leadingGame != null) {
                        // Portrait capsule art rather than a blown-up app icon, matching the
                        // Library grid treatment. The icon remains the final CDN fallback
                        // before the themed placeholder, e.g. for delisted art.
                        GameHeroCapsule(
                            heroCapsuleUrl = heroCapsuleUrl,
                            fallbackUrls = SteamIconMapper.gridArtworkFallbackUrls(leadingGame.appId) +
                                listOfNotNull(
                                    leadingGame.iconUrl.takeIf { it.isNotBlank() },
                                ),
                            modifier = Modifier.size(width = 52.dp, height = 72.dp),
                            shape = RoundedCornerShape(8.dp),
                        )
                    } else {
                        GameIcon(
                            iconUrl = leadingGame?.iconUrl.orEmpty(),
                            iconSize = 52.dp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = leadingHeadline.gameName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = UiFormat.localizedMinutes(leadingHeadline.minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    text = headlineDescription,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = headlineDescription
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryStat(
                    label = stringResource(R.string.analytics_tracked),
                    value = UiFormat.localizedMinutes(totalMinutes),
                    valueColor = contentColor,
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    label = stringResource(R.string.analytics_active_days),
                    value = UiFormat.count(activeDays),
                    valueColor = contentColor,
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    label = stringResource(R.string.analytics_daily_average),
                    value = UiFormat.localizedMinutes(averageMinutes),
                    valueColor = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }
            // Shared games are already inside every figure above. This names their slice, so a
            // reader can tell how much of the window came from time the app observed rather than
            // from a Steam-reported total. Omitted entirely when there is none — a standing zero
            // would explain a distinction that does not apply to this library.
            if (familySharedMinutes > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.analytics_family_shared_observed,
                        UiFormat.localizedMinutes(familySharedMinutes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * The daily playtime bar chart, hand-rolled on [Canvas] so no charting dependency is added. One
 * bar per day in the window, a dashed horizontal reference line at the configured quest threshold,
 * and a max-value axis label. Tapping a bar selects that day and reveals its exact total and goal
 * status below the plot. Zero-minute days render as a hairline baseline tick so the chart keeps a
 * continuous axis rather than collapsing gaps.
 */
@Composable
private fun DailyPlaytimeChart(
    days: List<AnalyticsDay>,
    questThreshold: Int,
    gamesByDate: Map<java.time.LocalDate, List<AnalyticsGame>>,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val baselineColor = MaterialTheme.colorScheme.outline
    val thresholdColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chartHeight = 176.dp

    val maxMinutes = niceChartMax(
        maxOf(days.maxOfOrNull { it.minutes } ?: 0, questThreshold, 1),
    )
    val dayFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    val totalMinutes = days.sumOf { it.minutes }
    var selectedIndex by remember(days) {
        mutableIntStateOf(initialAnalyticsDaySelection(days) ?: -1)
    }
    val selectDay: (Int) -> Unit = { requestedIndex ->
        analyticsDaySelectionIndex(days.size, requestedIndex)?.let { selectedIndex = it }
    }
    val selectedDay = days.getOrNull(selectedIndex)
    val previousDayLabel = stringResource(R.string.analytics_previous_day)
    val nextDayLabel = stringResource(R.string.analytics_next_day)
    val selectedDayLabel = selectedDay?.date?.format(dayFormatter)
        ?: stringResource(R.string.analytics_no_day_selected)
    val selectedDayDescription = selectedDay?.let { day ->
        val goal = when {
            questThreshold <= 0 -> stringResource(R.string.analytics_no_daily_goal)
            day.minutes >= questThreshold -> stringResource(R.string.analytics_daily_goal_met)
            else -> stringResource(
                R.string.analytics_to_goal,
                UiFormat.localizedMinutes(questThreshold - day.minutes),
            )
        }
        val games = gamesByDate[day.date].orEmpty()
        val breakdown = if (games.isEmpty()) {
            stringResource(R.string.analytics_no_games_day)
        } else {
            var description = ""
            for (index in games.indices) {
                val game = games[index]
                val item = stringResource(
                    R.string.analytics_game_minutes,
                    game.name,
                    UiFormat.localizedMinutes(game.minutes),
                )
                if (index > 0) description += ", "
                description += item
            }
            description
        }
        stringResource(
            R.string.analytics_selected_day_summary,
            day.date.format(dayFormatter),
            UiFormat.localizedMinutes(day.minutes),
            goal,
            breakdown,
        )
    } ?: stringResource(R.string.analytics_no_day_selected)
    val chartSummary = stringResource(
        R.string.analytics_chart_summary,
        selectedDayDescription,
    )

    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.analytics_daily_playtime), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        R.string.analytics_chart_select_day,
                        UiFormat.localizedMinutes(totalMinutes),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                // Keep the labels outside the canvas so they remain crisp and do not overlap bars.
                Column(
                    modifier = Modifier
                        .width(44.dp)
                        .height(chartHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(UiFormat.localizedMinutes(maxMinutes), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    Text(UiFormat.localizedMinutes(maxMinutes / 2), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    Text(stringResource(R.string.analytics_zero_minutes), style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(chartHeight)
                            .testTag(TAG_ANALYTICS_CHART)
                            .semantics {
                                contentDescription = chartSummary
                            }
                            .pointerInput(days, maxMinutes) {
                                detectTapGestures { offset ->
                                    if (days.isNotEmpty()) {
                                        selectDay((offset.x / (size.width / days.size)).toInt())
                                    }
                                }
                            },
                    ) {
                        val w = size.width
                        val h = size.height
                        if (days.isEmpty()) return@Canvas

                        // Quiet gridlines make the scale visible without competing with the data.
                        listOf(0f, 0.5f).forEach { fraction ->
                            val y = h * fraction
                            drawLine(
                                color = gridColor.copy(alpha = 0.7f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1f,
                            )
                        }

                        val barSlot = w / days.size
                        val barWidth = (barSlot * 0.68f).coerceAtLeast(2f)
                        val gap = (barSlot - barWidth).coerceAtLeast(0f) / 2f

                        days.forEachIndexed { index, day ->
                            val left = index * barSlot + gap
                            if (index == selectedIndex) {
                                drawRoundRect(
                                    color = barColor.copy(alpha = 0.12f),
                                    topLeft = Offset(index * barSlot + 1f, 0f),
                                    size = Size((barSlot - 2f).coerceAtLeast(1f), h),
                                    cornerRadius = CornerRadius(8f, 8f),
                                )
                            }
                            val barHeight = ((day.minutes.toFloat() / maxMinutes) * h)
                                .coerceAtLeast(if (day.minutes > 0) 3f else 0f)
                            val top = h - barHeight
                            if (day.minutes > 0) {
                                drawRoundRect(
                                    color = if (index == selectedIndex) barColor else barColor.copy(alpha = 0.72f),
                                    topLeft = Offset(left, top),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(4f, 4f),
                                )
                            } else {
                                // A small tick preserves the position of an empty day without noise.
                                drawLine(
                                    color = gridColor,
                                    start = Offset(left + barWidth / 2f, h),
                                    end = Offset(left + barWidth / 2f, h - 4f),
                                    strokeWidth = 1f,
                                )
                            }
                        }

                        // Quest threshold reference line (dashed).
                        if (questThreshold > 0) {
                            val y = h - (questThreshold.toFloat() / maxMinutes) * h
                            drawLine(
                                color = thresholdColor.copy(alpha = 0.85f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                            )
                        }

                        // Draw the baseline last so bars visibly terminate on a firm zero axis.
                        drawLine(
                            color = baselineColor,
                            start = Offset(0f, h - 1f),
                            end = Offset(w, h - 1f),
                            strokeWidth = 2f,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        days.firstOrNull()?.let {
                            Text(it.date.format(dayFormatter), style = MaterialTheme.typography.labelSmall, color = labelColor)
                        }
                        days.lastOrNull()?.let {
                            Text(it.date.format(dayFormatter), style = MaterialTheme.typography.labelSmall, color = labelColor)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(width = 18.dp, height = 2.dp)) {
                    drawLine(
                        color = thresholdColor,
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.analytics_daily_goal,
                        UiFormat.localizedMinutes(questThreshold),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    enabled = selectedIndex > 0,
                    onClick = { selectDay(stepAnalyticsDaySelection(selectedIndex, days.size, -1) ?: selectedIndex) },
                    modifier = Modifier.semantics {
                        contentDescription = previousDayLabel
                    }.testTag(TAG_ANALYTICS_PREVIOUS_DAY),
                ) {
                    Icon(
                        imageVector = TablerIcons.ChevronLeft,
                        contentDescription = null,
                    )
                }
                Text(
                    text = selectedDayLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = selectedDayDescription
                        },
                )
                IconButton(
                    enabled = selectedIndex >= 0 && selectedIndex < days.lastIndex,
                    onClick = { selectDay(stepAnalyticsDaySelection(selectedIndex, days.size, 1) ?: selectedIndex) },
                    modifier = Modifier.semantics {
                        contentDescription = nextDayLabel
                    }.testTag(TAG_ANALYTICS_NEXT_DAY),
                ) {
                    Icon(
                        imageVector = TablerIcons.ChevronRight,
                        contentDescription = null,
                    )
                }
            }
            selectedDay?.let { day ->
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = day.date.format(dayFormatter),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                questThreshold <= 0 -> stringResource(R.string.analytics_no_daily_goal)
                                day.minutes >= questThreshold -> stringResource(R.string.analytics_daily_goal_met)
                                else -> stringResource(
                                    R.string.analytics_to_goal,
                                    UiFormat.localizedMinutes(questThreshold - day.minutes),
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                        )
                    }
                    Text(
                        text = UiFormat.localizedMinutes(day.minutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (day.minutes >= questThreshold && questThreshold > 0) {
                            thresholdColor
                        } else {
                            barColor
                        },
                    )
                }
                val games = gamesByDate[day.date].orEmpty()
                if (games.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.analytics_games_played),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    games.forEach { game ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GameIcon(iconUrl = game.iconUrl, iconSize = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = game.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = UiFormat.localizedMinutes(game.minutes),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.analytics_no_games_day),
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                    )
                }
            }
        }
    }
}

/** Round the chart ceiling to a small, readable scale instead of labeling an arbitrary peak. */
private fun niceChartMax(maxMinutes: Int): Int {
    val minimum = maxMinutes.coerceAtLeast(1)
    val step = when {
        minimum <= 60 -> 15
        minimum <= 180 -> 30
        minimum <= 360 -> 60
        minimum <= 720 -> 120
        else -> 240
    }
    return ((minimum + step - 1) / step) * step
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
                Text(stringResource(R.string.analytics_streak_summary), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SummaryStat(
                    label = stringResource(R.string.analytics_current_streak),
                    value = UiFormat.count(currentStreak),
                )
                SummaryStat(
                    label = stringResource(R.string.analytics_longest_streak),
                    value = UiFormat.count(longestStreak),
                )
                SummaryStat(
                    label = stringResource(R.string.analytics_quest_met),
                    value = stringResource(
                        R.string.analytics_quest_met_count,
                        UiFormat.count(questMetDaysCount),
                        UiFormat.count(windowDays),
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.analytics_streak_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    // Weighted by callers on narrow phones so each cell keeps an equal share instead of
    // sizing itself. Values never ellipsize: a three-column phone row is too narrow for
    // durations such as "87 hrs 42 mins", so the value wraps to a second centered line
    // rather than truncating an exact KPI to "87 hrs 42...". Labels may ellipsize after
    // two lines since they repeat the card title context.
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 2,
            softWrap = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun MostPlayedGamesCard(games: List<AnalyticsGame>, periodLabel: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.analytics_most_played), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (games.isEmpty()) {
                Text(
                    text = stringResource(R.string.analytics_no_tracked_playtime, periodLabel),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = game.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Named on the row rather than only in the card's footnote: this is
                            // where a reader compares one game's minutes against another's, and
                            // the two figures do not mean quite the same thing.
                            if (game.isFamilyShared) {
                                Text(
                                    text = stringResource(R.string.analytics_family_sharing),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = UiFormat.localizedMinutes(game.minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Session shape over the window — how many sessions were tracked, the average length, and the
 * longest single session. Complements the daily bar chart: same underlying sessions, but the
 * rhythm of a single sitting rather than a whole day.
 */
@Composable
private fun SessionInsightsCard(
    sessionCount: Int,
    averageMinutes: Int,
    longestMinutes: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.analytics_session_insights), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SummaryStat(
                    label = stringResource(R.string.analytics_sessions),
                    value = UiFormat.count(sessionCount),
                )
                SummaryStat(
                    label = stringResource(R.string.analytics_average_session),
                    value = UiFormat.localizedMinutes(averageMinutes),
                )
                SummaryStat(
                    label = stringResource(R.string.analytics_longest_session),
                    value = UiFormat.localizedMinutes(longestMinutes),
                )
            }
        }
    }
}

/**
 * When the player's tracked minutes tend to land, bucketed into four parts of the day. The peak
 * bucket is highlighted so "I'm a night owl" reads at a glance.
 */
@Composable
private fun TimeOfDayCard(pattern: TimeOfDayPattern, periodLabel: String) {
    val peak = pattern.peakBucket
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Clock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.analytics_play_time_of_day), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            val buckets = listOf(
                TimeOfDayBucket.MORNING to pattern.morningMinutes,
                TimeOfDayBucket.AFTERNOON to pattern.afternoonMinutes,
                TimeOfDayBucket.EVENING to pattern.eveningMinutes,
                TimeOfDayBucket.NIGHT to pattern.nightMinutes,
            )
            val maxMinutes = maxOf(1, buckets.maxOf { it.second })
            Row(modifier = Modifier.fillMaxWidth()) {
                buckets.forEach { (label, minutes) ->
                    TimeOfDayBar(
                        label = label,
                        minutes = minutes,
                        fraction = minutes.toFloat() / maxMinutes,
                        highlighted = label == peak,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = peak?.let {
                    stringResource(R.string.analytics_peak_time, timeOfDayLabel(it))
                } ?: stringResource(R.string.analytics_no_tracked_play_period, periodLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeOfDayBar(
    label: TimeOfDayBucket,
    minutes: Int,
    fraction: Float,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.secondary
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = UiFormat.localizedMinutes(minutes),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceIn(0f, 1f))
                    .background(if (highlighted) barColor else mutedColor.copy(alpha = 0.55f)),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = timeOfDayLabel(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun timeOfDayLabel(bucket: TimeOfDayBucket): String = stringResource(
    when (bucket) {
        TimeOfDayBucket.MORNING -> R.string.analytics_morning
        TimeOfDayBucket.AFTERNOON -> R.string.analytics_afternoon
        TimeOfDayBucket.EVENING -> R.string.analytics_evening
        TimeOfDayBucket.NIGHT -> R.string.analytics_night
    },
)

/**
 * The player's achievement-rarity profile — how many unlocked achievements fall in each of Steam's
 * rarity tiers. A single stacked bar with segment widths proportional to each tier's count, plus a
 * per-tier legend. Tier colors reuse the game-detail halo palette so "rare" means the same color
 * everywhere in the app.
 */
@Composable
private fun RarityBreakdownCard(
    breakdown: RarityBreakdown,
    achievements: List<AnalyticsRarityAchievement>,
) {
    val tiers = listOf(
        RarityTier.COMMON to breakdown.common,
        RarityTier.UNCOMMON to breakdown.uncommon,
        RarityTier.RARE to breakdown.rare,
        RarityTier.EPIC to breakdown.epic,
        RarityTier.LEGENDARY to breakdown.legendary,
    )
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Trophy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.analytics_achievement_rarity), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                // A glyph rather than a word, for the same reason the display-density control is
                // one: what is left of this row after the title and a four-digit count is about
                // fifty dp, and any label yields to it. "Show rarest" ellipsized to "Sh…", and
                // shortening it to "Rarest" only bought "Ra…" — a truncated word says nothing,
                // while a chevron at a fixed size cannot be truncated at all. The count is the
                // child that must survive intact, so the control is the one that gives way, and
                // giving way now costs it nothing.
                if (achievements.isNotEmpty()) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) {
                                TablerIcons.ChevronUp
                            } else {
                                TablerIcons.ChevronDown
                            },
                            contentDescription = if (expanded) {
                                stringResource(R.string.analytics_hide_rarest)
                            } else {
                                stringResource(R.string.analytics_show_rarest)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    // A lifetime total a player might screenshot: pinned to one line and left
                    // unabbreviated, since rounding it to fix a layout bug would smuggle a
                    // presentation change in as a fix.
                    text = stringResource(
                        R.string.analytics_unlocked_count,
                        UiFormat.count(breakdown.total),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = stringResource(R.string.analytics_all_time_profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (breakdown.total == 0) {
                Text(
                    text = stringResource(R.string.analytics_no_unlocked_achievements),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    tiers.forEach { (tier, count) ->
                        if (count > 0) {
                            Box(
                                Modifier
                                    .weight(count.toFloat())
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.rarityHalo(tier)),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                tiers.forEach { (tier, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.rarityHalo(tier)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = rarityTierLabel(tier),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.analytics_achievements_count,
                                count,
                                UiFormat.count(count),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (expanded && achievements.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.analytics_rarest_unlocked),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    achievements.forEach { achievement ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.rarityHalo(achievement.tier)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = achievement.achievementName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = achievement.gameName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.analytics_rarity_detail,
                                    formatRarityPercent(achievement.rarityPercent),
                                    rarityTierLabel(achievement.tier),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rarityTierLabel(tier: RarityTier): String = stringResource(
    when (tier) {
        RarityTier.COMMON -> R.string.analytics_rarity_common
        RarityTier.UNCOMMON -> R.string.analytics_rarity_uncommon
        RarityTier.RARE -> R.string.analytics_rarity_rare
        RarityTier.EPIC -> R.string.analytics_rarity_epic
        RarityTier.LEGENDARY -> R.string.analytics_rarity_legendary
    },
)

private fun formatRarityPercent(percent: Double): String =
    NumberFormat.getPercentInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(percent / 100.0)

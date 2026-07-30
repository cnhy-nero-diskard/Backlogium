package com.example.backlogium.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.CircleMinus

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.configured) {
        EmptyState(
            title = "Steam not configured",
            message = "Connect your Steam account from the Home screen to track sessions.",
        )
        return
    }

    if (state.days.isEmpty()) {
        EmptyState(
            title = "No history yet",
            message = "Play a game and, after the next sync, your sessions and daily stats will appear here.",
        )
        return
    }

    // Transient — resets on navigation away, per the regroup-history design: this is a lens onto
    // the data, not a saved preference.
    var expandedDays by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedGames by remember { mutableStateOf<Set<Pair<String, Long>>>(emptySet()) }
    var autoExpandedToday by remember { mutableStateOf(false) }

    LaunchedEffect(state.today) {
        if (!autoExpandedToday && state.today.isNotEmpty()) {
            expandedDays = expandedDays + state.today
            autoExpandedToday = true
        }
    }

    val toggleDay: (String) -> Unit = { date ->
        expandedDays = if (date in expandedDays) expandedDays - date else expandedDays + date
    }
    val toggleGame: (String, Long) -> Unit = { date, appId ->
        val key = date to appId
        expandedGames = if (key in expandedGames) expandedGames - key else expandedGames + key
    }

    val todayGroup = state.days.firstOrNull { it.date == state.today }
    val pastGroups = state.days.filterNot { it.date == state.today }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        todayGroup?.let { day ->
            dayItems(day, expandedDays, expandedGames, toggleDay, toggleGame)
        }

        item(key = "daily-stats-divider") { SectionHeader("Daily stats") }

        pastGroups.forEach { day ->
            dayItems(day, expandedDays, expandedGames, toggleDay, toggleGame)
        }

        item(key = "load-older") {
            TextButton(onClick = viewModel::loadOlder, modifier = Modifier.fillMaxWidth()) {
                Text("Load older")
            }
        }
    }
}

/** One day's rows: its header, then (if expanded) its games and — per expanded game — its sessions. */
private fun LazyListScope.dayItems(
    day: HistoryDayGroup,
    expandedDays: Set<String>,
    expandedGames: Set<Pair<String, Long>>,
    onToggleDay: (String) -> Unit,
    onToggleGame: (String, Long) -> Unit,
) {
    val dayExpanded = day.date in expandedDays
    val expandable = day.games.isNotEmpty()

    item(key = "day-${day.date}") {
        DayHeaderRow(
            day = day,
            expanded = dayExpanded,
            expandable = expandable,
            onClick = { onToggleDay(day.date) },
        )
    }

    if (!dayExpanded) return

    day.games.forEach { game ->
        val gameKey = day.date to game.appId
        val gameExpanded = gameKey in expandedGames

        item(key = "game-${day.date}-${game.appId}") {
            GameGroupRow(
                game = game,
                expanded = gameExpanded,
                onClick = { onToggleGame(day.date, game.appId) },
            )
        }

        if (gameExpanded) {
            items(game.sessions, key = { "session-${it.id}" }) { session ->
                SessionRow(session)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun DayHeaderRow(
    day: HistoryDayGroup,
    expanded: Boolean,
    expandable: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .let { if (expandable) it.clickable(onClick = onClick) else it },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(day.date, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${UiFormat.minutes(day.minutesPlayed)} played" +
                            if (day.goalMinutesPlayed > 0) {
                                " · ${UiFormat.minutes(day.goalMinutesPlayed)} on Focus games"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expandable) {
                        Icon(
                            imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                        )
                    }
                    Icon(
                        imageVector = if (day.questMet) TablerIcons.CircleCheck else TablerIcons.CircleMinus,
                        contentDescription = if (day.questMet) "Quest met" else "Quest not met",
                        tint = if (day.questMet) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (day.achievements.iconUrls.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    day.achievements.iconUrls.forEach { icon ->
                        AchievementThumbnail(icon, modifier = Modifier.padding(end = 4.dp))
                    }
                    if (day.achievements.overflowCount > 0) {
                        Text(
                            text = "+${day.achievements.overflowCount}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementThumbnail(iconUrl: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = modifier
            .size(20.dp)
            .clip(shape),
        loading = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        },
        error = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        },
    )
}

@Composable
private fun GameGroupRow(
    game: HistoryGameGroup,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameIcon(game.iconUrl)
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = UiFormat.minutes(game.minutesPlayed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Icon(
                    imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: HistorySessionUi) {
    Text(
        text = sessionLabel(session),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
    )
}

/**
 * "~3:00 PM – 5:55 PM · 2h 35m played" (plus "· live" while open). The tilde and the word
 * "played" are load-bearing (regroup-history design): dropping either makes the screen look
 * arithmetically broken, since the range and the tracked minutes are different measurements that
 * can legitimately disagree (see `SessionDiffer`).
 */
private fun sessionLabel(session: HistorySessionUi): String {
    val range = UiFormat.sessionRange(
        startAt = session.startAt,
        endAt = session.endAt,
        open = session.open,
    )
    val minutes = "${UiFormat.minutes(session.minutes)} played"
    return if (session.open) "$range · $minutes · live" else "$range · $minutes"
}

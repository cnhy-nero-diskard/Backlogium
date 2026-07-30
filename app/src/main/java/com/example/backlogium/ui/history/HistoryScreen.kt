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
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
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
            item(key = "sessions-${day.date}-${game.appId}") {
                SessionsPanel(game.sessions)
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
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        day.date,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

/** Rounded on every corner while collapsed; flat on the bottom while expanded, so [SessionsPanel] reads as this card's own dropdown content rather than a separate, disconnected row. */
private val gameCardCornerRadius = 12.dp
private fun gameCardShape(expanded: Boolean): RoundedCornerShape = if (expanded) {
    RoundedCornerShape(
        topStart = gameCardCornerRadius,
        topEnd = gameCardCornerRadius,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
} else {
    RoundedCornerShape(gameCardCornerRadius)
}

@Composable
private fun GameGroupRow(
    game: HistoryGameGroup,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = gameCardShape(expanded),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = if (expanded) 0.dp else 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                GameIcon(game.iconUrl)
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = UiFormat.minutes(game.minutesPlayed),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
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

/**
 * A game's sessions for the day, rendered as one card directly beneath [GameGroupRow] — same
 * horizontal margin, flat top meeting the game row's flat bottom, and a distinct tonal surface —
 * so the block reads as that row's own expanded content rather than an unrelated floating list.
 */
@Composable
private fun SessionsPanel(sessions: List<HistorySessionUi>) {
    Card(
        shape = RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = gameCardCornerRadius,
            bottomEnd = gameCardCornerRadius,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 22.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sessions.forEach { session ->
                Text(
                    text = sessionLabel(session),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * "~12:43 AM · 23m played" (plus "· live" while open). Deliberately an approximate *start*, not a
 * start–end range: a two-endpoint range invites subtracting them into a "duration" that can
 * legitimately disagree with the tracked minutes once Steam's own counter lags, which reads as an
 * arithmetic error rather than two honest, different measurements (see `SessionDiffer`).
 */
private fun sessionLabel(session: HistorySessionUi): String {
    val start = UiFormat.approxTime(session.startAt)
    val minutes = "${UiFormat.minutes(session.minutes)} played"
    return if (session.open) "$start · $minutes · live" else "$start · $minutes"
}

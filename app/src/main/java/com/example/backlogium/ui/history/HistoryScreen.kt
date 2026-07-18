package com.example.backlogium.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.util.UiFormat

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.configured) {
        EmptyState(
            title = "Steam not configured",
            message = "Add your Steam credentials to local.properties and rebuild to track sessions.",
        )
        return
    }

    if (state.sessions.isEmpty() && state.days.isEmpty()) {
        EmptyState(
            title = "No history yet",
            message = "Play a game and, after the next sync, your sessions and daily stats will appear here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (state.sessions.isNotEmpty()) {
            item { SectionHeader("Recent sessions") }
            items(state.sessions, key = { it.id }) { session ->
                SessionRow(session)
            }
        }

        if (state.days.isNotEmpty()) {
            item { SectionHeader("Daily stats") }
            items(state.days, key = { it.date }) { day ->
                DayStatRow(day)
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
private fun SessionRow(session: SessionUi) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(session.gameName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = UiFormat.dateTime(session.startAt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (session.open) {
                    "${UiFormat.minutes(session.minutes)} · live"
                } else {
                    UiFormat.minutes(session.minutes)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DayStatRow(day: DayStatUi) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(day.date, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${UiFormat.minutes(day.minutesPlayed)} played" +
                        if (day.goalMinutesPlayed > 0) {
                            " · ${UiFormat.minutes(day.goalMinutesPlayed)} on goals"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (day.questMet) "✅" else "—",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

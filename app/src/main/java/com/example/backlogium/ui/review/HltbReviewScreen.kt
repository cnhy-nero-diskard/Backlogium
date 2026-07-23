package com.example.backlogium.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.util.UiFormat

/**
 * Match-review surface: each game flagged as needing an HLTB match is listed with its
 * candidate entries; tapping a candidate resolves the match and drops the game from the list.
 * Shows an empty state when nothing needs review.
 */
@Composable
fun HltbReviewScreen(viewModel: HltbReviewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.loading && state.games.isEmpty()) {
        EmptyState(
            title = "Nothing to review",
            message = "Games with an ambiguous HowLongToBeat match appear here after a refresh.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.games, key = { it.appId }) { game ->
            ReviewCard(
                game = game,
                onSelect = { candidate -> viewModel.resolve(game.appId, candidate) },
            )
        }
    }
}

@Composable
private fun ReviewCard(game: ReviewGameUi, onSelect: (HltbCandidate) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = game.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Choose the correct HowLongToBeat entry:",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (game.candidates.isEmpty()) {
                Text(
                    text = "No candidates were retained for this game.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                game.candidates.forEachIndexed { index, candidate ->
                    if (index > 0) HorizontalDivider()
                    CandidateRow(candidate = candidate, onClick = { onSelect(candidate) })
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: HltbCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
            val completionist = candidate.completionistMinutes
            Text(
                text = if (completionist != null) {
                    "Completionist: ${UiFormat.minutes(completionist)}"
                } else {
                    "No Completionist length"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

package com.example.backlogium.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.util.rememberReducedMotion

/**
 * Announcement for games a poll found in the library that it had no record of — a Steam sale used
 * to add eight games in silence, sorted by playtime into the bottom of a list nobody scrolls to.
 *
 * Non-modal, following [StreakBrokenOverlay]: the surrounding Box has no click handling, so Home
 * stays scrollable and usable behind the card. This is an announcement, not a decision.
 *
 * It is deliberately not a progress event. `progress-events` has exactly the delivery semantics
 * this wants — delivered once, acknowledged, survives process death — but its first rule is that
 * only earned progress produces events, and buying a game is not earned progress. Adding it there
 * would break the one sentence that gives that capability its meaning.
 */
@Composable
fun AcquiredGamesBanner(
    acquired: AcquiredGamesUi,
    onViewLibrary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (acquired.totalCount <= 0) return

    val reducedMotion = rememberReducedMotion()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (reducedMotion) {
            AcquiredGamesCard(acquired, onViewLibrary, onDismiss)
        } else {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            ) {
                AcquiredGamesCard(acquired, onViewLibrary, onDismiss)
            }
        }
    }
}

@Composable
private fun AcquiredGamesCard(
    acquired: AcquiredGamesUi,
    onViewLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 6.dp, end = 8.dp),
        ) {
            Text(
                text = acquiredGamesTitle(acquired.totalCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = acquiredGamesDetail(acquired),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                // Opens the Library rather than a filtered view of the new games: this change adds
                // no query axis, and inventing one for a banner would be the wrong place to start.
                TextButton(onClick = onViewLibrary) { Text("View library") }
            }
        }
    }
}

/** "1 new game" / "8 new games" — the count is the claim, so it leads. */
internal fun acquiredGamesTitle(totalCount: Int): String =
    if (totalCount == 1) "1 new game" else "$totalCount new games"

/**
 * The names, then a count of whatever is left over.
 *
 * A game whose name the library does not know still contributes to the leftover count rather than
 * vanishing, so the sentence can never describe fewer arrivals than the title claims.
 */
internal fun acquiredGamesDetail(acquired: AcquiredGamesUi): String {
    val names = acquired.namedGames.joinToString(", ")
    return when {
        acquired.namedGames.isEmpty() -> "Added to your library."
        acquired.unnamedCount <= 0 -> "$names — added to your library."
        else -> "$names and ${acquired.unnamedCount} more — added to your library."
    }
}

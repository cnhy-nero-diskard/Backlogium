package com.example.backlogium.ui.gamedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.ui.components.EmptyState
import compose.icons.TablerIcons
import compose.icons.tablericons.Trophy

/**
 * Per-game achievement list: unlock state, rarity tier, and contributed XP for each achievement
 * (add-steam-achievements). Shows an empty state when the game has no stored achievement data.
 */
@Composable
fun GameDetailScreen(viewModel: GameDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.loading && state.achievements.isEmpty()) {
        EmptyState(
            title = state.gameName.ifBlank { "Game details" },
            message = "No achievements to show for this game yet.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        item {
            Text(
                text = state.gameName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        if (state.allUnlocked) {
            item { GameCompletedBanner() }
        }
        items(state.achievements, key = { it.apiName }) { achievement ->
            AchievementRow(achievement)
        }
    }
}

/**
 * Striking, unmissable banner shown when every achievement for a game is unlocked (100%
 * completion) — the gold accent reserved elsewhere for level-up/streak moments, so it reads
 * as a comparable milestone.
 */
@Composable
private fun GameCompletedBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.Trophy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "GAME COMPLETED",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = "Every achievement unlocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun AchievementRow(achievement: AchievementUi) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .alpha(if (achievement.unlocked) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AchievementIcon(achievement.iconUrl)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(achievement.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = achievementStatusLabel(achievement),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (achievement.unlocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun achievementStatusLabel(achievement: AchievementUi): String {
    if (!achievement.unlocked) return "Locked"
    val tier = achievement.tier ?: return "Unlocked"
    return "${tier.name.lowercase().replaceFirstChar { it.uppercase() }} · +${achievement.xp} XP"
}

@Composable
private fun AchievementIcon(iconUrl: String?) {
    val shape = RoundedCornerShape(8.dp)
    if (iconUrl.isNullOrBlank()) {
        Box(
            Modifier
                .size(40.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TablerIcons.Trophy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        return
    }
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(shape),
        loading = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        error = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TablerIcons.Trophy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

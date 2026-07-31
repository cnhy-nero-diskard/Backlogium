package com.example.backlogium.ui.gamedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsSort
import compose.icons.tablericons.Trophy
import java.util.Locale

/**
 * One game: its own summary — art, playtime, HowLongToBeat lengths, achievement completion, XP —
 * above its achievement list, which is sortable by date achieved or rarity and shows each
 * achievement's description and unlock rate (enhance-game-detail).
 *
 * The summary is a header section on this same scrolling list rather than a tab, so the achievement
 * list stays one glance away, and it renders even for a game with no achievement data at all —
 * a game screen showing nothing but an empty state was the gap this closed.
 */
@Composable
fun GameDetailScreen(viewModel: GameDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            GameSummarySection(name = state.gameName, summary = state.summary)
        }
        if (state.allUnlocked) {
            item { GameCompletedBanner() }
        }
        if (!state.loading && state.achievements.isEmpty()) {
            item { NoAchievementsNotice() }
        } else if (state.achievements.isNotEmpty()) {
            item {
                AchievementSortControl(
                    selected = state.sort,
                    onSelect = viewModel::setSort,
                )
            }
            items(state.achievements, key = { it.apiName }) { achievement ->
                AchievementRow(achievement)
            }
        }
    }
}

/**
 * The game's own facts, kept deliberately tight — art, one playtime line, the HLTB lengths, and a
 * completion/XP line — so the first achievement row sits at or near the fold on a typical phone.
 */
@Composable
private fun GameSummarySection(name: String, summary: GameSummaryUi) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (summary.headerUrl.isNotBlank()) {
                HeaderArt(summary.headerUrl)
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (summary.iconUrl.isNotBlank()) {
                        GameIcon(summary.iconUrl)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                        PlaytimeLine(summary)
                    }
                }
                CompletionLine(summary)
                if (summary.hasHltb) {
                    Spacer(Modifier.height(8.dp))
                    HltbLengths(summary)
                }
            }
        }
    }
}

/** Store header art as a wide banner, themed while loading and simply absent on failure. */
@Composable
private fun HeaderArt(headerUrl: String) {
    SubcomposeAsyncImage(
        model = headerUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        loading = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        // No glyph fallback: a failed banner should read as "no art", not as a broken image.
        error = {},
    )
}

/**
 * Steam's lifetime playtime, plus the tracked-vs-imported split when history was imported. The
 * split is omitted otherwise, where it would only restate the total.
 */
@Composable
private fun PlaytimeLine(summary: GameSummaryUi) {
    Text(
        text = "${UiFormat.minutes(summary.playtimeMinutes)} played",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (summary.showPlaytimeSplit) {
        Text(
            text = "${UiFormat.minutes(summary.trackedMinutes)} tracked · " +
                "${UiFormat.minutes(summary.importedMinutes)} imported",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Achievement completion and the game's XP contribution. The XP figure is the Library's own
 * derivation, so the two screens cannot disagree.
 */
@Composable
private fun CompletionLine(summary: GameSummaryUi) {
    val completion = if (summary.achievementsTotal > 0) {
        "${summary.achievementsUnlocked}/${summary.achievementsTotal} achievements"
    } else {
        null
    }
    Text(
        text = listOfNotNull(completion, "${summary.xpContributed} XP").joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * The HowLongToBeat lengths that resolved. Each is omitted individually when unknown and the whole
 * block is gated on at least one being present, so nothing here ever renders as a zero or a dash.
 */
@Composable
private fun HltbLengths(summary: GameSummaryUi) {
    val lengths = listOfNotNull(
        summary.mainStoryMinutes?.let { "Main Story" to it },
        summary.mainExtraMinutes?.let { "Main + Extra" to it },
        summary.completionistMinutes?.let { "Completionist" to it },
        summary.allStylesMinutes?.let { "All Styles" to it },
    )
    Column {
        Text(
            text = "HowLongToBeat",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lengths.forEach { (label, minutes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = UiFormat.minutes(minutes),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Sort lens for the achievement list. Transient by design — a lens, not a preference — so it resets
 * to date-achieved on the next visit rather than costing a persisted key and a settings surface.
 */
@Composable
private fun AchievementSortControl(
    selected: AchievementSort,
    onSelect: (AchievementSort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = TablerIcons.ArrowsSort,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        AchievementSort.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.label) },
            )
        }
    }
}

private val AchievementSort.label: String
    get() = when (this) {
        AchievementSort.DATE_ACHIEVED -> "Recent"
        AchievementSort.RARITY -> "Rarest"
    }

/**
 * Shown in place of the achievement list when a game has no stored achievements — the summary above
 * still stands, so the screen explains the absence rather than looking broken.
 */
@Composable
private fun NoAchievementsNotice() {
    Text(
        text = "No achievements to show for this game yet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
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
            .padding(top = 12.dp),
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

/**
 * One achievement: icon, name, description, and a status line carrying tier/XP plus how rare it is.
 *
 * The locked treatment stays a whole-row alpha rather than per-element colouring — with the row now
 * carrying a description and an unlock rate as well, dimming the block keeps "locked" legible as one
 * signal instead of three competing muted greys.
 */
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
                AchievementDescription(achievement)
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

/**
 * The achievement's description, or a "Hidden achievement" label when Steam withholds it — naming
 * that state explains the gap, where blank space reads as a bug. A row with neither (a pre-migration
 * row not yet re-fetched) simply shows nothing.
 */
@Composable
private fun AchievementDescription(achievement: AchievementUi) {
    val text = when {
        achievement.description != null -> achievement.description
        achievement.showHiddenLabel -> "Hidden achievement"
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The row's status line: unlock state, rarity tier and XP when tierable, and the share of players
 * who have it.
 *
 * The percent shown is the one that produced the tier beside it (the frozen snapshot), falling back
 * to the live global percent only for locked rows, which have no snapshot. That is why a Legendary
 * row can never read "6% of players" — the two halves of this line are the same number by
 * construction, not by coincidence.
 */
private fun achievementStatusLabel(achievement: AchievementUi): String {
    val rate = achievement.unlockPercent?.let { "${formatPercent(it)}% of players have this" }
    if (!achievement.unlocked) {
        return listOfNotNull("Locked", rate).joinToString(" · ")
    }
    val tier = achievement.tier
        ?: return listOfNotNull("Unlocked", rate).joinToString(" · ")
    val tierLabel = tier.name.lowercase().replaceFirstChar { it.uppercase() }
    return listOfNotNull("$tierLabel · +${achievement.xp} XP", rate).joinToString(" · ")
}

/** One decimal: rarity's whole point is the difference between 0.8% and 8%. */
private fun formatPercent(percent: Double): String =
    String.format(Locale.getDefault(), "%.1f", percent)

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

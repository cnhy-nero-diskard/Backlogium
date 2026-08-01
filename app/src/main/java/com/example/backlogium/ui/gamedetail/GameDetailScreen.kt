package com.example.backlogium.ui.gamedetail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.theme.rarityHalo
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsSort
import compose.icons.tablericons.Trophy
import java.util.Locale

/**
 * One game: its own summary — art, playtime, HowLongToBeat lengths, achievement completion, XP,
 * and its current Steam concurrent-player count when available (add-active-player-count) — above
 * its achievement list, which is sortable by date achieved or rarity and shows each achievement's
 * description and unlock rate (enhance-game-detail).
 *
 * The summary is a header section on this same scrolling list rather than a tab, so the achievement
 * list stays one glance away, and it renders even for a game with no achievement data at all —
 * a game screen showing nothing but an empty state was the gap this closed.
 */
@Composable
fun GameDetailScreen(
    viewModel: GameDetailViewModel = hiltViewModel(),
    onAccentColorChanged: (Color?) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accentColor by rememberHeaderAccentColor(state.summary.headerUrl)
    // The wash itself is painted by the app shell, behind the top bar as well as this screen's own
    // content — reported up rather than drawn here so it can bleed past this screen's own bounds.
    LaunchedEffect(accentColor) { onAccentColorChanged(accentColor) }

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
 * The header art's muted average color, re-derived whenever the art itself changes. Coil already
 * holds the image in its memory cache from [HeaderArt]'s own load, so this is a decode, not a
 * second network fetch in practice.
 */
@Composable
private fun rememberHeaderAccentColor(headerUrl: String): State<Color?> {
    val context = LocalContext.current
    return produceState<Color?>(initialValue = null, headerUrl) {
        value = if (headerUrl.isBlank()) null else loadAverageColor(context, headerUrl)
    }
}

private suspend fun loadAverageColor(context: Context, url: String): Color? {
    val request = ImageRequest.Builder(context)
        .data(url)
        // Palette math needs readable pixels; hardware bitmaps don't allow that.
        .allowHardware(false)
        .build()
    val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
        ?: return null
    return averageColor(bitmap).mutedForBackdrop()
}

/**
 * Downsamples to a handful of pixels and averages them — a dominant-color estimate good enough
 * for a background wash, without pulling in a palette library for one number.
 */
private fun averageColor(bitmap: Bitmap): Color {
    val sample = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
    val pixels = IntArray(sample.width * sample.height)
    sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
    var r = 0L
    var g = 0L
    var b = 0L
    pixels.forEach { pixel ->
        r += android.graphics.Color.red(pixel)
        g += android.graphics.Color.green(pixel)
        b += android.graphics.Color.blue(pixel)
    }
    val n = pixels.size
    return Color(red = (r / n) / 255f, green = (g / n) / 255f, blue = (b / n) / 255f)
}

/**
 * Caps lightness so the backdrop always reads as a tint rather than a bright wash, but otherwise
 * leaves saturation mostly alone — a vivid box-art color should still read as that color, just
 * dimmed enough to sit behind the dark navy/gold theme the rest of the app commits to.
 */
private fun Color.mutedForBackdrop(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = hsv[2].coerceIn(0.22f, 0.48f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
                ActivePlayersLine(summary)
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
 * The game's current Steam concurrent-player count, fetched once when the screen opens. Renders
 * nothing while the fetch is in flight or if it fails — no zero, no dash, no spinner — the same
 * omit-rather-than-placeholder treatment [HltbLengths] gives an unresolved length.
 */
@Composable
private fun ActivePlayersLine(summary: GameSummaryUi) {
    val count = summary.activePlayers ?: return
    Text(
        text = "${UiFormat.count(count)} playing now",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            AchievementIcon(achievement.apiName, achievement.iconUrl, achievement.tier)
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

/**
 * The achievement icon, haloed in its rarity tier's color when unlocked and tierable — Steam's own
 * "shiny" achievement treatment, reimagined per tier here rather than one fixed shine. No halo for
 * locked achievements: a halo signals an earned tier, and a locked row has none to show.
 *
 * Three layers, largest first: an unclipped ambient [HaloBleed] that spills past the icon into the
 * row itself (a halo *surrounds* something; a glow confined to the icon's own footprint doesn't), a
 * [RarityPlate] just past the icon's edge carrying the actual tier color plus the animated shimmer,
 * then the icon glyph on top. Earlier attempts put the color gradient's bright center directly
 * behind the icon — which hid it completely, since the icon is opaque — leaving only the gradient's
 * darkest, near-black outer stop visible as a dim ring indistinguishable between tiers. The fix is
 * ordering: put the vivid color in the ring that is actually visible, not the part the icon covers.
 */
@Composable
private fun AchievementIcon(apiName: String, iconUrl: String?, tier: RarityTier?) {
    Box(modifier = Modifier.size(HALO_BLEED_SIZE), contentAlignment = Alignment.Center) {
        if (tier != null) {
            val color = MaterialTheme.colorScheme.rarityHalo(tier)
            HaloBleed(color)
            Box(modifier = Modifier.size(RARITY_PLATE_SIZE), contentAlignment = Alignment.Center) {
                RarityPlate(color, phaseSeed = apiName.hashCode())
            }
        }
        AchievementIconGlyph(iconUrl)
    }
}

/**
 * Ambient glow that bleeds past the plate's own edge into the row around it — soft, low-alpha,
 * fading fully to transparent well before this box's own bounds so it needs no clip.
 */
@Composable
private fun BoxScope.HaloBleed(color: Color) {
    val brush = remember(color) {
        Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = 0.55f),
                0.55f to color.copy(alpha = 0.25f),
                1f to color.copy(alpha = 0f),
            ),
        )
    }
    Box(modifier = Modifier.matchParentSize().drawBehind { drawRect(brush = brush) })
}

/**
 * The colored ring actually visible around the icon (the plate's center sits behind the opaque
 * icon and is never seen), plus a diagonal shine sweeping across it on a loop — the animation is
 * what actually reads as "shiny" the way Steam's own showcase does; a static ring alone is just a
 * colored border.
 *
 * [phaseSeed] staggers each row's shimmer against every other row's — without it, every achievement
 * on screen sweeps in perfect unison, which looks like a strobing wall rather than individual icons
 * catching the light.
 */
@Composable
private fun BoxScope.RarityPlate(color: Color, phaseSeed: Int) {
    val plateBrush = remember(color) { rarityPlateBrush(color) }
    val shimmerProgress by rememberInfiniteTransition(label = "achievementHalo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
            initialStartOffset = StartOffset(phaseSeed.mod(SHIMMER_DURATION_MS)),
        ),
        label = "shimmerProgress",
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                drawRect(brush = plateBrush)
                drawShimmerSweep(shimmerProgress)
            },
    )
}

/**
 * Full tier color across the whole plate, lit by a brighter band partway to the edge — deliberately
 * never darkens toward black. Only the outer ~30% of this radius (past the icon's own edge) is ever
 * seen, so that ring has to *be* the tier color at full strength, not fade away from it.
 */
private fun rarityPlateBrush(base: Color): Brush {
    val bright = lerp(base, Color.White, 0.5f)
    return Brush.radialGradient(
        colorStops = arrayOf(
            0f to base,
            0.72f to base,
            0.88f to bright,
            1f to base,
        ),
    )
}

/** A diagonal light band traveling across the plate once per [progress] cycle (0f..1f). */
private fun DrawScope.drawShimmerSweep(progress: Float) {
    val band = size.maxDimension * 0.6f
    val travel = size.width + size.height + band * 2f
    val lead = -band + progress * travel
    drawRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.6f),
                1f to Color.Transparent,
            ),
            start = Offset(lead, 0f),
            end = Offset(lead + band, size.height),
        ),
    )
}

/** How long one shimmer sweep takes to cross the plate. */
private const val SHIMMER_DURATION_MS = 2400

/** The ring around the icon: bigger than the 40dp icon so its outer band is actually visible. */
private val RARITY_PLATE_SIZE = 54.dp

/** The outermost ambient glow, bigger again so it visibly spills past the ring into the row. */
private val HALO_BLEED_SIZE = 68.dp

@Composable
private fun AchievementIconGlyph(iconUrl: String?) {
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

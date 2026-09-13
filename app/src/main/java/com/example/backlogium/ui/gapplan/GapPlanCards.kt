package com.example.backlogium.ui.gapplan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.ui.components.GameIcon
import compose.icons.TablerIcons
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.Heart
import compose.icons.tablericons.Plus
import compose.icons.tablericons.ThumbUp
import compose.icons.tablericons.Users

/**
 * One recommendation.
 *
 * Outlined rather than filled, because the card is the touch target: a bounded region the player
 * can see before they touch it, instead of a highlight that materialises out of flat background
 * the moment they do.
 *
 * Everything here is either a number beside its icon or a bar. The earlier version stated each
 * figure as its own sentence — the tier's rule, its share, its withheld remainder, the pick's
 * remaining time, its progress — which was individually defensible and collectively unreadable,
 * and pushed the third recommendation off the screen entirely.
 */
@Composable
internal fun GapPlanPickCard(
    pick: GapPlanPickUi,
    fullCapacityMinutes: Int,
    onInspect: (Long) -> Unit,
    onSave: () -> Unit,
) {
    val game = pick.game
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(pickTag(pick.intensity))
            // Clipped *before* clickable, so the press ripple takes the card's rounded shape. Left
            // unclipped it spreads as a rectangle over a flat background, which is the square
            // highlight that appeared out of nowhere on touch.
            .clip(shape)
            .then(
                if (game != null) Modifier.clickable { onInspect(game.appId) } else Modifier,
            ),
        shape = shape,
        colors = CardDefaults.outlinedCardColors(),
        // The visible bound. Stated explicitly rather than left to a filled card's tonal shift,
        // which on this dark surface is close to invisible until it is pressed.
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            GapPlanTierRow(pick = pick, canSave = game != null, onSave = onSave)
            CapacityBar(
                pickMinutes = pick.plannedMinutes,
                shareMinutes = pick.budgetMinutes,
                fullCapacityMinutes = fullCapacityMinutes,
                modifier = Modifier.testTag(shareTag(pick.intensity)),
            )

            if (game == null) {
                Text(
                    text = GapPlanPresentation.emptyPickMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(emptyTag(pick.intensity)),
                )
            } else {
                Text(
                    text = GapPlanPresentation.recommendationHook(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(hookTag(pick.intensity)),
                )
                GapPlanGameRow(game)
            }
        }
    }
}

/** The tier, its share as a bare percent, and the one action that commits it. */
@Composable
private fun GapPlanTierRow(pick: GapPlanPickUi, canSave: Boolean, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = GapPlanPresentation.intensityName(pick.intensity),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = GapPlanPresentation.intensityShare(pick.intensity),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = GapPlanPresentation.pickAgainstShare(pick),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (canSave) {
            IconButton(
                onClick = onSave,
                modifier = Modifier
                    .size(32.dp)
                    .testTag(saveTag(pick.intensity)),
            ) {
                Icon(
                    TablerIcons.Plus,
                    contentDescription = "Save as collection",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The game, and the facts that let the player judge it — each one an icon and a value. */
@Composable
private fun GapPlanGameRow(game: GapPlanGameUi) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(gameTag(game.appId)),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GameIcon(iconUrl = game.iconUrl, iconSize = 40.dp)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (game.isFamilyShared) {
                    Text(
                        text = "Shared",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag(familySharedTag(game.appId)),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconValue(TablerIcons.Clock, GapPlanPresentation.remaining(game))
                GapPlanPresentation.genreLine(game)?.let { genres ->
                    IconValue(
                        icon = TablerIcons.DeviceGamepad,
                        value = genres,
                        modifier = Modifier.testTag(genreTag(game.appId)),
                    )
                }
                game.facts.forEach { fact -> GameFact(fact, game.appId) }
            }

            // Progress is a proportion, so it is drawn. The caption states only the part the bar
            // cannot: how much of it is already done.
            game.facts.filterIsInstance<GapPlanFact.Progress>().firstOrNull()?.let { progress ->
                ProgressBar(
                    playedMinutes = progress.playedMinutes,
                    estimateMinutes = progress.estimateMinutes,
                    modifier = Modifier.testTag(progressTag(game.appId)),
                )
            }
        }
    }
}

/** One fact, as its icon and its shortest unambiguous value. */
@Composable
private fun GameFact(fact: GapPlanFact, appId: Long) {
    when (fact) {
        is GapPlanFact.Reviews -> IconValue(
            icon = TablerIcons.ThumbUp,
            value = GapPlanPresentation.reviewLabel(fact),
            tint = GapPlanPresentation.reviewStanding(fact)?.color(),
            modifier = Modifier.testTag(reviewTag(appId)),
        )

        is GapPlanFact.PlayingNow -> IconValue(
            icon = TablerIcons.Users,
            value = GapPlanPresentation.playerCountLabel(fact),
            modifier = Modifier.testTag(playersTag(appId)),
        )

        // The genre the player has actually been playing is the point of the statement, so it
        // carries the emphasis rather than the words around it.
        is GapPlanFact.GenreAffinity -> {
            // Read outside the builder: the colour scheme is a composable lookup, and the
            // AnnotatedString lambda is not a composable scope.
            val accent = MaterialTheme.colorScheme.tertiary
            IconValue(
                icon = TablerIcons.Heart,
                annotated = buildAnnotatedString {
                    append("Lately: ")
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                        append(fact.genreLabel)
                    }
                },
                modifier = Modifier.testTag(affinityTag(appId)),
            )
        }

        // Drawn as a bar instead, beneath this row.
        is GapPlanFact.Progress -> Unit
    }
}

/**
 * An icon and its value, as one unit.
 *
 * The icon is what makes the row readable at a glance: "677K" beside a thumb means something the
 * sentence "Very Positive (676,762 reviews)" took eight times the width to say.
 */
@Composable
private fun IconValue(
    icon: ImageVector,
    value: String? = null,
    annotated: AnnotatedString? = null,
    tint: Color? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val style = MaterialTheme.typography.labelMedium
        val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
        if (annotated != null) {
            Text(annotated, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(value.orEmpty(), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The tier's share of the forecast, the time it withholds, and the pick's own length — one bar.
 *
 * The track is the whole forecast. The lighter segment runs to this tier's share, and the solid
 * fill to the pick's length; whatever is left of the track is the capacity the intensity
 * deliberately withheld. Three sentences became one row, and the withheld remainder became
 * something the player sees rather than something they subtract.
 */
@Composable
private fun CapacityBar(
    pickMinutes: Int,
    shareMinutes: Int,
    fullCapacityMinutes: Int,
    modifier: Modifier = Modifier,
) {
    val total = fullCapacityMinutes.coerceAtLeast(1)
    val shareFraction = (shareMinutes.toFloat() / total).coerceIn(0f, 1f)
    val pickFraction = (pickMinutes.toFloat() / total).coerceIn(0f, shareFraction)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shareFraction)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = SHARE_ALPHA)),
        )
        Box(
            Modifier
                .fillMaxWidth(pickFraction)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** How far through the selected estimate an already-started game is. */
@Composable
private fun ProgressBar(playedMinutes: Int, estimateMinutes: Int, modifier: Modifier = Modifier) {
    val fraction = if (estimateMinutes <= 0) {
        0f
    } else {
        (playedMinutes.toFloat() / estimateMinutes).coerceIn(0f, 1f)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

/**
 * Steam's own three bands, in Steam's own colours, so a player who knows the store reads this
 * without being taught it. The standing behind the colour comes from the review ratio, never from
 * the localized description — see [GapPlanPresentation.reviewStanding].
 */
private fun ReviewStanding.color(): Color = when (this) {
    ReviewStanding.POSITIVE -> Color(0xFF66C0F4)
    ReviewStanding.MIXED -> Color(0xFFB9A074)
    ReviewStanding.NEGATIVE -> Color(0xFFC15B43)
}

/** How much of the track the tier's own share occupies behind the pick's fill. */
private const val SHARE_ALPHA = 0.28f

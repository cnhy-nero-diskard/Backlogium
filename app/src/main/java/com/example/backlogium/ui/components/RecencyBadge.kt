package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.backlogium.domain.GameRecencyState
import compose.icons.TablerIcons
import compose.icons.tablericons.History
import compose.icons.tablericons.Rotate
import compose.icons.tablericons.Stars

/**
 * The corner glyph for a game's recency state, or nothing at all when it carries none
 * (add-library-recency-signals).
 *
 * **Icon-only, deliberately.** A labelled pill cannot survive the compact grid, where a
 * three-column tile has one truncated line of text — and a signal that disappears at the density
 * where the most games are being scanned is a signal that fails exactly when it matters. So the
 * badge does not join the `GameListField` density ladder: that ladder governs *detail*, and recency
 * is a live-ish signal like currently-playing, which the density rules already exempt on the same
 * reasoning.
 *
 * The chip behind the glyph is not decoration: these are drawn over Steam artwork of arbitrary
 * brightness, and a bare icon disappears against a light capsule.
 *
 * Callers position it themselves — `Modifier.align(...)` plus an inset — because the free corner
 * differs per surface and the reason belongs at the call site: a grid cell's `TopStart` already
 * holds the selection indicator, while a list row's icon carries the currently-playing dot at
 * `TopEnd` and the HowLongToBeat status at `BottomEnd`.
 */
@Composable
fun RecencyBadge(
    state: GameRecencyState?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    if (state == null) return
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = state.glyph,
            // Named rather than decorative: three glyphs is close to the limit of a learnable
            // vocabulary even when they are visible, and none of it is available by sight to a
            // screen-reader user.
            contentDescription = state.accessibilityLabel,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.7f),
        )
    }
}

/** Glyph per state. One mapping, so no surface can invent a fourth meaning for a third symbol. */
private val GameRecencyState.glyph: ImageVector
    get() = when (this) {
        // Tabler has no `Sparkles` at the version this project pins; `Stars` is its sparkle glyph.
        GameRecencyState.NEWLY_ADDED -> TablerIcons.Stars
        // A history arrow describes a recent interaction without reading like an action button.
        GameRecencyState.NEWLY_PLAYED -> TablerIcons.History
        GameRecencyState.RETURNED -> TablerIcons.Rotate
    }

/**
 * Accessibility name per state, phrased as the player would describe the game.
 *
 * Named for its purpose rather than as a bare `label`, because surfaces that already import an
 * unrelated `label` extension (Home imports the collection-mode one) would otherwise be resolving
 * two same-named extensions by receiver type alone.
 */
val GameRecencyState.accessibilityLabel: String
    get() = when (this) {
        GameRecencyState.NEWLY_ADDED -> "Newly added"
        GameRecencyState.NEWLY_PLAYED -> "Newly played"
        GameRecencyState.RETURNED -> "Returned to play"
    }

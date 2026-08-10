package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad

/**
 * A game's thumbnail icon, themed while loading and on failure. The default remains a small rounded
 * square, while compact rows can opt into another shape without changing full-size callers. Shared
 * between the Library (where it originated) and History's day-grouped game rows (regroup-history) —
 * both need the same "icon that never looks broken" treatment for a Steam CDN thumbnail.
 */
@Composable
fun GameIcon(
    iconUrl: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = modifier
            .size(iconSize)
            .clip(shape),
        // Themed placeholder while the Steam CDN thumbnail loads.
        loading = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        // Themed fallback (generic controller glyph) when the image fails to load.
        error = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TablerIcons.DeviceGamepad,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    )
}

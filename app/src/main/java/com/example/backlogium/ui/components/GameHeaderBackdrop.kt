package com.example.backlogium.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/** Shared right-aligned Steam header-art treatment for Library and Collection game cards. */
@Composable
fun GameHeaderBackdrop(
    headerUrl: String,
    modifier: Modifier = Modifier,
) {
    if (headerUrl.isBlank()) return
    AsyncImage(
        model = headerUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.CenterEnd,
        modifier = modifier
            .graphicsLayer {
                alpha = HEADER_ART_ALPHA
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        HEADER_ART_FADE_END to Color.Black,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

private const val HEADER_ART_ALPHA = 0.22f
private const val HEADER_ART_FADE_END = 0.95f

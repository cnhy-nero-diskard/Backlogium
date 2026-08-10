package com.example.backlogium.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage

/** Shared right-aligned Steam header-art treatment for Library and Collection game cards. */
@Composable
fun GameHeaderBackdrop(
    headerUrl: String,
    fallbackUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    SteamArtworkWithFallback(
        urls = listOf(headerUrl) + fallbackUrls,
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
        loading = {},
        failure = {},
    )
}

/**
 * Attempts each non-blank Steam asset in order. Coil's error callback advances to the next URL,
 * keeping a transient CDN 404 from becoming the final UI state. Callers own the themed content
 * rendered after every candidate fails.
 */
@Composable
internal fun SteamArtworkWithFallback(
    urls: List<String>,
    contentScale: ContentScale,
    alignment: Alignment,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit,
    failure: @Composable () -> Unit,
) {
    val candidates = remember(urls) {
        urls.filter(String::isNotBlank).distinct()
    }
    var attempt by remember(candidates) { mutableStateOf(0) }
    val currentUrl = candidates.getOrNull(attempt)
    if (currentUrl == null) {
        failure()
        return
    }

    SubcomposeAsyncImage(
        model = currentUrl,
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
        loading = { loading() },
        error = { failure() },
        onError = {
            attempt = if (attempt < candidates.lastIndex) attempt + 1 else candidates.size
        },
    )
}

private const val HEADER_ART_ALPHA = 0.22f
private const val HEADER_ART_FADE_END = 0.95f

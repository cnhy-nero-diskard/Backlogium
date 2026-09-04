package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage

/**
 * Shared right-aligned Steam header-art treatment for Library and Collection game cards.
 *
 * Achieves the faint, fades-toward-black look with a plain [Modifier.alpha] dim plus a solid
 * gradient scrim drawn as a sibling on top, rather than a `drawWithContent` +
 * `BlendMode.DstIn` blend inside a `CompositingStrategy.Offscreen` layer. That combination is a
 * rarer, more fragile Compose pattern than a plain alpha modifier — asked to isolate and
 * recomposite an async-loading child, it could leave a cold (never-cached) image's offscreen
 * layer stuck on its pre-load, empty state even after the image finishes loading. A freshly
 * admitted family-shared game's art is exactly a cold load the very first time this card renders,
 * while an owned game's has usually been in Coil's cache for a while — which would explain a bug
 * reported as "family-shared games show no card art at all, owned games are fine"
 * (add-shared-game-playtime-and-filter follow-up). The overlay-scrim approach never asks Compose
 * to redraw a cached offscreen bitmap on a state it didn't structurally recompose for.
 */
@Composable
fun GameHeaderBackdrop(
    headerUrl: String,
    fallbackUrls: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        SteamArtworkWithFallback(
            urls = listOf(headerUrl) + fallbackUrls,
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            modifier = Modifier.matchParentSize().alpha(HEADER_ART_ALPHA),
            loading = {},
            failure = {},
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        HEADER_ART_FADE_END to Color.Black,
                    ),
                ),
        )
    }
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

package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
        // Themed rather than blank: every candidate exhausted still leaves a deliberate muted
        // panel behind the row's text instead of a jarring gap (add-shared-game-playtime-and-filter
        // follow-up — this is where family-shared games were reported to show no art at all).
        failure = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
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

/**
 * Confirmed on-device (add-shared-game-playtime-and-filter follow-up): the art was never failing
 * to load — `adb screencap` showed it rendering at the old 0.22 value — it was below what a human
 * eye can distinguish on an actual phone screen for dark-toned box art (God of War, Black Myth:
 * Wukong) against an already-dark card, especially on OLED where near-black tones compress
 * together. Owned games with brighter art happened to stay legible at the old value, masking the
 * issue for them. Raised until visible at normal viewing brightness on a real device.
 */
private const val HEADER_ART_ALPHA = 0.45f
private const val HEADER_ART_FADE_END = 0.95f

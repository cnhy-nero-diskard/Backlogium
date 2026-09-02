package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbCandidateSource
import com.example.backlogium.data.hltb.HltbRoutes
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.ExternalLink

/** Shared length formatting, also used by the inline picker. */
@Composable
fun HltbLengthsRow(candidate: HltbCandidate, modifier: Modifier = Modifier) {
    val lengths = buildList {
        candidate.mainStoryMinutes?.let { add("Main: ${UiFormat.minutes(it)}") }
        candidate.mainExtraMinutes?.let { add("Extra: ${UiFormat.minutes(it)}") }
        candidate.completionistMinutes?.let { add("Complete: ${UiFormat.minutes(it)}") }
        candidate.allStylesMinutes?.let { add("All: ${UiFormat.minutes(it)}") }
    }
    if (lengths.isEmpty()) {
        Text(
            "No completion lengths",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lengths.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun hltbCandidateContentDescription(candidate: HltbCandidate): String =
    "${candidate.name}, ${candidate.mainStoryMinutes?.let { "Main ${UiFormat.minutes(it)}" } ?: "no Main"}"

fun hltbLengthLabel(candidate: HltbCandidate): String {
    val parts = mutableListOf<String>()
    candidate.mainStoryMinutes?.let { parts += "Main ${UiFormat.minutes(it)}" }
    candidate.mainExtraMinutes?.let { parts += "Main+Extra ${UiFormat.minutes(it)}" }
    candidate.completionistMinutes?.let { parts += "Completionist ${UiFormat.minutes(it)}" }
    candidate.allStylesMinutes?.let { parts += "All Styles ${UiFormat.minutes(it)}" }
    return if (parts.isEmpty()) "No lengths" else parts.joinToString(", ")
}

/**
 * Adaptive HLTB candidate card with larger cover art, themed fixed-geometry fallback,
 * name, available lengths, provenance/confidence guidance, and an explicit selection
 * action (labeled by [selectionLabel]). The external HLTB link is a separate click target
 * that cannot invoke match selection.
 */
@Composable
fun HltbCandidateCard(
    candidate: HltbCandidate,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    selectionLabel: String = "Use match",
    showSelectionButton: Boolean = true,
) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // Larger portrait cover (80x120) with themed fixed-geometry fallback
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val imageUrl = candidate.imageUrl.orEmpty()
                    if (imageUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = imageUrl,
                            contentDescription = "Cover for ${candidate.name}",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            },
                            error = {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        TablerIcons.DeviceGamepad,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            },
                        )
                    } else {
                        Icon(
                            TablerIcons.DeviceGamepad,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = hltbCandidateContentDescription(candidate) },
                    )
                    Spacer(Modifier.height(4.dp))
                    HltbLengthsRow(candidate)
                    Spacer(Modifier.height(4.dp))
                    // Provenance / confidence guidance (secondary, never proof)
                    val guidance = when (candidate.source) {
                        HltbCandidateSource.BROADER_SEARCH -> "Broader search — verify manually"
                        HltbCandidateSource.MANUAL_LINK -> "From pasted HLTB link"
                        else -> if (candidate.confidence > 0) "Match ${(candidate.confidence * 100).toInt()}%" else null
                    }
                    guidance?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Separate external HLTB link (isolated click target)
                IconButton(
                    onClick = {
                        val url = HltbRoutes.canonicalGameUrl(candidate.hltbId)
                        uriHandler.openUri(url)
                    },
                    modifier = Modifier.semantics { contentDescription = "Open HLTB page for ${candidate.name}" },
                ) {
                    Icon(TablerIcons.ExternalLink, contentDescription = "Open HLTB page for ${candidate.name}")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (showSelectionButton) {
                Button(
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$selectionLabel for ${candidate.name}" },
                ) {
                    Text(selectionLabel)
                }
            }
        }
    }
}

/** Placeholder used when cover fails to load, preserving fixed geometry. */
@Composable
fun HltbCoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TablerIcons.DeviceGamepad,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
    }
}

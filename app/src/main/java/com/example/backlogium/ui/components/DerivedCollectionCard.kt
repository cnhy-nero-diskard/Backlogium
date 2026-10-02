package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.domain.DerivedCollectionArtwork

/** Shared read-only card; flexible text remains readable at large font scale. */
@Composable
fun DerivedCollectionCard(name: String, rule: String, memberCount: Int,
    artwork: List<DerivedCollectionArtwork>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.semantics {
        contentDescription = "Open $name derived collection, $memberCount ${if (memberCount == 1) "game" else "games"}, read-only"
    }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (artwork.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                artwork.take(3).forEach { cover ->
                    val coverModifier = Modifier.weight(1f).aspectRatio(2.1f).clip(RoundedCornerShape(8.dp))
                    val placeholder: @Composable () -> Unit = {
                        Box(coverModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    }
                    SteamArtworkWithFallback(listOf(cover.headerUrl) + SteamIconMapper.coverUrls(cover.appId),
                        ContentScale.Crop, Alignment.Center, coverModifier, loading = placeholder, failure = placeholder)
                }
            }
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("$memberCount ${if (memberCount == 1) "game" else "games"} · Read-only",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

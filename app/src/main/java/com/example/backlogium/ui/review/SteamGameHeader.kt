package com.example.backlogium.ui.review

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.hltb.HltbRoutes
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.hltb.SteamRoutes
import com.example.backlogium.ui.components.GameIcon
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.ExternalLink

@Composable
fun SteamGameHeader(
    game: MatchCenterGameUi,
    position: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon or artwork (48dp, with Steam fallback chain)
            if (game.iconUrl.isNotBlank()) {
                GameIcon(iconUrl = game.iconUrl, iconSize = 48.dp)
            } else {
                // Fallback to header art placeholder
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TablerIcons.DeviceGamepad,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                val statusLabel = when (game.matchStatus) {
                    HltbMatchStatus.NEEDS_REVIEW -> "Needs review"
                    HltbMatchStatus.UNMATCHED -> "No match"
                    HltbMatchStatus.RESOLVED -> "Resolved"
                    else -> game.matchStatus.name
                }
                Text(
                    text = "$statusLabel • $position / $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { uriHandler.openUri(SteamRoutes.storeUrl(game.appId)) },
            ) {
                Icon(TablerIcons.ExternalLink, contentDescription = "Open Steam Store page for ${game.name}")
            }
        }
    }
}

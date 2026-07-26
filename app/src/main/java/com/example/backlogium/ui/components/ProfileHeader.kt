package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.data.repo.LivePresence
import com.example.backlogium.ui.shell.ProfileHeaderViewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.User

/** Label shown when no persona name has been synced yet — neutral, never the raw SteamID. */
private const val FALLBACK_NAME = "Steam player"

private val AVATAR_SIZE = 36.dp

/**
 * The app shell's persistent identity strip: avatar, persona name, and live presence. Identity
 * renders from persisted state, so a cold offline launch is already populated rather than blank.
 *
 * Renders nothing while credentials are unconfigured or still loading — Home replaces itself with
 * the onboarding takeover in that state, and a skeleton avatar above it would just be noise.
 * Deliberately carries no level number: the app's own XP level is Home's, and a second unrelated
 * number here would read as a contradiction.
 */
@Composable
fun ProfileHeader(viewModel: ProfileHeaderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.visible) return

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The app is edge-to-edge and Scaffold hands the top inset to whatever occupies
                // `topBar`, so the header consumes it here (as TopAppBar would).
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(state.avatarUrl)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = state.personaName?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                presenceLabel(state.presence)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Circular avatar. Steam's CDN can 404 after an avatar change, so loading and error both fall
 * back to a themed glyph rather than collapsing the row.
 */
@Composable
private fun Avatar(avatarUrl: String?) {
    val modifier = Modifier
        .size(AVATAR_SIZE)
        .clip(CircleShape)
    if (avatarUrl.isNullOrBlank()) {
        Box(modifier) { AvatarFallback() }
    } else {
        SubcomposeAsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = modifier,
            error = { AvatarFallback() },
            loading = { AvatarFallback() },
        )
    }
}

@Composable
private fun AvatarFallback() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TablerIcons.User,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Null for [LivePresence.UNKNOWN]: before the first poll returns, claiming a state would lie. */
private fun presenceLabel(presence: LivePresence): String? = when (presence) {
    LivePresence.UNKNOWN -> null
    LivePresence.OFFLINE -> "Offline"
    LivePresence.ONLINE -> "Online"
    LivePresence.IN_GAME -> "In game"
}

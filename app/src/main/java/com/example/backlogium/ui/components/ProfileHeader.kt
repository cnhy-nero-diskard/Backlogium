package com.example.backlogium.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.data.repo.LivePresence
import com.example.backlogium.ui.shell.ProfileHeaderViewModel
import com.example.backlogium.ui.theme.playingIndicator
import com.example.backlogium.ui.util.rememberReducedMotion
import compose.icons.TablerIcons
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.User

/** Label shown when no persona name has been synced yet — neutral, never the raw SteamID. */
private const val FALLBACK_NAME = "Steam player"

private val AVATAR_SIZE = 36.dp

/** One full turn of the sync glyph; slow enough to read as "working", not as "urgent". */
private const val SYNC_SPIN_MILLIS = 1200

/**
 * The app shell's persistent identity strip: avatar, persona name, and live presence. Identity
 * renders from persisted state, so a cold offline launch is already populated rather than blank.
 *
 * Renders nothing while credentials are unconfigured or still loading — Home replaces itself with
 * the onboarding takeover in that state, and a skeleton avatar above it would just be noise.
 * Deliberately carries no level number: the app's own XP level is Home's, and a second unrelated
 * number here would read as a contradiction.
 *
 * [transparent] drops the strip's own surface color so a backdrop painted behind the whole shell
 * (the game detail screen's header-art wash) shows through it rather than being cut off at its
 * bottom edge — the header stays laid out identically either way. [onHome] hides only the running
 * game's name because Home's now-playing panel immediately below already identifies it.
 */
@Composable
fun ProfileHeader(
    viewModel: ProfileHeaderViewModel = hiltViewModel(),
    transparent: Boolean = false,
    onHome: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (!state.visible) return

    // contentColor is spelled out rather than left to Surface's default: Surface only infers it
    // for known scheme colors, and Color.Transparent isn't one — left implicit, every text/icon
    // in the strip would silently fall back to plain black instead of the theme's onSurface.
    Surface(
        color = if (transparent) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
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
            // The identity column takes the slack so a long persona name still ellipsizes
            // rather than pushing the trailing indicator off the row.
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.personaName?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                presenceLabel(
                    presence = state.presence,
                    gameName = state.gameName,
                    showGameName = !onHome,
                )?.let { label ->
                    Text(
                        text = label.toAnnotatedString(MaterialTheme.colorScheme.playingIndicator),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // Composed only while syncing, so idle costs no layout at all.
            if (state.syncing) {
                Spacer(Modifier.width(12.dp))
                SyncIndicator()
            }
        }
    }
}

/**
 * The shell's "talking to Steam" cue, shown on the trailing edge of the header for every sync —
 * scheduled or manual. Under a reduced-motion preference the same glyph is drawn static rather
 * than dropped: motion is the emphasis here, never the only thing carrying the state (the
 * content description says so regardless).
 */
@Composable
private fun SyncIndicator() {
    val reducedMotion = rememberReducedMotion()
    val rotation = if (reducedMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "sync")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(SYNC_SPIN_MILLIS, easing = LinearEasing),
            ),
            label = "syncRotation",
        )
        angle
    }

    Icon(
        imageVector = TablerIcons.Refresh,
        contentDescription = "Syncing",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(20.dp)
            .rotate(rotation),
    )
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

/** The text and range that identifies the currently-playing game without making color carry it. */
internal data class PresenceLabel(
    val text: String,
    val gameNameStart: Int? = null,
)

/** Null for [LivePresence.UNKNOWN]: before the first poll returns, claiming a state would lie. */
internal fun presenceLabel(
    presence: LivePresence,
    gameName: String?,
    showGameName: Boolean,
): PresenceLabel? = when (presence) {
    LivePresence.UNKNOWN -> null
    LivePresence.OFFLINE -> PresenceLabel("Offline")
    LivePresence.ONLINE -> PresenceLabel("Online")
    LivePresence.IN_GAME -> {
        val resolvedName = gameName?.takeIf { it.isNotBlank() }
        if (showGameName && resolvedName != null) {
            val prefix = "In game · "
            PresenceLabel(
                text = prefix + resolvedName,
                gameNameStart = prefix.length,
            )
        } else {
            PresenceLabel("In game")
        }
    }
}

private fun PresenceLabel.toAnnotatedString(gameNameColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        gameNameStart?.let { start ->
            addStyle(
                style = SpanStyle(color = gameNameColor),
                start = start,
                end = text.length,
            )
        }
    }

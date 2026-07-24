package com.example.backlogium.ui.home

import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.backlogium.R
import com.example.backlogium.domain.isStreakMilestone
import com.example.backlogium.ui.onboarding.OnboardingScreen
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.BrandSteam
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.Download
import compose.icons.tablericons.Flame
import compose.icons.tablericons.Pencil

@Composable
fun HomeScreen(
    onEditCredentials: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.loading) return

    if (!state.configured) {
        // Full-screen onboarding takeover replaces the old dead-end "not configured" message.
        // Completion flips credentialsStateFlow, so this screen recomposes to the configured
        // content automatically — no explicit navigation needed here.
        OnboardingScreen(onCompleted = {})
        return
    }

    // Level-up detection (design decision 6): HomeUiState carries no "previous level", so an
    // increment is detected in Compose state only. Seeding the remembered value from the
    // *current* level on first composition means a cold start never fires a false increment.
    var lastLevel by remember { mutableStateOf(state.level) }
    var playLevelUp by remember { mutableStateOf(false) }
    LaunchedEffect(state.level) {
        if (state.level > lastLevel) playLevelUp = true
        lastLevel = state.level
    }

    // Streak-milestone detection: fire only when the streak *changes* to a positive multiple
    // of 7 (STREAK_MILESTONE_INTERVAL_DAYS). The change-guard keeps it from replaying on every
    // recomposition/navigation while sitting at the same milestone value (task 7.5).
    var lastStreak by remember { mutableStateOf(state.currentStreak) }
    var playStreakMilestone by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentStreak) {
        if (state.currentStreak != lastStreak && isStreakMilestone(state.currentStreak)) {
            playStreakMilestone = true
        }
        lastStreak = state.currentStreak
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // "Now playing" banner: conditionally composed so it adds no layout when not in-game.
        val nowPlayingName = state.nowPlayingName
        if (state.isInGame && nowPlayingName != null) {
            NowPlayingBanner(
                name = nowPlayingName,
                iconUrl = state.nowPlayingIconUrl,
            )
        }

        state.lastSyncError?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        // Level + XP.
        Card(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Level ${state.level}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.xpFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${state.xpIntoLevel} / ${state.xpForNext} XP to next level " +
                            "· ${state.totalXp} total",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CelebrationAnimation(
                    resId = R.raw.levelup,
                    play = playLevelUp,
                    onFinished = { playLevelUp = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(72.dp),
                )
            }
        }

        // Today's quest.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Today's quest", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.questMet) TablerIcons.CircleCheck else TablerIcons.Clock,
                        contentDescription = null,
                        tint = if (state.questMet) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.questMet) "Complete" else "In progress",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = "${UiFormat.minutes(state.todayMinutes)} of " +
                        "${UiFormat.minutes(state.questThreshold)} played today",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Streak.
        Card(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Streak", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = TablerIcons.Flame,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${state.currentStreak} day${if (state.currentStreak == 1) "" else "s"}",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Text(
                        text = "Longest: ${state.longestStreak}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CelebrationAnimation(
                    resId = R.raw.streak_milestone,
                    play = playStreakMilestone,
                    onFinished = { playStreakMilestone = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(72.dp),
                )
            }
        }

        // Steam account: active id + masked key, with an action to reopen onboarding.
        SteamAccountCard(
            steamId = state.steamId,
            apiKeyMasked = state.apiKeyMasked,
            onEdit = onEditCredentials,
        )

        // Opt-in one-time Steam-history import.
        HistoryImportCard(
            imported = state.historyImported,
            importing = state.isImportingHistory,
            onImport = viewModel::importSteamHistory,
            onReset = viewModel::resetHistoryImport,
        )

        // Sync controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.isSyncing) {
                    "Syncing…"
                } else {
                    "Last sync: ${UiFormat.dateTime(state.lastSyncAt)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = viewModel::syncNow,
                enabled = !state.isSyncing,
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Sync now")
                }
            }
        }
    }
}

/**
 * Compact "Steam account" card shown while configured: the active SteamID64 and a masked API key,
 * plus an "Edit" action that reopens the onboarding flow to change credentials. The raw API key
 * never reaches this composable — [apiKeyMasked] is already redacted upstream.
 */
@Composable
private fun SteamAccountCard(
    steamId: String,
    apiKeyMasked: String,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.BrandSteam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Steam account", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "SteamID $steamId",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "API key $apiKeyMasked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) {
                Icon(TablerIcons.Pencil, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Edit")
            }
        }
    }
}

/**
 * One-time "Import Steam history" control (add-playtime-backfill). Before importing it offers
 * the action behind a confirmation that spells out the effect (counts past playtime toward XP,
 * one-time, matched games capped / unmatched counted in full). After importing it reflects the
 * completed state and offers a reset that undoes the import so it can be run again.
 */
@Composable
private fun HistoryImportCard(
    imported: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onReset: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Steam history", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (imported) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = TablerIcons.CircleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "History imported",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = "Past playtime already counts toward your XP.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = !importing,
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Reset import")
                    }
                }
            } else {
                Text(
                    text = "Count your pre-install Steam playtime toward XP. One-time only.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showConfirm = true },
                    enabled = !importing,
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = TablerIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Import Steam history")
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Import Steam history?") },
            text = {
                Text(
                    "This counts your past Steam playtime toward XP and can only be done once. " +
                        "Games matched to HowLongToBeat are capped by the usual taper; unmatched " +
                        "games count their full playtime.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onImport()
                    },
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset imported history?") },
            text = {
                Text(
                    "This removes imported past playtime from your XP; your level drops back to " +
                        "reflect tracked playtime only. Streaks and tracked sessions are kept, and " +
                        "you can import again afterward.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onReset()
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Compact "Now playing" banner shown only while the player is in-game. Displays the running
 * game's name, plus its icon when one is resolvable (name-only otherwise, with a themed
 * controller glyph in place of the missing art).
 */
@Composable
private fun NowPlayingBanner(name: String, iconUrl: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(8.dp)
            if (iconUrl != null) {
                SubcomposeAsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(shape),
                    error = { NowPlayingIconFallback() },
                    loading = { NowPlayingIconFallback() },
                )
            } else {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(shape),
                ) { NowPlayingIconFallback() }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingIconFallback() {
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
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Inline, one-shot celebratory animation. Plays a bundled Lottie asset exactly once each time
 * [play] transitions to true, then invokes [onFinished] so the caller can reset its trigger.
 * Renders nothing while idle so it never affects layout when not celebrating.
 */
@Composable
private fun CelebrationAnimation(
    @RawRes resId: Int,
    play: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = play,
        iterations = 1,
        restartOnPlay = true,
    )

    if (play) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = modifier,
        )
    }

    LaunchedEffect(play, progress) {
        if (play && progress >= 1f) onFinished()
    }
}

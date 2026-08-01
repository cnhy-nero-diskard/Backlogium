package com.example.backlogium.ui.home

import android.animation.ValueAnimator
import androidx.annotation.RawRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.Flame
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
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
        // "Now playing" card: conditionally composed so it adds no layout (and runs no
        // animation) when not in-game — the card is Home's most prominent element while it is.
        val nowPlayingName = state.nowPlayingName
        if (state.isInGame && nowPlayingName != null) {
            NowPlayingCard(
                name = nowPlayingName,
                iconUrl = state.nowPlayingIconUrl,
                sessionStartedAt = state.nowPlayingSessionStartedAt,
            )
        }

        // The one sync affordance Home keeps: the manual trigger lives in Settings now, but a
        // failure is exactly the case where an immediate retry matters, and sending the user
        // two taps away to find one would be the wrong answer. The card is driven by
        // `profile.lastSyncError`, which the worker clears on success, so a successful retry
        // makes it disappear on its own.
        state.lastSyncError?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        onClick = viewModel::syncNow,
                        // Same latched sync flow the header indicator uses, so a retry cannot
                        // be double-tapped into two overlapping polls.
                        enabled = !state.isSyncing,
                    ) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Retry")
                        }
                    }
                }
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
                            // While today's quest is still unmet, "N days" would read as if
                            // today already counts. "N-day streak" carries the same intact
                            // count without that implication; once met, the plain "days"
                            // phrasing applies exactly as it would for any other completed day.
                            text = if (state.questMet) {
                                "${state.currentStreak} day${if (state.currentStreak == 1) "" else "s"}"
                            } else {
                                "${state.currentStreak}-day streak"
                            },
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
    }
}

/**
 * The most visually prominent element on Home while the player is in-game: large game art, the
 * game's name, and a live elapsed-session timer, in the tertiary steel-blue lane — deliberately
 * not [MaterialTheme.colorScheme.primaryContainer] (gold), which stays reserved for milestone
 * moments (level-up, streak milestones, 100% completion). A slow, flowing gradient marks the
 * card as live rather than merely colored; the timer and the card's presence still carry that
 * meaning on their own when reduced-motion is on and the gradient renders statically.
 */
@Composable
private fun NowPlayingCard(name: String, iconUrl: String?, sessionStartedAt: Long?) {
    val elapsedMillis by rememberElapsedMillis(sessionStartedAt)
    val gradientOffset = rememberNowPlayingGradientOffset()
    val base = MaterialTheme.colorScheme.tertiaryContainer
    val sheen = MaterialTheme.colorScheme.tertiary
    val onContainer = MaterialTheme.colorScheme.onTertiaryContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val bandWidth = size.width * 0.7f
                val center = gradientOffset * size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to base,
                            0.5f to sheen.copy(alpha = 0.35f),
                            1f to base,
                        ),
                        start = Offset(center - bandWidth, 0f),
                        end = Offset(center + bandWidth, size.height),
                    ),
                )
            },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(12.dp)
            if (iconUrl != null) {
                SubcomposeAsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(shape),
                    error = { NowPlayingIconFallback() },
                    loading = { NowPlayingIconFallback() },
                )
            } else {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(shape),
                ) { NowPlayingIconFallback() }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                )
                Spacer(Modifier.height(2.dp))
                // "Playing for" reads as accumulated time since detection, not an exact launch
                // time — detection can lag the true start by up to the periodic sync's interval.
                Text(
                    text = "Playing for ${UiFormat.minutes((elapsedMillis / 60_000L).toInt())}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                )
            }
        }
    }
}

/** Elapsed time since [startedAt], ticking every second with no network involved. Zero when null. */
@Composable
private fun rememberElapsedMillis(startedAt: Long?): State<Long> {
    val elapsed = remember(startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        if (startedAt == null) return@LaunchedEffect
        while (isActive) {
            elapsed.longValue = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    return elapsed
}

/**
 * A slow, ambient sweep for the now-playing card's background — driven by an infinite transition
 * so it stops the moment the card leaves composition (i.e. the moment the player is no longer
 * in-game), and never runs at all otherwise. Honors the system's reduced-motion setting: with
 * animations disabled, the sweep holds at a fixed midpoint and the gradient renders statically.
 */
@Composable
private fun rememberNowPlayingGradientOffset(): Float {
    val animatorsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    if (!animatorsEnabled) return 0.5f

    val transition = rememberInfiniteTransition(label = "nowPlayingGradient")
    val offset by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offset",
    )
    return offset
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

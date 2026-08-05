package com.example.backlogium.ui.home

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.backlogium.R
import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.label
import com.example.backlogium.domain.isStreakMilestone
import com.example.backlogium.ui.onboarding.OnboardingScreen
import com.example.backlogium.ui.theme.collectionAccentColor
import com.example.backlogium.ui.util.UiFormat
import com.example.backlogium.ui.util.rememberReducedMotion
import compose.icons.TablerIcons
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.Flame
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Trophy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * @param onAccentColorChanged reports the shell-wide backdrop tint. While in game, Home reports the
 *   now-playing card's own container color so the shell paints one continuous wash behind the
 *   profile header *and* the card — which is what makes the two read as a single block rather than
 *   a strip with an unrelated card under it. Null (not in game) restores the flat theme background.
 */
@Composable
fun HomeScreen(
    onAccentColorChanged: (Color?) -> Unit = {},
    onOpenCollection: (Long) -> Unit = {},
    onCreateCollection: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val nowPlayingAccent = MaterialTheme.colorScheme.tertiaryContainer
    val inGame = state.isInGame && state.nowPlayingName != null
    LaunchedEffect(inGame, nowPlayingAccent) {
        onAccentColorChanged(if (inGame) nowPlayingAccent else null)
    }
    // Leaving Home must not strand the wash behind another tab's header.
    DisposableEffect(Unit) {
        onDispose { onAccentColorChanged(null) }
    }

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

    // The outer column is deliberately *unpadded* so the now-playing panel can run edge to edge
    // like the profile header above it; every other card keeps the screen's 16dp inset via the
    // inner column. An inset panel under a full-bleed header is exactly what read as disconnected.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // "Now playing" panel: conditionally composed so it adds no layout (and runs no
        // animation) when not in-game. Full-bleed and flush against the profile header — no gap,
        // no side margins, flat top edge — so it continues the header's own "In game" state
        // downward into detail instead of announcing it a second time in a separate card.
        val nowPlayingName = state.nowPlayingName
        if (state.isInGame && nowPlayingName != null) {
            NowPlayingPanel(
                name = nowPlayingName,
                iconUrl = state.nowPlayingIconUrl,
                headerUrl = state.nowPlayingHeaderUrl,
                sessionStartedAt = state.nowPlayingSessionStartedAt,
            )
        }

        Spacer(Modifier.height(16.dp))

        InnerHomeContent(
            state = state,
            playLevelUp = playLevelUp,
            onLevelUpFinished = { playLevelUp = false },
            playStreakMilestone = playStreakMilestone,
            onStreakMilestoneFinished = { playStreakMilestone = false },
            onSyncNow = viewModel::syncNow,
            onOpenCollection = onOpenCollection,
            onCreateCollection = onCreateCollection,
        )
    }
}

/** Everything below the now-playing panel, at the screen's normal 16dp inset. */
@Composable
private fun InnerHomeContent(
    state: HomeUiState,
    playLevelUp: Boolean,
    onLevelUpFinished: () -> Unit,
    playStreakMilestone: Boolean,
    onStreakMilestoneFinished: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    onCreateCollection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                        onClick = onSyncNow,
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
                    onFinished = onLevelUpFinished,
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
                    onFinished = onStreakMilestoneFinished,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(72.dp),
                )
            }
        }

        // Custom collections: one mission card per collection, plus a create entry point. Kept
        // after the streak card — always beneath the level/XP/quest/streak surfaces and the
        // now-playing panel, so it can never demote the now-playing card's visual priority.
        CollectionsSection(
            cards = state.collections,
            onOpenCollection = onOpenCollection,
            onCreateCollection = onCreateCollection,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The Home collections section: one mission card per collection plus a create entry point.
 * Renders purely from locally stored state (offline-first), with a dedicated empty state.
 * Cards are deliberately compact and sit at the very bottom of Home so they never displace or
 * demote the level, XP, quest, streak, or now-playing surfaces (app-ui spec).
 */
@Composable
private fun CollectionsSection(
    cards: List<HomeCollectionCard>,
    onOpenCollection: (Long) -> Unit,
    onCreateCollection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Collections", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onCreateCollection) { Text("New") }
        }
        if (cards.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No collections yet", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Group the games that matter right now — a completion goal, a " +
                            "deadline, or a play order.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            cards.forEach { card ->
                CollectionCard(
                    card = card,
                    onClick = { onOpenCollection(card.collectionId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One collection's mission card: its name plus its mode-specific banner, accented by palette. */
@Composable
private fun CollectionCard(
    card: HomeCollectionCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = MaterialTheme.colorScheme.collectionAccentColor(card.accent)
    val baseSurface = MaterialTheme.colorScheme.surfaceContainer
    val cardSurface = card.accent?.let {
        accentColor.copy(alpha = 0.16f).compositeOver(baseSurface)
    } ?: baseSurface
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = cardSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accentColor),
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = modeIcon(card.mode),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = accentColor,
                    )
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                bannerText(card.banner)?.let { copy ->
                    Text(
                        text = copy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun modeIcon(mode: CollectionMode) = when (mode) {
    CollectionMode.BASIC -> TablerIcons.DeviceGamepad
    CollectionMode.COMPLETION_GOAL -> TablerIcons.Trophy
    CollectionMode.DEADLINE_GOAL -> TablerIcons.Clock
    CollectionMode.ORDERED_QUEUE -> TablerIcons.PlayerPlay
}

/** A collection's mode-specific banner copy; a basic list shows its member count. */
private fun bannerText(banner: CollectionBanner): String = when (banner.mode) {
    CollectionMode.BASIC -> banner.memberCountLabel
    CollectionMode.COMPLETION_GOAL -> {
        val progress = banner.completionFraction?.let { percent(it) } ?: "—"
        val trophies = if (banner.achievementsUnlocked != null && banner.achievementsTotal != null) {
            "${banner.achievementsUnlocked}/${banner.achievementsTotal} trophies · " +
                "${banner.achievementsRemaining} left"
        } else {
            "No trophy data"
        }
        "$progress complete · $trophies"
    }
    CollectionMode.DEADLINE_GOAL -> {
        val progress = banner.completionFraction?.let { percent(it) } ?: "—"
        val countdown = when {
            banner.daysRemaining != null && banner.daysRemaining < 0 ->
                "${kotlin.math.abs(banner.daysRemaining)}d past deadline"
            banner.daysRemaining == 0L -> "Deadline today"
            banner.daysRemaining != null -> "${banner.daysRemaining}d left"
            else -> "No deadline set"
        }
        val fit = when {
            banner.unknownDurationCount > 0 ->
                "${banner.unknownDurationCount} missing ${banner.timeBasis.label()} data"
            banner.timeDifferentialMinutes == null -> "No ${banner.timeBasis.label()} estimate"
            banner.timeDifferentialMinutes >= 0 ->
                "${UiFormat.minutes(banner.timeDifferentialMinutes)} buffer"
            else -> "${UiFormat.minutes(kotlin.math.abs(banner.timeDifferentialMinutes))} short"
        }
        "$countdown · $progress complete · $fit"
    }
    CollectionMode.ORDERED_QUEUE -> when {
        banner.queueCompleted -> "Queue complete — no next game"
        banner.nextUp != null -> "Next: ${banner.nextUp.name} (#${banner.nextUpPosition})"
        else -> banner.memberCountLabel
    }
}

/** Format a 0..1 completion fraction as a whole percent, e.g. 0.7 → "70%". */
private fun percent(fraction: Double): String = "${kotlin.math.round(fraction * 100)}%"

/**
 * The most visually prominent element on Home while the player is in-game: large game art, the
 * game's name, and a live elapsed-session timer, in the tertiary steel-blue lane — deliberately
 * not [MaterialTheme.colorScheme.primaryContainer] (gold), which stays reserved for milestone
 * moments (level-up, streak milestones, 100% completion).
 *
 * Rendered as a full-bleed **panel**, not a card: no side margins, no elevation, no top corners,
 * and its top edge fades out of the profile header's own surface color, so header and panel read
 * as one continuous block (the shell also paints a matching wash behind the header while in game —
 * see `HomeScreen`'s `onAccentColorChanged`). An inset, shadowed, fully-rounded card under a
 * full-bleed header is precisely what read as two unrelated things stacked.
 *
 * A slow, flowing sheen marks the panel as live rather than merely colored; the ticking timer and
 * the panel's presence still carry that meaning when reduced-motion renders the sheen statically.
 */
@Composable
private fun NowPlayingPanel(
    name: String,
    iconUrl: String?,
    headerUrl: String?,
    sessionStartedAt: Long?,
) {
    val elapsedMillis by rememberElapsedMillis(sessionStartedAt)
    val sheenCenter = rememberNowPlayingSheenCenter()
    val base = MaterialTheme.colorScheme.tertiaryContainer
    val sheen = MaterialTheme.colorScheme.tertiary
    val onContainer = MaterialTheme.colorScheme.onTertiaryContainer

    val elapsedLabel = UiFormat.liveElapsed(elapsedMillis)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 24.dp,
                    bottomEnd = 24.dp,
                ),
            )
            // Everything the panel paints — its tint *and* its sheen — fades in from fully
            // transparent across the top, then is masked (`DstIn`, the same idiom the Library
            // row's header-art backdrop uses) so not one pixel differs from the shell backdrop at
            // the boundary itself. Nothing may "start" at y=0 here: an abrupt start of any layer,
            // tint or sheen, is what read as a crease against the transparent profile header.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.75f to base,
                            1f to base,
                        ),
                    ),
                )
                // The ambient live sheen sweeping across, layered over the tint.
                val bandWidth = size.width * 0.55f
                val center = sheenCenter * size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.5f to sheen.copy(alpha = 0.22f),
                            1f to Color.Transparent,
                        ),
                        start = Offset(center - bandWidth, 0f),
                        end = Offset(center + bandWidth, size.height),
                    ),
                )
                // Alpha mask over both layers at once: zero opacity at the very top edge, full
                // by a third of the way down. Applied as a mask rather than baked into each
                // brush so the sheen can never reintroduce an edge the tint just removed.
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.35f to Color.Black,
                            1f to Color.Black,
                        ),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            // Accessible even with the visible "Now playing" label folded into the header above.
            .semantics(mergeDescendants = true) {
                contentDescription = "Now playing $name, playing for $elapsedLabel"
            },
    ) {
        // The running game's own store art, filling the space to the right of the text. Reuses the
        // Library row's backdrop treatment (faint, right-anchored, alpha-masked) rather than a
        // Steam logo: Valve's branding guidelines require the logo to stand alone and not be
        // combined with other graphics or text, and a large brand watermark would also imply an
        // affiliation the Web API terms forbid. The game's art says more here anyway.
        if (headerUrl != null) {
            NowPlayingArtBackdrop(headerUrl = headerUrl, modifier = Modifier.matchParentSize())
        }

        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
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
                // No repeated "Now playing" label here — the profile header directly above
                // already reads "In game"; this just continues that thought with what and how
                // long, rather than announcing the same state a second time.
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
                    text = "Playing for $elapsedLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                )
            }
        }
    }
}

/**
 * The running game's store header art as a faint panel backdrop, anchored to the right edge.
 *
 * Masked on **both** axes inside one offscreen layer (`DstIn` twice, so the two alpha ramps
 * multiply), which is what makes it usable here: the horizontal ramp dissolves the art before it
 * reaches the game name on the left, and the vertical ramp keeps it clear of the panel's top edge —
 * without that second ramp the art would start abruptly against the transparent profile header and
 * put the crease straight back. The same `DstIn` idiom as the Library row's `GameBackdrop`, which
 * needs only the horizontal ramp since its card has no shared edge to protect.
 *
 * Games with no art on the CDN render nothing at all; the panel is designed to look right without
 * it, so no placeholder is drawn.
 */
@Composable
private fun NowPlayingArtBackdrop(headerUrl: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = headerUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.CenterEnd,
        modifier = modifier
            .graphicsLayer {
                alpha = ART_BACKDROP_ALPHA
                // Required for DstIn: the masks composite against this layer, not the screen.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        ART_BACKDROP_FADE_END to Color.Black,
                    ),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        ART_BACKDROP_TOP_FADE_END to Color.Black,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/** Faint enough that the game name and timer keep their contrast over the brightest header art. */
private const val ART_BACKDROP_ALPHA = 0.20f

/** Fraction of the panel width at which the art reaches full opacity, fading out left of it. */
private const val ART_BACKDROP_FADE_END = 0.95f

/** Fraction of the panel height over which the art fades in, keeping its top edge seamless. */
private const val ART_BACKDROP_TOP_FADE_END = 0.5f

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
 * A slow, ambient sweep for the now-playing panel's sheen — driven by an infinite transition so it
 * stops the moment the panel leaves composition (i.e. the moment the player is no longer in-game),
 * and never runs at all otherwise. Under a reduced-motion preference the sweep holds at a fixed
 * midpoint and the sheen renders statically, via the same shared [rememberReducedMotion] the
 * shell's sync indicator uses, so the app answers that question in one place.
 */
@Composable
private fun rememberNowPlayingSheenCenter(): Float {
    if (rememberReducedMotion()) return 0.5f

    val transition = rememberInfiniteTransition(label = "nowPlayingSheen")
    val center by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sheenCenter",
    )
    return center
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

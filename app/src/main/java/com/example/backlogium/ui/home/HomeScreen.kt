package com.example.backlogium.ui.home

import androidx.annotation.RawRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ScrollState
import androidx.compose.animation.core.snap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.backlogium.R
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionPacingState
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.label
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.components.RecencyBadge
import com.example.backlogium.ui.components.accessibilityLabel
import com.example.backlogium.ui.onboarding.OnboardingScreen
import com.example.backlogium.ui.theme.collectionAccentColor
import com.example.backlogium.ui.theme.deadlineWarning
import com.example.backlogium.ui.theme.playingIndicator
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.ui.util.UiFormat
import com.example.backlogium.ui.util.playIfNotSilent
import com.example.backlogium.ui.util.rememberHaptics
import com.example.backlogium.ui.util.rememberReducedMotion
import com.example.backlogium.ui.util.toHapticIntent
import compose.icons.TablerIcons
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.ArrowsSort
import compose.icons.tablericons.Flame
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Trophy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    onOpenCollections: () -> Unit = {},
    onPlanGap: () -> Unit = {},
    onOpenSmartCollection: (SmartCollectionId) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenGame: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    val nowPlayingAccent = MaterialTheme.colorScheme.tertiaryContainer
    val inGame = state.isInGame && state.nowPlayingName != null
    LaunchedEffect(inGame, nowPlayingAccent) {
        onAccentColorChanged(if (inGame) nowPlayingAccent else null)
    }
    // Leaving Home must not strand the wash behind another tab's header.
    DisposableEffect(Unit) {
        onDispose { onAccentColorChanged(null) }
    }

    if (shouldShowHomeLoading(state)) {
        HomeLoadingContent()
        return
    }

    // The takeover latches on the first unconfigured composition and is released by the flow
    // itself, not by `configured` flipping. Saving credentials flips it *mid-flow* — the flow
    // continues into first-run setup afterwards — so tearing the takeover down there would
    // dismantle the setup step in the same frame it appeared.
    //
    // Two latches, because one cannot cover both windows. `state.firstRunSetupActive` is durable and
    // is what restores the takeover on a cold launch after the process is killed mid-setup, when
    // credentials are already stored. It is written asynchronously as they are stored, so the
    // saved-instance latch below holds the surface across an Activity recreation in the gap before
    // that write lands — and across the gap after it is cleared, before `completed` is reported.
    var onboardingActive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.configured) { if (!state.configured) onboardingActive = true }

    if (!state.configured || state.firstRunSetupActive || onboardingActive) {
        // Full-screen onboarding takeover replaces the old dead-end "not configured" message.
        OnboardingScreen(onCompleted = { onboardingActive = false })
        return
    }

    // Durable progress events, rather than a state-level comparison, decide when an earned moment
    // is presented. This also covers a level-up produced while the app was closed.
    val pendingLevelUp = state.pendingProgressEvent as? ProgressEvent.LevelUp
    var playLevelUp by remember { mutableStateOf(false) }
    LaunchedEffect(pendingLevelUp) {
        playLevelUp = pendingLevelUp != null
    }
    LaunchedEffect(state.pendingProgressEvent, playLevelUp) {
        val event = state.pendingProgressEvent ?: return@LaunchedEffect
        if (event is ProgressEvent.LevelUp) {
            if (!playLevelUp) return@LaunchedEffect
            // The Lottie presentation is mounted only after playLevelUp becomes true; wait for
            // that presentation to reach a frame before delivering the earned haptic.
            withFrameNanos { }
        } else if (event is ProgressEvent.QuestMet) {
            // The event-specific card below must have reached a frame before its haptic and
            // acknowledgement make the earned moment durable as delivered.
            withFrameNanos { }
        }
        val intent = event.toHapticIntent()
        haptics.playIfNotSilent(intent)
        // The event-specific quest card is the visible presentation; it has no separate animation
        // or dismiss affordance, so acknowledge it after that card has reached a frame.
        if (event is ProgressEvent.QuestMet) {
            viewModel.acknowledgeProgressEvent(event)
        }
    }

    // The error card is also used by background syncs. Only a retry initiated from this visible
    // card arms Reject, so a background failure never produces an unattributable buzz.
    var manualSyncAttempt by remember { mutableStateOf(false) }
    var manualSyncInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(state.isSyncing) {
        if (state.isSyncing && manualSyncAttempt) {
            manualSyncInFlight = true
        } else if (!state.isSyncing && manualSyncAttempt && manualSyncInFlight) {
            if (state.lastSyncError != null) haptics.playIfNotSilent(HapticIntent.Reject)
            manualSyncAttempt = false
            manualSyncInFlight = false
        }
    }

    val scrollState = rememberScrollState()
    var scrollViewport by remember { mutableStateOf<Rect?>(null) }

    // The outer column is deliberately *unpadded* so the now-playing panel can run edge to edge
    // like the profile header above it; every other card keeps the screen's 16dp inset via the
    // inner column. An inset panel under a full-bleed header is exactly what read as disconnected.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .onGloballyPositioned { coordinates ->
                val topLeft = coordinates.positionInRoot()
                scrollViewport = Rect(
                    topLeft = topLeft,
                    bottomRight = topLeft + Offset(
                        coordinates.size.width.toFloat(),
                        coordinates.size.height.toFloat(),
                    ),
                )
            },
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
                recencyState = state.nowPlayingRecencyState,
            )
        }

        Spacer(Modifier.height(16.dp))

        InnerHomeContent(
            state = state,
            playLevelUp = playLevelUp,
            onLevelUpFinished = {
                playLevelUp = false
                pendingLevelUp?.let(viewModel::acknowledgeProgressEvent)
            },
            onStreakMilestoneFinished = { viewModel.acknowledgeProgressEvent(it) },
            onSyncNow = {
                if (!state.isSyncing) {
                    manualSyncAttempt = true
                    viewModel.syncNow()
                }
            },
            onOpenCollection = onOpenCollection,
            onCreateCollection = onCreateCollection,
            onOpenCollections = onOpenCollections,
            onPlanGap = onPlanGap,
            onOpenSmartCollection = onOpenSmartCollection,
            onOpenLibrary = onOpenLibrary,
            onOpenGame = onOpenGame,
            scrollState = scrollState,
            scrollViewport = scrollViewport,
            onReorderCollections = viewModel::reorderCollections,
        )
    }
}

/** Everything below the now-playing panel, at the screen's normal 16dp inset. */
@Composable
private fun InnerHomeContent(
    state: HomeUiState,
    playLevelUp: Boolean,
    onLevelUpFinished: () -> Unit,
    onStreakMilestoneFinished: (ProgressEvent.StreakMilestone) -> Unit,
    onSyncNow: () -> Unit,
    onOpenCollection: (Long) -> Unit,
    onCreateCollection: () -> Unit,
    onOpenCollections: () -> Unit,
    onPlanGap: () -> Unit,
    onOpenSmartCollection: (SmartCollectionId) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenGame: (Long) -> Unit,
    scrollState: ScrollState,
    scrollViewport: Rect?,
    onReorderCollections: (List<Long>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (shouldShowHomeUpdating(state)) {
            HomeUpdatingIndicator()
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
                            Text(stringResource(R.string.home_retry))
                        }
                    }
                }
            }
        }

        val monitoringMessage = when (state.liveMonitoringAvailability) {
            PresenceMonitoringAvailability.AVAILABLE -> null
            PresenceMonitoringAvailability.FOREGROUND_REQUIRED ->
                stringResource(R.string.home_monitoring_foreground_required)
            PresenceMonitoringAvailability.RUNTIME_BUDGET_EXHAUSTED ->
                stringResource(R.string.home_monitoring_budget_exhausted)
            PresenceMonitoringAvailability.START_REFUSED ->
                stringResource(R.string.home_monitoring_start_refused)
            PresenceMonitoringAvailability.START_FAILED ->
                stringResource(R.string.home_monitoring_start_failed)
        }
        monitoringMessage?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.home_live_monitoring_unavailable),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = message)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_periodic_tracking_continues),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        (state.pendingProgressEvent as? ProgressEvent.QuestMet)?.let { event ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.CircleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.home_quest_completed),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = stringResource(
                                R.string.home_earned_on,
                                UiFormat.date(event.date),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        HomeNextActionSurface(
            action = state.nextAction,
            onOpenGame = onOpenGame,
            onOpenCollection = onOpenCollection,
            onOpenLibrary = onOpenLibrary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HOME_NEXT_ACTION_TAG),
        )

        // Level + XP.
        Card(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.home_level,
                            UiFormat.count(state.level),
                        ),
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
                        text = stringResource(
                            R.string.home_xp_progress,
                            UiFormat.count(state.xpIntoLevel),
                            UiFormat.count(state.xpForNext),
                            UiFormat.count(state.totalXp),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                CelebrationAnimation(
                    resId = R.raw.levelup,
                    play = playLevelUp,
                    onFinished = onLevelUpFinished,
                    staticLabel = stringResource(R.string.home_level_up_earned),
                    staticTag = HOME_LEVEL_UP_STATIC_TAG,
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
                Text(
                    stringResource(R.string.home_todays_quest),
                    style = MaterialTheme.typography.titleMedium,
                )
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
                        text = stringResource(
                            if (state.questMet) R.string.home_complete else R.string.home_in_progress,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.home_played_today,
                        UiFormat.localizedMinutes(state.todayMinutes),
                        UiFormat.localizedMinutes(state.questThreshold),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Streak.
        Card(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.home_streak),
                        style = MaterialTheme.typography.titleMedium,
                    )
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
                                pluralStringResource(
                                    R.plurals.home_streak_days,
                                    state.currentStreak,
                                    UiFormat.count(state.currentStreak),
                                )
                            } else {
                                stringResource(
                                    R.string.home_streak_pending,
                                    UiFormat.count(state.currentStreak),
                                )
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.home_longest,
                            UiFormat.count(state.longestStreak),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val pendingMilestone = state.pendingStreakMilestone
                CelebrationAnimation(
                    resId = R.raw.streak_milestone,
                    play = pendingMilestone != null,
                    onFinished = { pendingMilestone?.let(onStreakMilestoneFinished) },
                    staticLabel = stringResource(R.string.home_streak_milestone_earned),
                    staticTag = HOME_STREAK_MILESTONE_STATIC_TAG,
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
            onOpenCollections = onOpenCollections,
            onPlanGap = onPlanGap,
            scrollState = scrollState,
            scrollViewport = scrollViewport,
            onReorderCollections = onReorderCollections,
            modifier = Modifier.fillMaxWidth(),
        )

        // Derived lists, always last and always in their fixed order. The dashed rule above them
        // carries the whole distinction: everything above it the player arranged, everything below
        // it the app worked out.
        if (state.smartCollections.isNotEmpty()) {
            SmartCollectionsSection(
                cards = state.smartCollections,
                onOpenSmartCollection = onOpenSmartCollection,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

internal const val HOME_LOADING_TAG = "home-loading"
internal const val HOME_LOADING_PLACEHOLDER_TAG = "home-loading-placeholder"
internal const val HOME_UPDATING_TAG = "home-updating"

/** Bounded first-load presentation; it keeps the Home hierarchy recognizable without fake data. */
@Composable
internal fun HomeLoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag(HOME_LOADING_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_loading_title),
            style = MaterialTheme.typography.titleMedium,
        )
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        listOf(144.dp, 116.dp, 116.dp, 164.dp).forEach { height ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .testTag(HOME_LOADING_PLACEHOLDER_TAG),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {}
        }
    }
}

/** Small retained-content status; the cached Home remains mounted underneath it. */
@Composable
internal fun HomeUpdatingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HOME_UPDATING_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.home_updating),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Home collections section: one mission card per collection plus a create entry point.
 * Renders purely from locally stored state (offline-first), with a dedicated empty state.
 * Cards are deliberately compact and sit at the very bottom of Home so they never displace or
 * demote the level, XP, quest, streak, or now-playing surfaces (app-ui spec).
 */
/** Restore the last persisted presentation when a moved drag is cancelled. */
internal fun <T> homeCollectionOrderAfterCancelledDrag(
    persistedCards: List<T>,
    currentCards: List<T>,
    initialIndex: Int,
    currentIndex: Int,
): List<T> = if (currentIndex != initialIndex) persistedCards else currentCards

internal const val HOME_COLLECTIONS_NEW_TAG = "home-collections-new"
internal const val HOME_COLLECTIONS_VIEW_ALL_TAG = "home-collections-view-all"
internal const val HOME_COLLECTIONS_REORDER_TAG = "home-collections-reorder"
internal const val HOME_COLLECTION_CARD_TAG_PREFIX = "home-collection-card-"

@Composable
internal fun HomeCollectionsHeader(
    onCreateCollection: () -> Unit,
    onOpenCollections: () -> Unit,
    onPlanGap: () -> Unit,
    reorderMode: Boolean = false,
    onToggleReorder: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_collections),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onCreateCollection,
                modifier = Modifier.testTag(HOME_COLLECTIONS_NEW_TAG),
            ) {
                Text(stringResource(R.string.home_new))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenCollections,
                modifier = Modifier
                    .weight(1f)
                    .testTag(HOME_COLLECTIONS_VIEW_ALL_TAG),
            ) {
                Text(stringResource(R.string.home_view_all))
            }
            TextButton(
                onClick = onPlanGap,
                modifier = Modifier
                    .weight(1f)
                    .testTag(HOME_PLAN_GAP_TAG),
            ) {
                Text(stringResource(R.string.home_plan_gap))
            }
            TextButton(
                onClick = onToggleReorder,
                modifier = Modifier
                    .weight(1f)
                    .testTag(HOME_COLLECTIONS_REORDER_TAG),
            ) {
                Text(
                    stringResource(
                        if (reorderMode) R.string.home_done else R.string.home_reorder,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun CollectionsSection(
    cards: List<HomeCollectionCard>,
    onOpenCollection: (Long) -> Unit,
    onCreateCollection: () -> Unit,
    onOpenCollections: () -> Unit,
    onPlanGap: () -> Unit,
    scrollState: ScrollState,
    scrollViewport: Rect?,
    onReorderCollections: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoScrollScope = rememberCoroutineScope()
    var reorderMode by remember { mutableStateOf(false) }
    var orderedCards by remember { mutableStateOf(cards) }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }
    val draggedId = remember { mutableStateOf<Long?>(null) }
    val initialIndex = remember { mutableStateOf(-1) }
    val currentIndex = remember { mutableStateOf(-1) }
    val pointerRootY = remember { mutableFloatStateOf(0f) }
    val dragBaseline = remember { mutableStateOf<List<HomeCollectionCard>?>(null) }
    val autoScrollJob = remember { mutableStateOf<Job?>(null) }
    val latestCards = rememberUpdatedState(orderedCards)
    val latestPersistedCards = rememberUpdatedState(cards)

    LaunchedEffect(cards) {
        val cardsById = cards.associateBy { it.collectionId }
        orderedCards = orderedCards
            .mapNotNull { cardsById[it.collectionId] }
            .let { existing ->
                existing + cards.filterNot { card -> existing.any { it.collectionId == card.collectionId } }
            }
        cardBounds.keys.toList()
            .filterNot { it in cardsById }
            .forEach(cardBounds::remove)
        if (cards.isEmpty()) reorderMode = false
    }

    fun applyCollectionReorder(
        fromIndex: Int,
        targetIndex: Int,
        persist: Boolean,
    ) {
        val activeCards = latestCards.value
        val reordered = homeCollectionOrderAfterMove(activeCards, fromIndex, targetIndex)
        if (reordered == activeCards) return
        orderedCards = reordered
        if (draggedId.value != null) currentIndex.value = targetIndex
        if (persist) onReorderCollections(reordered.map { it.collectionId })
    }

    fun moveCollection(collectionId: Long, delta: Int): Boolean {
        val index = latestCards.value.indexOfFirst { it.collectionId == collectionId }
        val targetIndex = index + delta
        if (index !in latestCards.value.indices || targetIndex !in latestCards.value.indices) {
            return false
        }
        applyCollectionReorder(index, targetIndex, persist = true)
        return true
    }

    fun clearDrag(revertToBaseline: Boolean = false) {
        if (revertToBaseline && currentIndex.value != initialIndex.value) {
            orderedCards = homeCollectionOrderAfterCancelledDrag<HomeCollectionCard>(
                persistedCards = dragBaseline.value ?: latestPersistedCards.value,
                currentCards = orderedCards,
                initialIndex = initialIndex.value,
                currentIndex = currentIndex.value,
            )
        }
        autoScrollJob.value?.cancel()
        autoScrollJob.value = null
        dragBaseline.value = null
        draggedId.value = null
        initialIndex.value = -1
        currentIndex.value = -1
        pointerRootY.floatValue = 0f
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeCollectionsHeader(
            onCreateCollection = onCreateCollection,
            onOpenCollections = onOpenCollections,
            onPlanGap = onPlanGap,
            reorderMode = reorderMode,
            onToggleReorder = {
                if (reorderMode) clearDrag(revertToBaseline = true)
                reorderMode = !reorderMode
            },
        )
        if (cards.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.home_no_collections),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_no_collections_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            orderedCards.forEachIndexed { position, card ->
                key(card.collectionId) {
                    val isDragged = draggedId.value == card.collectionId
                    val cardCenter = cardBounds[card.collectionId]?.center?.y ?: pointerRootY.floatValue
                    val dragOffset = if (isDragged) pointerRootY.floatValue - cardCenter else 0f
                    val dragHandleModifier = if (reorderMode) {
                        Modifier.pointerInput(card.collectionId, reorderMode) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (latestCards.value.size <= 1) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    val index = latestCards.value.indexOfFirst {
                                        it.collectionId == card.collectionId
                                    }
                                    if (index < 0) return@detectDragGesturesAfterLongPress
                                    val center = cardBounds[card.collectionId]?.center?.y
                                        ?: return@detectDragGesturesAfterLongPress
                                    dragBaseline.value = latestPersistedCards.value
                                    draggedId.value = card.collectionId
                                    initialIndex.value = index
                                    currentIndex.value = index
                                    pointerRootY.floatValue = center
                                },
                                onDragCancel = { clearDrag(revertToBaseline = true) },
                                onDragEnd = {
                                    if (draggedId.value == card.collectionId) {
                                        if (currentIndex.value != initialIndex.value) {
                                            onReorderCollections(latestCards.value.map { it.collectionId })
                                        }
                                        clearDrag()
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    if (draggedId.value != card.collectionId) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    change.consume()
                                    pointerRootY.floatValue += dragAmount.y

                                    scrollViewport?.let { viewport ->
                                        val edgeDistance = 72f
                                        val scrollDelta = when {
                                            pointerRootY.floatValue < viewport.top + edgeDistance ->
                                                -((viewport.top + edgeDistance - pointerRootY.floatValue) /
                                                    edgeDistance * 24f)
                                            pointerRootY.floatValue > viewport.bottom - edgeDistance ->
                                                ((pointerRootY.floatValue - (viewport.bottom - edgeDistance)) /
                                                    edgeDistance * 24f)
                                            else -> 0f
                                        }
                                        autoScrollJob.value?.cancel()
                                        autoScrollJob.value = if (scrollDelta != 0f) {
                                            autoScrollScope.launch { scrollState.scrollBy(scrollDelta) }
                                        } else {
                                            null
                                        }
                                    }

                                    val activeCards = latestCards.value
                                    val fromIndex = currentIndex.value
                                    if (fromIndex !in activeCards.indices) {
                                        return@detectDragGesturesAfterLongPress
                                    }
                                    var targetIndex = activeCards.lastIndex
                                    activeCards.forEachIndexed { index, candidate ->
                                        val center = cardBounds[candidate.collectionId]?.center?.y
                                            ?: return@forEachIndexed
                                        if (pointerRootY.floatValue < center &&
                                            targetIndex == activeCards.lastIndex
                                        ) {
                                            targetIndex = if (index > fromIndex) index - 1 else index
                                        }
                                    }
                                    applyCollectionReorder(
                                        fromIndex = fromIndex,
                                        targetIndex = targetIndex.coerceIn(0, activeCards.lastIndex),
                                        persist = false,
                                    )
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                    CollectionCard(
                        card = card,
                        onClick = {
                            if (!reorderMode && draggedId.value == null) {
                                onOpenCollection(card.collectionId)
                            }
                        },
                        reorderMode = reorderMode,
                        position = position,
                        totalCount = orderedCards.size,
                        onMoveUp = { moveCollection(card.collectionId, -1) },
                        onMoveDown = { moveCollection(card.collectionId, 1) },
                        dragHandleModifier = dragHandleModifier,
                        dragged = isDragged,
                        dragOffset = dragOffset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                val topLeft = coordinates.positionInRoot()
                                cardBounds[card.collectionId] = Rect(
                                    topLeft = topLeft,
                                    bottomRight = topLeft + Offset(
                                        coordinates.size.width.toFloat(),
                                        coordinates.size.height.toFloat(),
                                    ),
                                )
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun smartCollectionName(id: SmartCollectionId): String = when (id) {
    SmartCollectionId.QUICK_WINS -> stringResource(R.string.home_smart_quick_wins)
    SmartCollectionId.NEVER_STARTED -> stringResource(R.string.home_smart_never_started)
    SmartCollectionId.ALMOST_DONE -> stringResource(R.string.home_smart_almost_done)
    SmartCollectionId.DROPPED -> stringResource(R.string.home_smart_dropped)
    SmartCollectionId.COMPLETED -> stringResource(R.string.home_smart_completed)
}

@Composable
private fun smartCollectionRule(id: SmartCollectionId): String = when (id) {
    SmartCollectionId.QUICK_WINS -> stringResource(R.string.home_smart_rule_quick_wins)
    SmartCollectionId.NEVER_STARTED -> stringResource(R.string.home_smart_rule_never_started)
    SmartCollectionId.ALMOST_DONE -> stringResource(R.string.home_smart_rule_almost_done)
    SmartCollectionId.DROPPED -> stringResource(R.string.home_smart_rule_dropped)
    SmartCollectionId.COMPLETED -> stringResource(R.string.home_smart_rule_completed)
}

/**
 * Home's derived collections: fixed membership, fixed order, no affordance to change either.
 *
 * They sit below the custom collections rather than among them because the two answer different
 * questions - what the player chose to group, then what the library turned out to be saying - and
 * a list that cannot be reordered must not appear to be part of one that can.
 */
@Composable
private fun SmartCollectionsSection(
    cards: List<HomeSmartCollectionCard>,
    onOpenSmartCollection: (SmartCollectionId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DashedSectionDivider(Modifier.padding(vertical = 6.dp))
        Text(
            stringResource(R.string.home_derived_collections),
            style = MaterialTheme.typography.titleMedium,
        )
        cards.forEach { card ->
            key(card.id) {
                SmartCollectionCard(
                    card = card,
                    onClick = { onOpenSmartCollection(card.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The boundary between chosen and derived collections, drawn rather than written. */
@Composable
private fun DashedSectionDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
        )
    }
}

/** A derived list on Home: its name, how many games qualify, and the rule that decided. */
@Composable
private fun SmartCollectionCard(
    card: HomeSmartCollectionCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = smartCollectionName(card.id)
    val rule = smartCollectionRule(card.id)
    val openDescription = stringResource(
        R.string.home_open_derived_collection,
        name,
    )
    Card(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = openDescription
        },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = UiFormat.count(card.memberCount),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = rule,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One collection's mission card: its name plus its mode-specific banner, accented by palette. */
@Composable
internal fun CollectionCard(
    card: HomeCollectionCard,
    onClick: () -> Unit,
    reorderMode: Boolean = false,
    position: Int = -1,
    totalCount: Int = 0,
    onMoveUp: () -> Boolean = { false },
    onMoveDown: () -> Boolean = { false },
    dragHandleModifier: Modifier = Modifier,
    dragged: Boolean = false,
    dragOffset: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val compactDeadlineCard = card.mode == CollectionMode.DEADLINE_GOAL
    val accentColor = MaterialTheme.colorScheme.collectionAccentColor(card.accent)
    val deadlineSummaryColor = when {
        card.mode != CollectionMode.DEADLINE_GOAL -> MaterialTheme.colorScheme.onSurfaceVariant
        card.banner.daysRemaining == null || card.banner.daysRemaining > 7L ->
            MaterialTheme.colorScheme.onSurfaceVariant
        card.banner.daysRemaining <= 0L -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.deadlineWarning
    }
    val glowColor = card.accent?.let {
        MaterialTheme.colorScheme.collectionAccentColor(it)
    } ?: MaterialTheme.colorScheme.playingIndicator
    val reducedMotion = rememberReducedMotion()
    val dragScale by animateFloatAsState(
        targetValue = if (dragged) 1.02f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(120),
        label = "collectionDragScale",
    )
    val glowVisibility by animateFloatAsState(
        targetValue = if (card.isCurrentlyPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "collectionGlowFade",
    )
    val pulse = if (card.isCurrentlyPlaying && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "collectionGlowPulse")
        transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "collectionGlowPulseAlpha",
        ).value
    } else {
        1f
    }
    val glowAlpha = glowVisibility * pulse
    val baseSurface = MaterialTheme.colorScheme.surfaceContainer
    val cardSurface = card.accent?.let {
        accentColor.copy(alpha = 0.16f).compositeOver(baseSurface)
    } ?: baseSurface
    val reorderActions = if (reorderMode) {
        listOfNotNull(
            (position > 0).takeIf { it }?.let {
                CustomAccessibilityAction("Move up") { onMoveUp() }
            },
            (position >= 0 && position < totalCount - 1).takeIf { it }?.let {
                CustomAccessibilityAction("Move down") { onMoveDown() }
            },
        )
    } else {
        emptyList()
    }
    val positionDescription = stringResource(
        R.string.home_collection_position,
        position + 1,
        totalCount,
    )
    val dragDescription = stringResource(R.string.home_drag_to_reorder, card.name)
    Card(
        onClick = onClick,
        enabled = !reorderMode,
        modifier = modifier
            .testTag(HOME_COLLECTION_CARD_TAG_PREFIX + card.collectionId)
            .semantics {
                if (reorderMode) {
                    customActions = reorderActions
                    stateDescription = positionDescription
                }
            }
            .shadow(
                elevation = 12.dp * glowVisibility,
                shape = RoundedCornerShape(12.dp),
                ambientColor = glowColor.copy(alpha = 0.65f * glowAlpha),
                spotColor = glowColor.copy(alpha = 0.45f * glowAlpha),
            )
            .drawWithContent {
                drawContent()
                if (glowVisibility > 0f) {
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.22f * glowAlpha),
                        style = Stroke(width = 6.dp.toPx()),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                    drawRoundRect(
                        color = glowColor.copy(alpha = 0.86f * glowAlpha),
                        style = Stroke(width = 2.dp.toPx()),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                }
            }
            .graphicsLayer {
                translationY = if (dragged) dragOffset else 0f
                scaleX = dragScale
                scaleY = dragScale
            },
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        horizontal = 14.dp,
                        vertical = if (compactDeadlineCard) 10.dp else 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = modeIcon(card.mode),
                            contentDescription = modeAccessibilityLabel(card.mode),
                            modifier = Modifier.size(18.dp),
                            tint = accentColor,
                        )
                        Text(
                            text = card.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(if (compactDeadlineCard) 2.dp else 4.dp))
                    bannerText(card.banner)?.let { copy ->
                        Text(
                            text = copy,
                            style = MaterialTheme.typography.bodySmall,
                            color = deadlineSummaryColor,
                            maxLines = if (compactDeadlineCard) 1 else Int.MAX_VALUE,
                            overflow = if (compactDeadlineCard) TextOverflow.Ellipsis else TextOverflow.Clip,
                        )
                    }
                    if (card.mode != CollectionMode.BASIC) {
                        card.banner.completionFraction?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (compactDeadlineCard) 4.dp else 6.dp),
                            )
                        }
                    }
                }
                CollectionGameThumbs(
                    games = card.games,
                    accentColor = accentColor,
                )
                if (reorderMode) {
                    Box(
                        modifier = dragHandleModifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = dragDescription
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = TablerIcons.ArrowsSort,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionGameThumbs(
    games: List<HomeCollectionGame>,
    accentColor: Color,
) {
    if (games.isEmpty()) return
    val preview = homeCollectionThumbnailPreview(games)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        preview.visibleGames.forEach { game ->
            if (game.iconUrl != null) {
                GameIcon(iconUrl = game.iconUrl, iconSize = 26.dp, shape = CircleShape)
            } else {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TablerIcons.DeviceGamepad,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = accentColor,
                    )
                }
            }
        }
        if (preview.overflowCount > 0) {
            Text(
                text = stringResource(
                    R.string.home_collection_overflow,
                    preview.overflowCount,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
            )
        }
    }
}

private fun modeIcon(mode: CollectionMode) = when (mode) {
    CollectionMode.BASIC -> TablerIcons.DeviceGamepad
    CollectionMode.COMPLETION_GOAL -> TablerIcons.Trophy
    CollectionMode.DEADLINE_GOAL -> TablerIcons.Clock
    CollectionMode.ORDERED_QUEUE -> TablerIcons.PlayerPlay
}

@Composable
private fun modeAccessibilityLabel(mode: CollectionMode): String = when (mode) {
    CollectionMode.BASIC -> stringResource(R.string.home_mode_basic)
    CollectionMode.COMPLETION_GOAL -> stringResource(R.string.home_mode_completion_goal)
    CollectionMode.DEADLINE_GOAL -> stringResource(R.string.home_mode_deadline_goal)
    CollectionMode.ORDERED_QUEUE -> stringResource(R.string.home_mode_ordered_queue)
}

/** A collection's mode-specific banner copy; a basic list shows its member count. */
@Composable
private fun bannerText(banner: CollectionBanner): String = when (banner.mode) {
    CollectionMode.BASIC -> pluralStringResource(
        R.plurals.home_collection_games,
        banner.memberCount,
        UiFormat.count(banner.memberCount),
    )
    CollectionMode.COMPLETION_GOAL -> {
        val progress = banner.completionFraction?.let { percent(it) }
            ?: stringResource(R.string.home_not_available)
        val trophies = if (banner.achievementsUnlocked != null && banner.achievementsTotal != null) {
            stringResource(
                R.string.home_trophy_progress,
                UiFormat.count(banner.achievementsUnlocked),
                UiFormat.count(banner.achievementsTotal),
                UiFormat.count(banner.achievementsRemaining),
            )
        } else {
            stringResource(R.string.home_no_trophy_data)
        }
        stringResource(R.string.home_completion_progress, progress, trophies)
    }
    CollectionMode.DEADLINE_GOAL -> {
        val progress = banner.completionFraction?.let { percent(it) }
            ?: stringResource(R.string.home_not_available)
        val countdown = when {
            banner.daysRemaining != null && banner.daysRemaining < 0 ->
                pluralStringResource(
                    R.plurals.home_days_past_deadline,
                    kotlin.math.abs(banner.daysRemaining).toInt(),
                    kotlin.math.abs(banner.daysRemaining),
                )
            banner.daysRemaining == 0L -> stringResource(R.string.home_deadline_today)
            banner.daysRemaining != null -> pluralStringResource(
                R.plurals.home_deadline_days_left,
                banner.daysRemaining.toInt(),
                banner.daysRemaining,
            )
            else -> stringResource(R.string.home_no_deadline)
        }
        val status = when (banner.pacingState) {
            CollectionPacingState.AT_RISK -> banner.requiredMinutesPerActiveDay?.let {
                stringResource(R.string.home_need_per_day, UiFormat.localizedMinutes(it.toInt()))
            } ?: stringResource(R.string.home_attention_needed)
            CollectionPacingState.INCOMPLETE_DATA -> stringResource(R.string.home_incomplete)
            CollectionPacingState.LEARNING -> stringResource(R.string.home_learning)
            else -> progress
        }
        stringResource(R.string.home_deadline_summary, countdown, status)
    }
    CollectionMode.ORDERED_QUEUE -> when {
        banner.queueCompleted -> stringResource(R.string.home_queue_complete)
        banner.nextUp != null -> stringResource(
            R.string.home_queue_next,
            banner.nextUp.name ?: stringResource(R.string.home_game_fallback, banner.nextUp.appId),
            banner.nextUpPosition ?: 0,
        )
        else -> pluralStringResource(
            R.plurals.home_collection_games,
            banner.memberCount,
            UiFormat.count(banner.memberCount),
        )
    }
}

/** Format a 0..1 completion fraction as a whole percent, e.g. 0.7 → "70%". */
private fun percent(fraction: Double): String = UiFormat.percent(fraction)

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
    recencyState: GameRecencyState? = null,
) {
    val elapsedMillis by rememberElapsedMillis(sessionStartedAt)
    val sheenCenter = rememberNowPlayingSheenCenter()
    val base = MaterialTheme.colorScheme.tertiaryContainer
    val sheen = MaterialTheme.colorScheme.tertiary
    val onContainer = MaterialTheme.colorScheme.onTertiaryContainer

    val elapsedLabel = UiFormat.liveElapsed(elapsedMillis)
    val nowPlayingDescription = stringResource(
        R.string.home_now_playing_accessibility,
        name,
        elapsedLabel,
    )

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
            // The recency state is spelled into this description rather than left to the badge's
            // own: this node merges its descendants, so a nested contentDescription is swallowed.
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    nowPlayingDescription,
                    recencyState?.accessibilityLabel,
                ).joinToString(", ")
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                    )
                    // The playing treatment here is the panel's whole tint and sheen, so a corner
                    // glyph beside the name competes with none of it.
                    RecencyBadge(
                        state = recencyState,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                // "Playing for" reads as accumulated time since detection, not an exact launch
                // time — detection can lag the true start by up to the periodic sync's interval.
                Text(
                    text = stringResource(R.string.home_playing_for, elapsedLabel),
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

internal const val HOME_LEVEL_UP_STATIC_TAG = "home-level-up-static"
internal const val HOME_STREAK_MILESTONE_STATIC_TAG = "home-streak-milestone-static"

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
internal fun CelebrationAnimation(
    @RawRes resId: Int,
    play: Boolean,
    onFinished: () -> Unit,
    staticLabel: String,
    staticTag: String,
    modifier: Modifier = Modifier,
    reducedMotionOverride: Boolean? = null,
) {
    val reducedMotion = reducedMotionOverride ?: rememberReducedMotion()
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = play && !reducedMotion,
        iterations = 1,
        restartOnPlay = true,
    )

    if (play && reducedMotion) {
        Card(
            modifier = modifier.testTag(staticTag),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TablerIcons.Trophy,
                    contentDescription = staticLabel,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    } else if (play) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = modifier,
        )
    }

    LaunchedEffect(play, reducedMotion) {
        if (play && reducedMotion) {
            withFrameNanos { }
            onFinished()
        }
    }
    LaunchedEffect(play, reducedMotion, progress) {
        if (play && !reducedMotion && progress >= 1f) onFinished()
    }
}

/** Stable handle for the Home gap-plan entry, so a navigation test never depends on wording. */
internal const val HOME_PLAN_GAP_TAG = "home-plan-gap"

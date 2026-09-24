package com.example.backlogium.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.res.stringResource
import com.example.backlogium.R
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.util.UiFormat
import com.example.backlogium.data.repo.ContributionState
import compose.icons.TablerIcons
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.CircleMinus
import compose.icons.tablericons.Cloud

internal const val TAG_HISTORY_MEASUREMENT_HELP = "history-measurement-help"
internal const val TAG_HISTORY_LOADING = "history-loading"
internal const val TAG_HISTORY_CLOUD_MARK = "history-cloud-mark"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    onOpenCloudActivity: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()
    HistoryContent(state = state, onLoadOlder = viewModel::loadOlder,
        onOpenCloudActivity = onOpenCloudActivity, reveal = reveal,
        onRevealHandled = viewModel::clearReveal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    onLoadOlder: () -> Unit = {},
    onOpenCloudActivity: () -> Unit = {},
    reveal: HistoryReveal? = null,
    onRevealHandled: () -> Unit = {},
) {

    if (!state.configured) {
        EmptyState(
            title = stringResource(R.string.history_steam_not_configured),
            message = stringResource(R.string.history_steam_not_configured_message),
        )
        return
    }

    if (state.loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TAG_HISTORY_LOADING),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.days.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.history_empty_title),
            message = stringResource(R.string.history_empty_message),
        )
        return
    }

    // Transient — resets on navigation away, per the regroup-history design: this is a lens onto
    // the data, not a saved preference.
    var expandedDays by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedGames by remember { mutableStateOf<Set<Pair<String, Long>>>(emptySet()) }
    var autoExpandedDate by remember { mutableStateOf<String?>(null) }
    var showMeasurementHelp by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.today) {
        // Request identity is checked at publish time: only the current input, job, or date may publish.
        // Keep this in sync across OnboardingViewModel, LibraryViewModel.changeMatch(), and HistoryScreen.
        if (shouldAutoExpandHistoryDate(state.today, autoExpandedDate)) {
            expandedDays = expandedDays + state.today
            autoExpandedDate = state.today
        }
    }

    val toggleDay: (String) -> Unit = { date ->
        expandedDays = if (date in expandedDays) expandedDays - date else expandedDays + date
    }
    val toggleGame: (String, Long) -> Unit = { date, appId ->
        val key = date to appId
        expandedGames = if (key in expandedGames) expandedGames - key else expandedGames + key
    }

    val sections = historySections(state.days, state.today)
    val activity = historyCloudActivity(state.days, state.cloudReaderConfigured)
    LaunchedEffect(reveal, state.days) {
        val target = reveal ?: return@LaunchedEffect
        if (state.days.none { it.date == target.date }) return@LaunchedEffect
        expandedDays = expandedDays + target.date
        expandedGames = expandedGames + (target.date to target.appId)
        withFrameNanos { }
        val dayIndex = 1 + (if (activity.hasContributions) 1 else 0) +
            if (sections.today?.date == target.date) 1 else {
                (if (sections.today != null) 1 + (if (sections.today.date in expandedDays) 1 else 0) else 0) +
                    1 + sections.earlier.takeWhile { it.date != target.date }.sumOf { earlier ->
                    1 + (if (earlier.date in expandedDays) 1 else 0)
                }
            }
        listState.scrollToItem(dayIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        item(key = "history-heading") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showMeasurementHelp = true },
                    modifier = Modifier.testTag(TAG_HISTORY_MEASUREMENT_HELP),
                ) {
                    Text(stringResource(R.string.history_measurement_help_action))
                }
            }
        }

        if (activity.hasContributions) {
            item(key = "cloud-activity") {
                TextButton(onClick = onOpenCloudActivity, modifier = Modifier.fillMaxWidth()) {
                    Icon(TablerIcons.Cloud, contentDescription = null)
                    Text(stringResource(R.string.history_cloud_activity), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        sections.today?.let { day ->
            item(key = "today-heading") {
                SectionHeader(stringResource(R.string.history_today_section))
            }
            dayItems(day, expandedDays, expandedGames, toggleDay, toggleGame,
                state.cloudReaderConfigured, reveal, onRevealHandled)
        }

        if (sections.earlier.isNotEmpty()) {
            item(key = "earlier-history-heading") {
                SectionHeader(stringResource(R.string.history_earlier_section))
            }
        }
        sections.earlier.forEach { day ->
            dayItems(day, expandedDays, expandedGames, toggleDay, toggleGame,
                state.cloudReaderConfigured, reveal, onRevealHandled)
        }

        item(key = "load-older") {
            TextButton(onClick = onLoadOlder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.history_load_older))
            }
        }
    }

    if (showMeasurementHelp) {
        ModalBottomSheet(
            onDismissRequest = { showMeasurementHelp = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.history_measurement_help_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.history_measurement_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = { showMeasurementHelp = false },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.history_measurement_help_close))
                }
            }
        }
    }
}

/** Expand only when a different non-blank date becomes current; data emissions for that date do not reopen it. */
internal fun shouldAutoExpandHistoryDate(today: String, autoExpandedDate: String?): Boolean =
    today.isNotEmpty() && today != autoExpandedDate

/** One day's rows: its header, then (if expanded) a single connected block of its games. */
private fun LazyListScope.dayItems(
    day: HistoryDayGroup,
    expandedDays: Set<String>,
    expandedGames: Set<Pair<String, Long>>,
    onToggleDay: (String) -> Unit,
    onToggleGame: (String, Long) -> Unit,
    cloudReaderConfigured: Boolean,
    reveal: HistoryReveal?,
    onRevealHandled: () -> Unit,
) {
    val dayExpanded = day.date in expandedDays
    val expandable = day.games.isNotEmpty()

    item(key = "day-${day.date}") {
        DayHeaderRow(
            day = day,
            expanded = dayExpanded,
            expandable = expandable,
            onClick = { onToggleDay(day.date) },
        )
    }

    if (!dayExpanded || !expandable) return

    item(key = "games-${day.date}") {
        DayGamesBlock(
            date = day.date,
            games = day.games,
            expandedGames = expandedGames,
            onToggleGame = onToggleGame,
            cloudReaderConfigured = cloudReaderConfigured,
            reveal = reveal,
            onRevealHandled = onRevealHandled,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun DayHeaderRow(
    day: HistoryDayGroup,
    expanded: Boolean,
    expandable: Boolean,
    onClick: () -> Unit,
) {
    val connected = expanded && expandable
    val actionLabel = stringResource(
        if (expanded) R.string.history_collapse_day else R.string.history_expand_day,
    )
    val dayDescription = stringResource(
        R.string.history_day_accessibility,
        formatHistoryDate(day.date),
        daySummary(day),
        if (day.questMet) {
            stringResource(R.string.history_quest_met)
        } else {
            stringResource(R.string.history_quest_not_met)
        },
        if (expandable) {
            if (expanded) {
                stringResource(R.string.history_expanded)
            } else {
                stringResource(R.string.history_collapsed)
            }
        } else {
            stringResource(R.string.history_no_games_to_expand)
        },
    )
    val dayStateDescription = if (expandable) {
        if (expanded) {
            stringResource(R.string.history_expanded)
        } else {
            stringResource(R.string.history_collapsed)
        }
    } else {
        stringResource(R.string.history_no_games_to_expand)
    }
    Card(
        shape = dayCardShape(topOnly = connected),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = if (connected) 0.dp else 4.dp)
            .let { modifier ->
                modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = dayDescription
                        stateDescription = dayStateDescription
                    }
                    .let {
                        if (expandable) {
                            it.clickable(
                                role = Role.Button,
                                onClickLabel = actionLabel,
                                onClick = onClick,
                            )
                        } else {
                            it
                        }
                    }
            },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatHistoryDate(day.date),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = daySummary(day),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expandable) {
                        Icon(
                            imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                            contentDescription = null,
                        )
                    }
                    Icon(
                        imageVector = if (day.questMet) TablerIcons.CircleCheck else TablerIcons.CircleMinus,
                        contentDescription = null,
                        tint = if (day.questMet) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (day.gameThumbnails.games.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    day.gameThumbnails.games.forEach { game ->
                        GameIcon(
                            iconUrl = game.iconUrl,
                            iconSize = 20.dp,
                            shape = CircleShape,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    if (day.gameThumbnails.overflowCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.history_overflow_count,
                                UiFormat.count(day.gameThumbnails.overflowCount),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (day.achievements.iconUrls.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(
                        top = if (day.gameThumbnails.games.isNotEmpty()) 4.dp else 8.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    day.achievements.iconUrls.forEach { icon ->
                        AchievementThumbnail(icon, modifier = Modifier.padding(end = 4.dp))
                    }
                    if (day.achievements.overflowCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.history_overflow_count,
                                UiFormat.count(day.achievements.overflowCount),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementThumbnail(iconUrl: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = modifier
            .size(20.dp)
            .clip(shape),
        loading = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        },
        error = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        },
    )
}

/** Shared corner radius for [DayHeaderRow] and [DayGamesBlock]. */
private val dayCardCornerRadius = 12.dp

/**
 * Rounded on every corner by default. Pass `topOnly = true` for the header when its games block is
 * showing beneath it (flat bottom), and `bottomOnly = true` for the games block itself (flat top),
 * so the header and the block read as one continuous card rather than two stacked ones.
 */
private fun dayCardShape(topOnly: Boolean = false, bottomOnly: Boolean = false): RoundedCornerShape =
    RoundedCornerShape(
        topStart = if (bottomOnly) 0.dp else dayCardCornerRadius,
        topEnd = if (bottomOnly) 0.dp else dayCardCornerRadius,
        bottomStart = if (topOnly) 0.dp else dayCardCornerRadius,
        bottomEnd = if (topOnly) 0.dp else dayCardCornerRadius,
    )

/** Width of the left-hand gutter that holds the timeline line and each game's dot. */
private val historyTimelineGutterWidth = 32.dp

/**
 * All of a day's games as one continuous card, directly beneath [DayHeaderRow] (flat top meeting
 * the header's flat bottom). A single vertical line runs down the left gutter behind every game
 * row — and past a game's sessions when it's expanded — so the whole block visibly reads as "all
 * of this belongs to this one day," rather than a stack of separate, only-slightly-indented cards.
 */
@Composable
private fun DayGamesBlock(
    date: String,
    games: List<HistoryGameGroup>,
    expandedGames: Set<Pair<String, Long>>,
    onToggleGame: (String, Long) -> Unit,
    cloudReaderConfigured: Boolean,
    reveal: HistoryReveal?,
    onRevealHandled: () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    // Tracked so the line can stop exactly at the last game's dot instead of running past it to the
    // bottom of the card — recomputed on layout since an earlier game expanding shifts the last
    // dot's position down.
    var columnCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastDotCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Card(
        shape = dayCardShape(bottomOnly = true),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { columnCoordinates = it }
                .drawBehind {
                    val column = columnCoordinates
                    val dot = lastDotCoordinates
                    val lineEndY = if (column != null && dot != null) {
                        column.localPositionOf(dot, Offset(dot.size.width / 2f, dot.size.height / 2f)).y
                    } else {
                        size.height
                    }
                    val lineX = historyTimelineGutterWidth.toPx() / 2f
                    drawLine(
                        color = lineColor,
                        start = Offset(lineX, 0f),
                        end = Offset(lineX, lineEndY),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                .padding(vertical = 6.dp),
        ) {
            games.forEachIndexed { index, game ->
                val gameKey = date to game.appId
                val gameExpanded = gameKey in expandedGames

                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = historyTimelineGutterWidth),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }

                GameRow(
                    game = game,
                    expanded = gameExpanded,
                    dotColor = lineColor,
                    onClick = { onToggleGame(date, game.appId) },
                    onDotPositioned = if (index == games.lastIndex) {
                        { lastDotCoordinates = it }
                    } else {
                        null
                    },
                )

                if (gameExpanded) {
                    Column(
                        modifier = Modifier
                            .padding(
                                start = historyTimelineGutterWidth + 8.dp,
                                end = 12.dp,
                                bottom = 10.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        game.sessions.forEach { session ->
                            HistorySessionRow(session, cloudReaderConfigured,
                                reveal?.takeIf { it.date == date && it.appId == game.appId && it.sessionId == session.id },
                                onRevealHandled)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySessionRow(
    session: HistorySessionUi,
    cloudReaderConfigured: Boolean,
    reveal: HistoryReveal? = null,
    onRevealHandled: () -> Unit = {},
) {
    var showExplanation by remember(session.id) { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal != null) {
            bringIntoViewRequester.bringIntoView()
            onRevealHandled()
        }
    }
    val contribution = session.cloudContribution
    val hasMark = cloudReaderConfigured && contribution.hasRecordedContribution()
    val recovery = contribution.recoveredSharedPlay
    val timing = contribution.timingInformedSteamPlay
    val markLabel = listOfNotNull(
        if (recovery == ContributionState.FULL || recovery == ContributionState.PARTIAL) {
            stringResource(if (recovery == ContributionState.PARTIAL)
                R.string.history_cloud_recovered_partial else R.string.history_cloud_recovered)
        } else null,
        if (timing == ContributionState.FULL || timing == ContributionState.PARTIAL) {
            stringResource(if (timing == ContributionState.PARTIAL)
                R.string.history_cloud_timed_partial else R.string.history_cloud_timed)
        } else null,
    ).joinToString(". ")
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester)) {
        Text(
            text = sessionLabel(session),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (hasMark) {
            TextButton(
                onClick = { showExplanation = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_HISTORY_CLOUD_MARK)
                    .semantics { contentDescription = markLabel },
            ) {
                Icon(TablerIcons.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.history_cloud_mark), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    if (hasMark && showExplanation) {
        ModalBottomSheet(
            onDismissRequest = { showExplanation = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.history_cloud_explanation_title),
                    style = MaterialTheme.typography.titleLarge)
                if (recovery == ContributionState.FULL || recovery == ContributionState.PARTIAL) {
                    Text(stringResource(if (recovery == ContributionState.PARTIAL)
                        R.string.history_cloud_recovered_partial_body else R.string.history_cloud_recovered_body))
                }
                if (timing == ContributionState.FULL || timing == ContributionState.PARTIAL) {
                    Text(stringResource(if (timing == ContributionState.PARTIAL)
                        R.string.history_cloud_timed_partial_body else R.string.history_cloud_timed_body))
                }
                TextButton(onClick = { showExplanation = false }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.history_measurement_help_close))
                }
            }
        }
    }
}

/** One game's row inside [DayGamesBlock]: a dot on the timeline, its icon, name, playtime, and chevron. */
@Composable
private fun GameRow(
    game: HistoryGameGroup,
    expanded: Boolean,
    dotColor: Color,
    onClick: () -> Unit,
    onDotPositioned: ((LayoutCoordinates) -> Unit)? = null,
) {
    val actionLabel = stringResource(
        if (expanded) R.string.history_collapse_game else R.string.history_expand_game,
    )
    val gameDescription = stringResource(
        R.string.history_game_accessibility,
        game.name,
        UiFormat.localizedMinutes(game.minutesPlayed),
    )
    val gameStateDescription = if (expanded) {
        stringResource(R.string.history_expanded)
    } else {
        stringResource(R.string.history_collapsed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = gameDescription
                stateDescription = gameStateDescription
            }
            .clickable(
                role = Role.Button,
                onClickLabel = actionLabel,
                onClick = onClick,
            )
            .padding(top = 10.dp, bottom = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier.width(historyTimelineGutterWidth),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .let { m -> onDotPositioned?.let { m.onGloballyPositioned(it) } ?: m },
                )
            }
            GameIcon(game.iconUrl)
            Text(
                text = game.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = UiFormat.localizedMinutes(game.minutesPlayed),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(
                imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                contentDescription = null,
            )
        }
    }
}

/**
 * "~12:43 AM · 23m played" (plus "· live" while open). Deliberately an approximate *start*, not a
 * start–end range: a two-endpoint range invites subtracting them into a "duration" that can
 * legitimately disagree with the tracked minutes once Steam's own counter lags, which reads as an
 * arithmetic error rather than two honest, different measurements (see `SessionDiffer`).
 */
@Composable
private fun sessionLabel(session: HistorySessionUi): String {
    val start = UiFormat.approxTime(session.startAt)
    return if (session.open) {
        stringResource(
            R.string.history_session_live,
            start,
            UiFormat.localizedMinutes(session.minutes),
        )
    } else {
        stringResource(
            R.string.history_session,
            start,
            UiFormat.localizedMinutes(session.minutes),
        )
    }
}

@Composable
private fun daySummary(day: HistoryDayGroup): String =
    if (day.goalMinutesPlayed > 0) {
        stringResource(
            R.string.history_day_summary_with_focus,
            UiFormat.localizedMinutes(day.minutesPlayed),
            UiFormat.localizedMinutes(day.goalMinutesPlayed),
        )
    } else {
        stringResource(R.string.history_day_summary, UiFormat.localizedMinutes(day.minutesPlayed))
    }

package com.example.backlogium.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.util.UiFormat
import com.example.backlogium.work.HltbBatchProgress
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsSort
import compose.icons.tablericons.Check
import compose.icons.tablericons.Checkbox
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.Search
import compose.icons.tablericons.Trophy
import compose.icons.tablericons.X

/**
 * Mutable dialog state: which game is being edited and whether it is already tracked. The
 * `isGoal` name matches the persisted flag; the user-facing wording is "Focus" throughout (see
 * the label/identifier mismatch noted in the enhance-library design).
 */
private data class GoalDialogTarget(
    val appId: Long,
    val name: String,
    val isGoal: Boolean,
)

@Composable
fun LibraryScreen(
    onOpenReview: () -> Unit = {},
    onOpenGameDetail: (Long) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialogTarget by remember { mutableStateOf<GoalDialogTarget?>(null) }

    // Selection is transient: leaving the Library drops it, so it can never outlive the screen
    // that shows the count.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearSelection() }
    }

    if (!state.configured) {
        EmptyState(
            title = "Steam not configured",
            message = "Connect your Steam account from the Home screen to load your library.",
        )
        return
    }

    // Keyed to the *unfiltered* library. If the filtered lists fed this, a query matching nothing
    // would unmount the search field along with everything else, leaving no way to clear the query
    // that caused it.
    if (state.libraryEmpty) {
        EmptyState(
            title = "No games yet",
            message = "Once a sync completes, your Steam library appears here. " +
                "If it stays empty, your profile may be private.",
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectionMode) {
            SelectionBar(
                count = state.selection.size,
                // Both paths enqueue under one unique work name with KEEP, so a selection tapped
                // during a sweep would be dropped with no error — gate it like HltbControls does.
                refreshing = state.refreshing,
                onRefreshSelection = viewModel::refreshSelection,
                onClear = viewModel::clearSelection,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            item {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onClear = viewModel::clearQuery,
                )
            }

            item {
                HltbControls(
                    refreshing = state.refreshing,
                    reviewCount = state.reviewCount,
                    onRefresh = { viewModel.refreshHltb(force = false) },
                    onForceRefresh = { viewModel.refreshHltb(force = true) },
                    onOpenReview = onOpenReview,
                )
            }

            if (state.refreshing) {
                item {
                    BatchProgressPanel(progress = state.batchProgress, log = state.batchLog)
                }
            }

            if (state.goalGames.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = "Focus",
                        sort = state.focusSort,
                        onSortChange = viewModel::setFocusSort,
                    )
                }
                items(state.goalGames, key = { it.appId }) { game ->
                    GoalGameRow(
                        game = game,
                        selected = game.appId in state.selection,
                        selectionMode = state.selectionMode,
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelection(game.appId)
                            } else {
                                onOpenGameDetail(game.appId)
                            }
                        },
                        onLongClick = { viewModel.toggleSelection(game.appId) },
                        onManageGoal = {
                            dialogTarget = GoalDialogTarget(
                                appId = game.appId,
                                name = game.name,
                                isGoal = true,
                            )
                        },
                    )
                }
            }

            // Heading only for a section that has matches — with a filter active, an empty
            // "Your games" heading would describe nothing.
            if (state.backlog.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = "Your games",
                        sort = state.librarySort,
                        onSortChange = viewModel::setLibrarySort,
                    )
                }
                items(state.backlog, key = { it.appId }) { game ->
                    BacklogGameRow(
                        game = game,
                        selected = game.appId in state.selection,
                        selectionMode = state.selectionMode,
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelection(game.appId)
                            } else {
                                onOpenGameDetail(game.appId)
                            }
                        },
                        onLongClick = { viewModel.toggleSelection(game.appId) },
                        onManageGoal = {
                            dialogTarget = GoalDialogTarget(
                                appId = game.appId,
                                name = game.name,
                                isGoal = false,
                            )
                        },
                    )
                }
            }

            // Inside the column, beneath the search field: the query that produced no matches
            // stays visible and clearable.
            if (state.noMatches) {
                item { NoMatchesRow(query = state.query, onClear = viewModel::clearQuery) }
            }
        }
    }

    dialogTarget?.let { target ->
        // Read live status/op so the dialog reflects a lookup started from within it.
        val liveGoal = state.goalGames.firstOrNull { it.appId == target.appId }
        val liveBacklog = state.backlog.firstOrNull { it.appId == target.appId }
        GoalDialog(
            target = target,
            hltbStatus = liveGoal?.hltbStatus ?: liveBacklog?.hltbStatus,
            fetchOp = liveGoal?.fetchOp ?: liveBacklog?.fetchOp,
            onDismiss = { dialogTarget = null },
            onTag = {
                viewModel.tagGoal(target.appId)
                dialogTarget = null
            },
            onUntag = {
                viewModel.untagGoal(target.appId)
                dialogTarget = null
            },
            onRefresh = { viewModel.refreshGame(target.appId, target.name) },
        )
    }
}

/** Name filter over the loaded library. Instant: nothing is re-queried per keystroke. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        singleLine = true,
        label = { Text("Search games") },
        leadingIcon = {
            Icon(
                imageVector = TablerIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = TablerIcons.X,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

/**
 * Batch HLTB refresh control (with a force-all option) plus the match-review entry point.
 * Reflects the running state via [refreshing]; surfaces the pending [reviewCount].
 */
@Composable
private fun HltbControls(
    refreshing: Boolean,
    reviewCount: Int,
    onRefresh: () -> Unit,
    onForceRefresh: () -> Unit,
    onOpenReview: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !refreshing,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Refreshing…")
                } else {
                    Text("Refresh HLTB library")
                }
            }
            OutlinedButton(
                onClick = onForceRefresh,
                enabled = !refreshing,
            ) {
                Text("Force all")
            }
        }
        TextButton(
            onClick = onOpenReview,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                if (reviewCount > 0) "Review HLTB matches ($reviewCount)" else "Review HLTB matches",
            )
        }
    }
}

/**
 * Live state of a running sweep: how far it has got, and what each processed game resolved to.
 *
 * A null [progress] is not a stalled run. It covers a sweep that is enqueued but has not reported
 * its first game yet, and a sweep whose target set turned out to be empty (the repository only
 * reports from inside its loop) — so it renders as indeterminate rather than as `0 / 0`.
 */
@Composable
private fun BatchProgressPanel(progress: HltbBatchProgress?, log: List<HltbLogEntry>) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (progress == null || progress.total <= 0) {
                Text(
                    text = "Starting HowLongToBeat refresh…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                return@Column
            }

            Text(
                text = "${progress.done} / ${progress.total}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Newest first, in a fixed-height scroller: the log is a progress aid, not a
                // record, and it is never persisted across process death.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    log.asReversed().forEach { entry ->
                        Text(
                            text = "${entry.gameName} — ${outcomeLabel(entry.outcome)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A null outcome is a failed lookup — distinct from a search that found no candidates. */
private fun outcomeLabel(outcome: HltbMatchState?): String = when (outcome) {
    HltbMatchState.RESOLVED -> "matched"
    HltbMatchState.NEEDS_REVIEW -> "needs review"
    HltbMatchState.UNMATCHED -> "no match"
    null -> "lookup failed"
}

/**
 * Action bar for the transient multi-select. The count includes games the active filter hides, so
 * a selection is never silently narrowed by typing in the search field.
 */
@Composable
private fun SelectionBar(
    count: Int,
    refreshing: Boolean,
    onRefreshSelection: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefreshSelection, enabled = !refreshing) {
            Text("HowLongToBeat lookup ($count)")
        }
        IconButton(onClick = onClear) {
            Icon(imageVector = TablerIcons.X, contentDescription = "Clear selection")
        }
    }
}

/** Section heading plus that list's own sort control; the two lists sort independently. */
@Composable
private fun SectionHeader(
    text: String,
    sort: LibrarySortKey,
    onSortChange: (LibrarySortKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        SortControl(sort = sort, onSortChange = onSortChange)
    }
}

/** Compact menu showing the active key by name — the sort labels name what they order by. */
@Composable
private fun SortControl(sort: LibrarySortKey, onSortChange: (LibrarySortKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                imageVector = TablerIcons.ArrowsSort,
                contentDescription = "Change sort order",
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(librarySortLabel(sort), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(librarySortLabel(key)) },
                    onClick = {
                        onSortChange(key)
                        expanded = false
                    },
                    trailingIcon = {
                        if (key == sort) {
                            Icon(
                                imageVector = TablerIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** Filter matched nothing. Rendered in-list so the search field above it stays reachable. */
@Composable
private fun NoMatchesRow(query: String, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text(
            text = "No games match \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
            Text("Clear search")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GoalGameRow(
    game: GoalGameUi,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManageGoal: () -> Unit,
) {
    val completed = isGameCompleted(game.achievementUnlocked, game.achievementTotal)
    GameCard(
        selected = selected,
        completed = completed,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        GameIcon(game.iconUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(game.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = UiFormat.minutes(game.playtimeForever) + " played",
                style = MaterialTheme.typography.bodySmall,
            )
            CompletionProgress(
                playtimeMinutes = game.playtimeForever,
                completionistMinutes = game.completionistMinutes,
            )
            Spacer(Modifier.height(4.dp))
            HltbStatusLabel(status = game.hltbStatus, op = game.fetchOp)
            GameBadges(
                unlocked = game.achievementUnlocked,
                total = game.achievementTotal,
                xpContributed = game.xpContributed,
            )
        }
        RowTrailing(
            selected = selected,
            selectionMode = selectionMode,
            onManageGoal = onManageGoal,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BacklogGameRow(
    game: BacklogGameUi,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManageGoal: () -> Unit,
) {
    val completed = isGameCompleted(game.achievementUnlocked, game.achievementTotal)
    GameCard(
        selected = selected,
        completed = completed,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        GameIcon(game.iconUrl)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(game.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = UiFormat.minutes(game.playtimeForever) + " played",
                style = MaterialTheme.typography.bodySmall,
            )
            CompletionProgress(
                playtimeMinutes = game.playtimeForever,
                completionistMinutes = game.completionistMinutes,
            )
            // Only surface HLTB state for untracked games once there is something to report,
            // so the common "no data" case stays uncluttered.
            if (game.hltbStatus != null || game.fetchOp != null) {
                Spacer(Modifier.height(4.dp))
                HltbStatusLabel(status = game.hltbStatus, op = game.fetchOp)
            }
            GameBadges(
                unlocked = game.achievementUnlocked,
                total = game.achievementTotal,
                xpContributed = game.xpContributed,
            )
        }
        RowTrailing(
            selected = selected,
            selectionMode = selectionMode,
            onManageGoal = onManageGoal,
        )
    }
}

/**
 * The shared row shell. Material 3's `Card(onClick = …)` has no long-press, so the card is
 * non-clickable and carries [combinedClickable] instead — long-press enters selection mode while
 * tap keeps its existing meaning.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(
    selected: Boolean,
    completed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = selectionBorder(selected) ?: completedBorder(completed),
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

/** While selecting, the 3-dot menu gives way to the row's selected state. */
@Composable
private fun RowTrailing(selected: Boolean, selectionMode: Boolean, onManageGoal: () -> Unit) {
    if (selectionMode) {
        Icon(
            imageVector = if (selected) TablerIcons.Check else TablerIcons.Checkbox,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        return
    }
    IconButton(onClick = onManageGoal) {
        Icon(imageVector = TablerIcons.DotsVertical, contentDescription = "Manage focus")
    }
}

/**
 * Progress toward the HowLongToBeat Completionist length, for **any** game that has one — the
 * batch refresh fetches a length for the whole library, so this is not a tracked-games privilege.
 * A game with no length yet renders nothing at all: no bar, no placeholder.
 */
@Composable
private fun CompletionProgress(playtimeMinutes: Int, completionistMinutes: Int?) {
    val completionist = completionistMinutes ?: return
    val fraction = Gamification.goalProgress(playtimeMinutes, completionist).fraction.toFloat()
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = "${UiFormat.minutes(playtimeMinutes)} / ${UiFormat.minutes(completionist)} to 100%",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Compact, live HLTB state for a game: in-flight, failed, or the persisted match status. */
@Composable
private fun HltbStatusLabel(
    status: HltbMatchState?,
    op: HltbFetchOp?,
    modifier: Modifier = Modifier,
) {
    when {
        op == HltbFetchOp.IN_PROGRESS -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Looking up HowLongToBeat…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        op == HltbFetchOp.FAILED -> Text(
            text = "HowLongToBeat lookup failed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )

        status == HltbMatchState.RESOLVED -> Text(
            text = "HowLongToBeat matched",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )

        status == HltbMatchState.NEEDS_REVIEW -> Text(
            text = "Needs match review",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = modifier,
        )

        status == HltbMatchState.UNMATCHED -> Text(
            text = "No HowLongToBeat match",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        else -> Text(
            text = "No HowLongToBeat data yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/** True once a game's achievement counts show every known achievement unlocked. */
private fun isGameCompleted(unlocked: Int?, total: Int?): Boolean =
    total != null && total > 0 && unlocked == total

/** Gold outline reserved for a fully-completed game's row; null (no border) otherwise. */
@Composable
private fun completedBorder(completed: Boolean): BorderStroke? =
    if (completed) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null

/** Selection outline, which takes precedence over the completion one while selecting. */
@Composable
private fun selectionBorder(selected: Boolean): BorderStroke? =
    if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null

/**
 * The row's badge line: achievement counts and contributed XP together.
 *
 * The XP badge is deliberately the quietest thing on the row — plain muted text, no pill or icon
 * — because every row can now also carry a progress bar. If the row still reads as crowded, this
 * is the first element to drop.
 */
@Composable
private fun GameBadges(unlocked: Int?, total: Int?, xpContributed: Int) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AchievementCountLabel(unlocked = unlocked, total = total)
        if (unlocked != null && total != null) Spacer(Modifier.width(8.dp))
        XpContributionLabel(xpContributed)
    }
}

/**
 * XP this game contributed to the player's total — tapered playtime XP over tracked (and
 * imported) minutes plus its achievements' rarity XP, so every row's badge adds up to the
 * player's real total.
 *
 * Deliberately *not* proportional to the "120h played" text above it: lifetime Steam playtime
 * includes pre-install hours that only earn XP if the player imported their history, and playtime
 * XP tapers toward zero past a game's completion length. `0 XP` on a long-owned game is correct.
 */
@Composable
private fun XpContributionLabel(xpContributed: Int) {
    Text(
        text = "$xpContributed XP contributed",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Compact "unlocked / total" achievement badge; shown only once achievement data exists. Once
 * every achievement is unlocked, this becomes a striking gold "100% Completed" pill instead of
 * the plain count, so a fully-completed game is unmistakable at a glance in the list.
 */
@Composable
private fun AchievementCountLabel(unlocked: Int?, total: Int?) {
    if (unlocked == null || total == null) return
    if (isGameCompleted(unlocked, total)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.Trophy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "100% COMPLETED",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = TablerIcons.Trophy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$unlocked / $total achievements",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GameIcon(iconUrl: String) {
    val shape = RoundedCornerShape(8.dp)
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(shape),
        // Themed placeholder while the Steam CDN thumbnail loads.
        loading = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        // Themed fallback (generic controller glyph) when the image fails to load.
        error = {
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
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    )
}

/**
 * Confirm adding a game to Focus or removing it (no typed target — completion lengths come
 * from HowLongToBeat), and surface/refresh this game's HLTB state: the current match status,
 * plus a "Refresh HowLongToBeat" action that forces a fresh single-game lookup.
 */
@Composable
private fun GoalDialog(
    target: GoalDialogTarget,
    hltbStatus: HltbMatchState?,
    fetchOp: HltbFetchOp?,
    onDismiss: () -> Unit,
    onTag: () -> Unit,
    onUntag: () -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.isGoal) "Remove from Focus" else "Add to Focus") },
        text = {
            Column {
                Text(
                    text = if (target.isGoal) {
                        "Remove \"${target.name}\" from Focus?"
                    } else {
                        "Add \"${target.name}\" to Focus? Its playtime is then tracked separately."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                HltbStatusLabel(status = hltbStatus, op = fetchOp)
                TextButton(
                    onClick = onRefresh,
                    enabled = fetchOp != HltbFetchOp.IN_PROGRESS,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("Refresh HowLongToBeat")
                }
            }
        },
        confirmButton = {
            if (target.isGoal) {
                TextButton(onClick = onUntag) { Text("Remove") }
            } else {
                TextButton(onClick = onTag) { Text("Add") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

package com.example.backlogium.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.HltbRefreshOutcome
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.components.GameHeaderBackdrop
import com.example.backlogium.ui.components.GameHeroCapsule
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.components.GameListDensityControl
import com.example.backlogium.ui.components.HltbCandidateRow
import com.example.backlogium.ui.collections.GenreFilterChoice
import com.example.backlogium.ui.collections.genreFilterCatalog
import com.example.backlogium.ui.theme.overrunExcess
import com.example.backlogium.ui.theme.playingIndicator
import com.example.backlogium.ui.components.RecencyBadge
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.ui.util.UiFormat
import com.example.backlogium.ui.util.rememberHaptics
import com.example.backlogium.work.HltbBatchProgress
import com.example.backlogium.work.HltbRefreshStatus
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.ArrowsSort
import compose.icons.tablericons.Bolt
import compose.icons.tablericons.Check
import compose.icons.tablericons.Checkbox
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.PlayerStop
import compose.icons.tablericons.Search
import compose.icons.tablericons.TrendingUp
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

/** Common display shape used by both Library sections and all three density renderers. */
private data class LibraryDisplayGame(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val headerUrl: String,
    val heroCapsuleUrl: String,
    val playtimeForever: Int,
    val completionistMinutes: Int?,
    val hltbStatus: HltbMatchState,
    val fetchOp: HltbFetchOp?,
    val achievementUnlocked: Int?,
    val achievementTotal: Int?,
    val xpContributed: Int,
    val isCurrentlyPlaying: Boolean,
    /**
     * Played through Family Sharing rather than owned. Marked in words on the row rather than by
     * colour, and it changes what [playtimeForever] means: a shared game has no Steam total, so
     * the figure is what the app observed and the row says so.
     */
    val isFamilyShared: Boolean = false,
    val recencyState: GameRecencyState? = null,
)

/** Keep the owned-library empty state from hiding a separately readable wishlist. */
internal fun shouldShowFullScreenLibraryEmptyState(
    libraryState: LibraryUiState,
    wishlistState: WishlistUiState,
): Boolean = libraryState.libraryEmpty && !wishlistState.configured

internal fun shouldShowWishlistSection(
    libraryState: LibraryUiState,
    wishlistState: WishlistUiState,
    selectedGenreSet: Set<String>,
): Boolean = wishlistState.configured &&
    (libraryState.libraryEmpty ||
        (libraryState.query.isBlank() && selectedGenreSet.isEmpty()))

@Composable
private fun LibraryEmptyNotice() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No games yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Once a sync completes, your Steam library appears here. " +
                "If it stays empty, your profile may be private.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LibraryScreen(
    onOpenReview: () -> Unit = {},
    onOpenGameDetail: (Long) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    wishlistViewModel: WishlistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val wishlistState by wishlistViewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val haptics = rememberHaptics()
    var dialogTarget by remember { mutableStateOf<GoalDialogTarget?>(null) }
    var pickerTarget by remember { mutableStateOf<GoalDialogTarget?>(null) }
    var selectedGenreIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showNotCoveredOnly by rememberSaveable { mutableStateOf(false) }
    var showGenreSheet by rememberSaveable { mutableStateOf(false) }
    val selectedGenreSet = selectedGenreIds.toSet()
    val genreCatalog = remember(state.availableGenres) {
        genreFilterCatalog(state.availableGenres)
    }
    val visibleGoalGames = remember(state.goalGames, selectedGenreIds, showNotCoveredOnly) {
        state.goalGames
            .filterByGenres(selectedGenreSet)
            .filterByHltbCoverage(showNotCoveredOnly) { it.hltbStatus }
    }
    val visibleBacklog = remember(state.backlog, selectedGenreIds, showNotCoveredOnly) {
        state.backlog
            .filterByGenres(selectedGenreSet)
            .filterByHltbCoverage(showNotCoveredOnly) { it.hltbStatus }
    }
    val noVisibleMatches =
        (state.query.isNotBlank() || selectedGenreSet.isNotEmpty() || showNotCoveredOnly) &&
            visibleGoalGames.isEmpty() &&
            visibleBacklog.isEmpty()

    fun toggleSelection(appId: Long) {
        val entering = !state.selectionMode
        val leaving = state.selectionMode && state.selection.size == 1 && appId in state.selection
        viewModel.toggleSelection(appId)
        if (entering || leaving) {
            haptics.play(HapticIntent.Toggle(enabled = entering))
        }
    }

    fun exitSelectionMode(action: () -> Unit) {
        val wasInSelectionMode = state.selectionMode
        action()
        if (wasInSelectionMode) {
            haptics.play(HapticIntent.Toggle(enabled = false))
        }
    }

    // Selection is transient: leaving the Library drops it, so it can never outlive the screen
    // that shows the count.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSelection()
            selectedGenreIds = emptyList()
            showNotCoveredOnly = false
            showGenreSheet = false
        }
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
    // that caused it. A configured wishlist is the one independent surface that must remain
    // reachable even when this owned-library state is empty.
    if (shouldShowFullScreenLibraryEmptyState(state, wishlistState)) {
        EmptyState(
            title = "No games yet",
            message = "Once a sync completes, your Steam library appears here. " +
                "If it stays empty, your profile may be private.",
        )
        return
    }

    if (state.libraryEmpty) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                if (shouldShowWishlistSection(state, wishlistState, selectedGenreSet)) {
                    wishlistSection(
                        state = wishlistState,
                        density = state.density,
                        onToggle = wishlistViewModel::setExpanded,
                        onOpenStore = { uriHandler.openUri(it.storeUrl) },
                    )
                }
                item { LibraryEmptyNotice() }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectionMode) {
            SelectionBar(
                count = state.selection.size,
                // Both paths enqueue under one unique work name with KEEP, so a selection tapped
                // during a sweep would be dropped with no error — gate it like HltbControls does.
                refreshing = state.refreshing,
                onRefreshSelection = { exitSelectionMode(viewModel::refreshSelection) },
                onClear = { exitSelectionMode(viewModel::clearSelection) },
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchField(
                            query = state.query,
                            onQueryChange = viewModel::setQuery,
                            onClear = viewModel::clearQuery,
                            modifier = Modifier.weight(1f),
                        )
                        GameListDensityControl(
                            density = state.density,
                            onDensityChange = viewModel::setDensity,
                        )
                        HltbMenuButton(
                            refreshing = state.refreshing,
                            reviewCount = state.reviewCount,
                            onRefresh = { viewModel.refreshHltb(force = false) },
                            onForceRefresh = { viewModel.refreshHltb(force = true) },
                            onOpenReview = onOpenReview,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GenreFilterButton(
                            selectedCount = selectedGenreSet.size,
                            enabled = genreCatalog.isNotEmpty(),
                            onClick = { showGenreSheet = true },
                        )
                        FilterChip(
                            selected = showNotCoveredOnly,
                            onClick = { showNotCoveredOnly = !showNotCoveredOnly },
                            label = { Text("Not covered") },
                        )
                    }
                }
            }

            if (selectedGenreSet.isNotEmpty()) {
                item {
                    ActiveGenreFilters(
                        genres = genreCatalog.filter { it.id in selectedGenreSet },
                        onRemove = { genre ->
                            selectedGenreIds = selectedGenreIds - genre.id
                        },
                        onClear = { selectedGenreIds = emptyList() },
                    )
                }
            }

            if (state.refreshing) {
                item {
                    BatchProgressPanel(
                        status = state.hltbRefreshStatus,
                        waitRemainingSeconds = state.hltbWaitRemainingSeconds,
                        progress = state.batchProgress,
                        log = state.batchLog,
                        onStop = viewModel::stopHltbRefresh,
                    )
                }
            }

            // Above the owned lists, and only while nothing is being searched or filtered:
            // those controls act on the owned library, and an unfiltered wishlist sitting under a
            // query would read as a result of it.
            if (state.query.isBlank() && selectedGenreSet.isEmpty() && !showNotCoveredOnly) {
                wishlistSection(
                    state = wishlistState,
                    density = state.density,
                    onToggle = wishlistViewModel::setExpanded,
                    onOpenStore = { uriHandler.openUri(it.storeUrl) },
                )
            }

            if (visibleGoalGames.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = "Focus",
                        sort = state.focusSort,
                        direction = state.focusSortDirection,
                        onSortChange = viewModel::setFocusSort,
                        onDirectionChange = viewModel::setFocusSortDirection,
                    )
                }
                libraryGameItems(
                    games = visibleGoalGames.map(GoalGameUi::toDisplayGame),
                    density = state.density,
                    selectedIds = state.selection,
                    selectionMode = state.selectionMode,
                    onClick = { game ->
                        if (state.selectionMode) toggleSelection(game.appId)
                        else onOpenGameDetail(game.appId)
                    },
                    onLongClick = { game -> toggleSelection(game.appId) },
                    onManageGoal = { game ->
                        dialogTarget = GoalDialogTarget(
                            appId = game.appId,
                            name = game.name,
                            isGoal = true,
                        )
                    },
                )
            }

            // Heading only for a section that has matches — with a filter active, an empty
            // "Your games" heading would describe nothing.
            if (visibleBacklog.isNotEmpty()) {
                item {
                    SectionHeader(
                        text = "Your games",
                        sort = state.librarySort,
                        direction = state.librarySortDirection,
                        onSortChange = viewModel::setLibrarySort,
                        onDirectionChange = viewModel::setLibrarySortDirection,
                    )
                }
                libraryGameItems(
                    games = visibleBacklog.map(BacklogGameUi::toDisplayGame),
                    density = state.density,
                    selectedIds = state.selection,
                    selectionMode = state.selectionMode,
                    onClick = { game ->
                        if (state.selectionMode) toggleSelection(game.appId)
                        else onOpenGameDetail(game.appId)
                    },
                    onLongClick = { game -> toggleSelection(game.appId) },
                    onManageGoal = { game ->
                        dialogTarget = GoalDialogTarget(
                            appId = game.appId,
                            name = game.name,
                            isGoal = false,
                        )
                    },
                )
            }

            // Inside the column, beneath the search field: the query that produced no matches
            // stays visible and clearable.
            if (noVisibleMatches) {
                item {
                    NoMatchesRow(
                        query = state.query,
                        hasGenreFilter = selectedGenreSet.isNotEmpty(),
                        hasCoverageFilter = showNotCoveredOnly,
                        onClear = viewModel::clearQuery,
                        onClearGenres = { selectedGenreIds = emptyList() },
                        onClearCoverage = { showNotCoveredOnly = false },
                    )
                }
            }
        }
    }

    dialogTarget?.let { target ->
        // Read live status/op so the dialog reflects a lookup started from within it.
        val liveGoal = state.goalGames.firstOrNull { it.appId == target.appId }
        val liveBacklog = state.backlog.firstOrNull { it.appId == target.appId }
        GoalDialog(
            target = target,
            hltbStatus = liveGoal?.hltbStatus
                ?: liveBacklog?.hltbStatus
                ?: HltbMatchState.NOT_COVERED,
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
            onChooseMatch = {
                viewModel.clearPicker(target.appId)
                pickerTarget = target
                dialogTarget = null
            },
            onChangeMatch = {
                pickerTarget = target
                dialogTarget = null
                viewModel.changeMatch(target.appId, target.name)
            },
        )
    }

    pickerTarget?.let { target ->
        val retainedCandidates = state.hltbCandidatesByAppId[target.appId].orEmpty()
        val transient = state.pickerStates[target.appId]
        HltbPickerSheet(
            gameName = target.name,
            candidates = transient?.candidates ?: retainedCandidates,
            loading = transient?.loading == true,
            failed = transient?.failed == true,
            onDismiss = {
                viewModel.clearPicker(target.appId)
                pickerTarget = null
            },
            onSelect = { candidate ->
                viewModel.resolveMatch(target.appId, candidate)
                viewModel.clearPicker(target.appId)
                pickerTarget = null
            },
        )
    }

    if (showGenreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGenreSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text("Filter by genres", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                genreCatalog.forEach { genre ->
                    FilterChip(
                        selected = genre.id in selectedGenreSet,
                        onClick = {
                            selectedGenreIds = if (genre.id in selectedGenreSet) {
                                selectedGenreIds - genre.id
                            } else {
                                selectedGenreIds + genre.id
                            }
                        },
                        label = { Text(genre.label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HltbPickerSheet(
    gameName: String,
    candidates: List<HltbCandidate>,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onSelect: (HltbCandidate) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text("Choose HowLongToBeat match", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = gameName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))

            when {
                loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Looking up candidates…")
                        }
                    }
                }

                failed -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "HowLongToBeat lookup failed. Try again from Change match.",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                candidates.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No candidate matches found.")
                    }
                }

                else -> {
                    Text(
                        text = "Choose the correct entry:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(
                            items = candidates,
                            key = { candidate -> candidate.hltbId },
                        ) { candidate ->
                            HltbCandidateRow(
                                candidate = candidate,
                                enabled = !loading,
                                onClick = { onSelect(candidate) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Name filter over the loaded library. Instant: nothing is re-queried per keystroke. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        placeholder = {
            Text(
                text = "Search games or genres",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = TablerIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = TablerIcons.X,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun GenreFilterButton(
    selectedCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(if (selectedCount == 0) "Genres" else "Genres ($selectedCount)")
    }
}

@Composable
private fun ActiveGenreFilters(
    genres: List<GenreFilterChoice>,
    onRemove: (GenreFilterChoice) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Genre filters",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("Clear") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            genres.forEach { genre ->
                FilterChip(
                    selected = true,
                    onClick = { onRemove(genre) },
                    label = { Text(genre.label) },
                )
            }
        }
    }
}

private fun <T : LibraryRow> List<T>.filterByGenres(selectedGenreIds: Set<String>): List<T> =
    if (selectedGenreIds.isEmpty()) {
        this
    } else {
        filter { game -> game.genres.any { it.id in selectedGenreIds } }
    }

/** Pure presentation filter: selected IDs live in the ViewModel and are intentionally untouched. */
internal fun <T> List<T>.filterByHltbCoverage(
    notCoveredOnly: Boolean,
    statusOf: (T) -> HltbMatchState,
): List<T> = if (notCoveredOnly) {
    filter { statusOf(it) == HltbMatchState.NOT_COVERED }
} else {
    this
}

/**
 * Batch HLTB refresh (with a force-all option) plus the match-review entry point, tucked behind a
 * single icon button next to the search field. These are a one-time-setup action a player taps
 * heavily on first run and rarely afterward, so they no longer earn permanent top-of-screen real
 * estate — the pending [reviewCount] surfaces as a badge instead so it stays noticeable without a
 * standing button.
 */
@Composable
private fun HltbMenuButton(
    refreshing: Boolean,
    reviewCount: Int,
    onRefresh: () -> Unit,
    onForceRefresh: () -> Unit,
    onOpenReview: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                BadgedBox(
                    badge = {
                        if (reviewCount > 0) {
                            Badge { Text(reviewCount.toString()) }
                        }
                    },
                ) {
                    Icon(imageVector = TablerIcons.Clock, contentDescription = "HowLongToBeat options")
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (refreshing) "Refreshing…" else "Refresh HLTB library") },
                enabled = !refreshing,
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
            DropdownMenuItem(
                text = { Text("Force refresh all") },
                enabled = !refreshing,
                onClick = {
                    expanded = false
                    onForceRefresh()
                },
            )
            if (reviewCount > 0) {
                DropdownMenuItem(
                    text = { Text("Review HLTB matches ($reviewCount)") },
                    onClick = {
                        expanded = false
                        onOpenReview()
                    },
                )
            }
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
private fun BatchProgressPanel(
    status: HltbRefreshStatus,
    waitRemainingSeconds: Int?,
    progress: HltbBatchProgress?,
    log: List<HltbLogEntry>,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (progress == null || progress.total <= 0) {
                val waitSuffix = if (status == HltbRefreshStatus.WAITING_FOR_NETWORK) {
                    " ${waitRemainingSeconds ?: HLTB_OFFLINE_WAIT_SECONDS}s"
                } else {
                    ""
                }
                val statusText = when (status) {
                    HltbRefreshStatus.WAITING_FOR_NETWORK ->
                        "Waiting for an internet connection…$waitSuffix"
                    HltbRefreshStatus.RETRYING -> "Retrying HowLongToBeat refresh…"
                    HltbRefreshStatus.IDLE,
                    HltbRefreshStatus.QUEUED,
                    HltbRefreshStatus.RUNNING,
                    -> "Starting HowLongToBeat refresh…"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    StopScanButton(onStop)
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StopScanButton(onStop)
            }
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

/**
 * Stops the sweep where it stands. Not a pause, but it behaves like one: every game already
 * fetched is now inside the freshness window, so tapping "Refresh HLTB library" again resumes from
 * roughly where this left off instead of starting over. ("Force all" deliberately does start over.)
 */
@Composable
private fun StopScanButton(onStop: () -> Unit) {
    TextButton(onClick = onStop) {
        Icon(
            imageVector = TablerIcons.PlayerStop,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("Stop")
    }
}

/** The rolling log distinguishes a failed lookup from a successful no-match. */
private fun outcomeLabel(outcome: HltbRefreshOutcome): String = when (outcome) {
    is HltbRefreshOutcome.Refreshed -> when (outcome.state) {
        HltbMatchState.NOT_COVERED -> "not covered"
        HltbMatchState.RESOLVED -> "matched"
        HltbMatchState.NEEDS_REVIEW -> "needs review"
        HltbMatchState.UNMATCHED -> "no match"
    }
    HltbRefreshOutcome.NoMatch -> "no match"
    is HltbRefreshOutcome.Failed -> "lookup failed (${outcome.failureClass.name.lowercase()})"
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
    direction: LibrarySortDirection,
    onSortChange: (LibrarySortKey) -> Unit,
    onDirectionChange: (LibrarySortDirection) -> Unit,
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
        SortControl(
            sort = sort,
            direction = direction,
            onSortChange = onSortChange,
            onDirectionChange = onDirectionChange,
        )
    }
}

/**
 * Compact menu showing the active key by name — the sort labels name what they order by — plus a
 * separate toggle for which end comes first.
 *
 * The two are distinct controls rather than one menu of eight options: the menu answers "by what",
 * the chevron answers "which end first". Folding the second into the first would make "Name" and
 * "Name (Z→A)" peers in a list where they are not, and would cost two taps to do what one does.
 */
@Composable
private fun SortControl(
    sort: LibrarySortKey,
    direction: LibrarySortDirection,
    onSortChange: (LibrarySortKey) -> Unit,
    onDirectionChange: (LibrarySortDirection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
        // Tapping flips without opening the menu: reversing a list is one gesture, not a
        // trip through a picker.
        IconButton(
            onClick = { onDirectionChange(direction.flipped()) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = when (direction) {
                    LibrarySortDirection.ASCENDING -> TablerIcons.ChevronUp
                    LibrarySortDirection.DESCENDING -> TablerIcons.ChevronDown
                },
                // Names where the list stands *and* what the tap will do — a bare chevron on its
                // own says neither.
                contentDescription = "Sorted ${librarySortDirectionLabel(sort, direction)}; " +
                    "tap for ${librarySortDirectionLabel(sort, direction.flipped())}",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Filter matched nothing. Rendered in-list so the search field above it stays reachable. */
@Composable
private fun NoMatchesRow(
    query: String,
    hasGenreFilter: Boolean,
    hasCoverageFilter: Boolean,
    onClear: () -> Unit,
    onClearGenres: () -> Unit,
    onClearCoverage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text(
            text = when {
                query.isNotBlank() && (hasGenreFilter || hasCoverageFilter) ->
                    "No games match \"$query\" and the active filters"
                query.isNotBlank() -> "No games match \"$query\""
                hasGenreFilter && hasCoverageFilter -> "No games match the active filters"
                hasGenreFilter -> "No games match the selected genres"
                else -> "No games are marked not covered"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row {
            if (query.isNotBlank()) {
                TextButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Clear search")
                }
            }
            if (hasGenreFilter) {
                TextButton(onClick = onClearGenres, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Clear genres")
                }
            }
            if (hasCoverageFilter) {
                TextButton(onClick = onClearCoverage, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Show all coverage")
                }
            }
        }
    }
}

private fun GoalGameUi.toDisplayGame() = LibraryDisplayGame(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = headerUrl,
    heroCapsuleUrl = heroCapsuleUrl,
    playtimeForever = playtimeForever,
    completionistMinutes = completionistMinutes,
    hltbStatus = hltbStatus,
    fetchOp = fetchOp,
    achievementUnlocked = achievementUnlocked,
    achievementTotal = achievementTotal,
    xpContributed = xpContributed,
    isCurrentlyPlaying = isCurrentlyPlaying,
    isFamilyShared = isFamilyShared,
    recencyState = recencyState,
)

private fun BacklogGameUi.toDisplayGame() = LibraryDisplayGame(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = headerUrl,
    heroCapsuleUrl = heroCapsuleUrl,
    playtimeForever = playtimeForever,
    completionistMinutes = completionistMinutes,
    hltbStatus = hltbStatus,
    fetchOp = fetchOp,
    achievementUnlocked = achievementUnlocked,
    achievementTotal = achievementTotal,
    xpContributed = xpContributed,
    isCurrentlyPlaying = isCurrentlyPlaying,
    isFamilyShared = isFamilyShared,
    recencyState = recencyState,
)

/** Emit one lazy item per row in list mode, or one lazy item per grid row in grid modes. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.libraryGameItems(
    games: List<LibraryDisplayGame>,
    density: GameListDensity,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onClick: (LibraryDisplayGame) -> Unit,
    onLongClick: (LibraryDisplayGame) -> Unit,
    onManageGoal: (LibraryDisplayGame) -> Unit,
) {
    if (!density.isGrid) {
        games.forEach { game ->
            item(key = "library-game-${game.appId}") {
                LibraryGameRow(
                    game = game,
                    density = density,
                    selected = game.appId in selectedIds,
                    selectionMode = selectionMode,
                    onClick = { onClick(game) },
                    onLongClick = { onLongClick(game) },
                    onManageGoal = { onManageGoal(game) },
                )
            }
        }
        return
    }

    games.chunked(density.columns).forEachIndexed { rowIndex, row ->
        item(key = "library-grid-row-$rowIndex-${row.firstOrNull()?.appId ?: 0}") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { game ->
                    LibraryGameCell(
                        game = game,
                        density = density,
                        selected = game.appId in selectedIds,
                        selectionMode = selectionMode,
                        onClick = { onClick(game) },
                        onLongClick = { onLongClick(game) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(density.columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The single full-detail renderer used by both Library sections. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGameRow(
    game: LibraryDisplayGame,
    density: GameListDensity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onManageGoal: () -> Unit,
) {
    GameCard(
        headerUrl = game.headerUrl,
        fallbackUrls = SteamIconMapper.listBackgroundFallbackUrls(game.appId),
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        GameIconWithHltbBadge(
            iconUrl = game.iconUrl,
            status = game.hltbStatus,
            op = game.fetchOp,
            isCurrentlyPlaying = game.isCurrentlyPlaying,
            // The HLTB match badge is part of the completion picture rather than a score badge,
            // so it rides the same rung as the progress bar below the name.
            showHltbStatus = density.showsCompletionProgress,
            recencyState = game.recencyState,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = game.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (game.isCurrentlyPlaying) {
                    MaterialTheme.colorScheme.playingIndicator
                } else {
                    Color.Unspecified
                },
            )
            if (game.isFamilyShared) FamilySharedLabel()
            if (density.showsPlaytime) {
                PlaytimeLabel(game.playtimeForever, observed = game.isFamilyShared)
            }
            if (density.showsCompletionProgress) {
                CompletionProgress(
                    playtimeMinutes = game.playtimeForever,
                    completionistMinutes = game.completionistMinutes,
                )
            }
            if (density.showsAchievementCount || density.showsXpContribution) {
                GameBadges(
                    unlocked = game.achievementUnlocked,
                    total = game.achievementTotal,
                    xpContributed = game.xpContributed,
                    showAchievementCount = density.showsAchievementCount,
                    showXpContribution = density.showsXpContribution,
                )
            }
        }
        RowTrailing(
            selected = selected,
            selectionMode = selectionMode,
            onManageGoal = onManageGoal,
        )
    }
}

/**
 * Grid cell renderer. The two grid densities share a deliberate tile shell and portrait Steam hero
 * capsule stage while changing only the amount of information in the body. Grid cards intentionally
 * have no trailing action control, keeping their visual hierarchy focused on the game itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGameCell(
    game: LibraryDisplayGame,
    density: GameListDensity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = density == GameListDensity.COMPACT_GRID
    val tileShape = RoundedCornerShape(18.dp)
    val heroShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        game.isCurrentlyPlaying -> MaterialTheme.colorScheme.playingIndicator
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    }
    Card(
        modifier = modifier
            .padding(vertical = 4.dp)
            // GRID is slightly taller than it was: its body gained the achievement-count line, and
            // the hero capsule holds the remaining weight — so the tile grows rather than the
            // artwork shrinking or the name truncating.
            .aspectRatio(if (compact) 0.62f else 0.56f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = tileShape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 1.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(heroShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                GameHeroCapsule(
                    heroCapsuleUrl = game.heroCapsuleUrl,
                    fallbackUrls = SteamIconMapper.gridArtworkFallbackUrls(game.appId),
                    modifier = Modifier.matchParentSize(),
                    shape = heroShape,
                )

                if (selectionMode) {
                    TileSelectionIndicator(
                        selected = selected,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
                RecencyBadge(
                    state = game.recencyState,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 9.dp),
                horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Text(
                    text = game.name,
                    style = if (compact) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (game.isCurrentlyPlaying) {
                        MaterialTheme.colorScheme.playingIndicator
                    } else {
                        Color.Unspecified
                    },
                    textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (game.isFamilyShared) {
                    FamilySharedLabel(modifier = Modifier.padding(top = 4.dp))
                }
                if (density.showsPlaytime) {
                    PlaytimeLabel(
                        minutes = game.playtimeForever,
                        observed = game.isFamilyShared,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                if (density.showsCompletionProgress) {
                    CompletionProgress(
                        playtimeMinutes = game.playtimeForever,
                        completionistMinutes = game.completionistMinutes,
                    )
                }
                // The same label the list row carries, including its gold "100% Completed" pill —
                // a completionist's scan target is the one badge worth the grid cell's last line.
                if (density.showsAchievementCount) {
                    AchievementCountLabel(
                        unlocked = game.achievementUnlocked,
                        total = game.achievementTotal,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TileSelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val fill = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    }
    Box(
        modifier = modifier
            .padding(8.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) TablerIcons.Check else TablerIcons.Checkbox,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A play icon plus the raw duration — "played" is implied by the row it sits in. */
@Composable
private fun PlaytimeLabel(
    minutes: Int,
    observed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Steam reports no lifetime playtime for a family-shared game, so what is shown for one is what
    // the app observed. The word travels with the number rather than living in a legend elsewhere:
    // a total presented as complete when it structurally cannot be is the one thing this must not do.
    val suffix = if (observed) " observed" else ""

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TablerIcons.PlayerPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = UiFormat.minutes(minutes) + suffix,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = if (observed) {
                    "${UiFormat.minutes(minutes)} observed by Backlogium"
                } else {
                    "${UiFormat.minutes(minutes)} played"
                }
            },
        )
    }
}

/**
 * The row's source marking. Text, not a colour or a bare dot: the Library must make the
 * distinction perceptible without depending on colour alone, and a shared game is a normal game
 * in every other respect — so this stays a quiet label rather than a competing badge.
 */
@Composable
private fun FamilySharedLabel(modifier: Modifier = Modifier) {
    Text(
        text = "Family Sharing",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics {
            contentDescription = "Played through Family Sharing"
        },
    )
}

/**
 * The shared row shell. Material 3's `Card(onClick = …)` has no long-press, so the card is
 * non-clickable and carries [combinedClickable] instead — long-press enters selection mode while
 * tap keeps its existing meaning.
 *
 * A completed game is marked by its gold trophy "100%" pill only. The row used to also take a
 * gold outline, which read as loud rather than celebratory once several completed games sat next
 * to each other in the list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(
    headerUrl: String,
    fallbackUrls: List<String> = emptyList(),
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = selectionBorder(selected),
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GameHeaderBackdrop(
                headerUrl = headerUrl,
                fallbackUrls = fallbackUrls,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

/**
 * The game's store header art, anchored to the right edge of the card and dissolving to nothing
 * before it reaches the text on the left.
 *
 * The fade is a real alpha mask (`DstIn` against a horizontal gradient) drawn inside an offscreen
 * layer, not a colored scrim over the top — a scrim would have to match the card's fill, and would
 * break the moment a row is selected and its container turns `secondaryContainer`.
 *
 * Games with no header on the CDN simply render nothing; the row is designed to look right without
 * it, so no placeholder is drawn.
 */
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
 *
 * Past the completion length the bar **rescales** rather than sitting pinned at 100%, which said
 * nothing about how far past you were. The whole bar becomes your playtime: the HowLongToBeat
 * length keeps the accent gold, and the excess beyond it fills the rest in a darker, redder shade
 * of that same gold. So the bar stays full — no empty track — and the gold segment shrinking is
 * exactly the "how far past am I" signal.
 */
@Composable
private fun CompletionProgress(playtimeMinutes: Int, completionistMinutes: Int?) {
    val completionist = completionistMinutes ?: return
    val overrun = playtimeMinutes > completionist && completionist > 0
    val fraction = if (overrun) {
        // Bar spans the playtime; the gold portion is the completion length's share of it.
        completionist.toFloat() / playtimeMinutes.toFloat()
    } else {
        Gamification.goalProgress(playtimeMinutes, completionist).fraction.toFloat()
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
        // Under the length, the track is "still to play" and keeps its default treatment. Past it,
        // the track *is* the excess, so it takes the overrun color instead.
        trackColor = if (overrun) {
            MaterialTheme.colorScheme.overrunExcess
        } else {
            ProgressIndicatorDefaults.linearTrackColor
        },
    )
    Spacer(Modifier.height(2.dp))
    val percent = (playtimeMinutes.toLong() * 100 / completionist).toInt()
    val fullDescription = if (overrun) {
        "${UiFormat.minutes(completionist)} to 100% · played $percent%"
    } else {
        "${UiFormat.minutes(playtimeMinutes)} / ${UiFormat.minutes(completionist)} to 100%"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = fullDescription },
    ) {
        if (overrun) {
            Icon(
                imageVector = TablerIcons.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.overrunExcess,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact, live HLTB state for a game: in-flight, failed, or the persisted match status. */
@Composable
private fun HltbStatusLabel(
    status: HltbMatchState,
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

        status == HltbMatchState.NOT_COVERED -> Text(
            text = "Not covered by completion-times dataset",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        else -> error("Unreachable HLTB state")
    }
}

/**
 * A clock glyph stands in for a stored HowLongToBeat outcome; not-covered uses an alert-circle
 * glyph as a non-colour-only distinction. Row real estate is scarce, so the full sentence from
 * [HltbStatusLabel] also lives in the icon's content description and focus-management dialog.
 */
@Composable
private fun HltbIndicator(
    status: HltbMatchState,
    op: HltbFetchOp?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    if (op == HltbFetchOp.IN_PROGRESS) {
        CircularProgressIndicator(
            modifier = modifier
                .size(size)
                .semantics { contentDescription = "Looking up HowLongToBeat…" },
            strokeWidth = 1.5.dp,
        )
        return
    }
    val greyedOut = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val (icon, tint, description) = when {
        op == HltbFetchOp.FAILED ->
            Triple(
                TablerIcons.Clock,
                MaterialTheme.colorScheme.error,
                "HowLongToBeat lookup failed",
            )
        status == HltbMatchState.NOT_COVERED ->
            Triple(
                TablerIcons.AlertCircle,
                greyedOut,
                "Not covered by completion-times dataset",
            )
        status == HltbMatchState.RESOLVED ->
            Triple(TablerIcons.Clock, MaterialTheme.colorScheme.primary, "HowLongToBeat matched")
        status == HltbMatchState.NEEDS_REVIEW ->
            Triple(
                TablerIcons.Clock,
                MaterialTheme.colorScheme.tertiary,
                "Needs HowLongToBeat match review",
            )
        status == HltbMatchState.UNMATCHED ->
            Triple(TablerIcons.Clock, greyedOut, "No HowLongToBeat match")
        else -> error("Unreachable HLTB state")
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * The row's leading game icon with a small HLTB status badge pinned to its corner — a persistent
 * per-game marker that doesn't compete with the title or badge line for width, since the old
 * inline text label squeezed the "100% COMPLETED" pill down to a truncated "100% C".
 *
 * A "currently playing" dot pins to the opposite (top-end) corner when Steam's live presence
 * reports this exact game as running — the bottom-end corner is already the HLTB badge's spot.
 */
@Composable
private fun GameIconWithHltbBadge(
    iconUrl: String,
    status: HltbMatchState,
    op: HltbFetchOp?,
    isCurrentlyPlaying: Boolean,
    iconSize: Dp = 40.dp,
    showHltbStatus: Boolean = true,
    recencyState: GameRecencyState? = null,
) {
    Box {
        GameIcon(iconUrl, iconSize = iconSize)
        RecencyBadge(
            state = recencyState,
            size = 16.dp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (showHltbStatus) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                HltbIndicator(status = status, op = op, size = 10.dp)
            }
        }
        if (isCurrentlyPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.playingIndicator)
                        .semantics { contentDescription = "Currently playing" },
                )
            }
        }
    }
}

/** True once a game's achievement counts show every known achievement unlocked. */
private fun isGameCompleted(unlocked: Int?, total: Int?): Boolean =
    total != null && total > 0 && unlocked == total

/** The only row outline left: the selection one. */
@Composable
private fun selectionBorder(selected: Boolean): BorderStroke? =
    if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null

/**
 * The row's badge line: achievement counts and contributed XP on **one** line, always.
 *
 * Both halves are pinned to a single line and the achievement side yields first (it ellipsizes,
 * the XP figure does not), because the two together are wide enough to wrap a narrow row into
 * three lines — which is what pushed the card taller than its own icon.
 *
 * The XP badge is deliberately the quietest thing here — plain muted text, no pill or icon — since
 * every row can now also carry a progress bar.
 */
@Composable
private fun GameBadges(
    unlocked: Int?,
    total: Int?,
    xpContributed: Int,
    showAchievementCount: Boolean = true,
    showXpContribution: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // fill = false: the achievement badge takes only what it needs, so a game with no
        // achievement data leaves the XP figure at the left rather than pushed to the far edge.
        if (showAchievementCount) {
            AchievementCountLabel(
                unlocked = unlocked,
                total = total,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (showXpContribution) {
            if (showAchievementCount && unlocked != null && total != null) {
                Spacer(Modifier.width(8.dp))
            }
            XpContributionLabel(xpContributed)
        }
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
 *
 * Shown as a bolt icon plus the bare number to keep the badge line to one row; the full
 * "N XP contributed" wording lives in the accessibility label, where length costs nothing.
 */
@Composable
private fun XpContributionLabel(xpContributed: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$xpContributed XP contributed"
        },
    ) {
        Icon(
            imageVector = TablerIcons.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = "$xpContributed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Compact "unlocked / total" achievement badge; shown only once achievement data exists. Once
 * every achievement is unlocked, this becomes a striking gold "100% Completed" pill instead of
 * the plain count, so a fully-completed game is unmistakable at a glance in the list.
 */
@Composable
private fun AchievementCountLabel(unlocked: Int?, total: Int?, modifier: Modifier = Modifier) {
    if (unlocked == null || total == null) return
    if (isGameCompleted(unlocked, total)) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics(mergeDescendants = true) { contentDescription = "100% completed" },
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
                text = "100%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                softWrap = false,
            )
        }
        return
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$unlocked of $total achievements unlocked"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TablerIcons.Trophy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$unlocked/$total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Confirm adding a game to Focus or removing it (no typed target — completion lengths come
 * from HowLongToBeat), and surface/refresh this game's HLTB state: the current match status,
 * plus a "Refresh HowLongToBeat" action that forces a fresh single-game lookup.
 */
@Composable
private fun GoalDialog(
    target: GoalDialogTarget,
    hltbStatus: HltbMatchState,
    fetchOp: HltbFetchOp?,
    onDismiss: () -> Unit,
    onTag: () -> Unit,
    onUntag: () -> Unit,
    onRefresh: () -> Unit,
    onChooseMatch: () -> Unit,
    onChangeMatch: () -> Unit,
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
                when (hltbStatus) {
                    HltbMatchState.NEEDS_REVIEW -> TextButton(
                        onClick = onChooseMatch,
                        enabled = fetchOp != HltbFetchOp.IN_PROGRESS,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("Choose match")
                    }

                    HltbMatchState.RESOLVED -> TextButton(
                        onClick = onChangeMatch,
                        enabled = fetchOp != HltbFetchOp.IN_PROGRESS,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("Change match")
                    }

                    else -> Unit
                }
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

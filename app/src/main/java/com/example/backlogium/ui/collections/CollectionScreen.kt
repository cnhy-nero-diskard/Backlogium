package com.example.backlogium.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionBanner
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionPacingState
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.label
import com.example.backlogium.ui.components.GameHeaderBackdrop
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.theme.collectionAccentColor
import com.example.backlogium.ui.theme.deadlineWarning
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextDecoration

/**
 * Collection destination: existing collections open on a read-only overview, while creation opens
 * the management form. Customization for an existing collection is intentionally behind the
 * secondary actions menu so its games and metrics are the primary surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onDone: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showEditor by rememberSaveable { mutableStateOf(viewModel.collectionId == 0L) }
    var showActions by remember { mutableStateOf(false) }
    val showingOverview = !state.isNew && !showEditor

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (!state.isNew && showEditor) {
                        showEditor = false
                    } else {
                        onDone()
                    }
                },
            ) {
                Icon(imageVector = TablerIcons.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = when {
                    state.isNew -> "New collection"
                    showingOverview -> state.name.ifBlank { "Collection" }
                    else -> "Edit collection"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (!state.isNew) {
                Box {
                    IconButton(onClick = { showActions = true }) {
                        Icon(
                            imageVector = if (showingOverview) {
                                TablerIcons.DotsVertical
                            } else {
                                TablerIcons.Settings
                            },
                            contentDescription = "Collection actions",
                        )
                    }
                    DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                    ) {
                        if (showingOverview) {
                            DropdownMenuItem(
                                text = { Text("Customize collection") },
                                onClick = {
                                    showActions = false
                                    showEditor = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete collection") },
                            onClick = {
                                showActions = false
                                viewModel.delete()
                            },
                        )
                    }
                }
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            if (showingOverview) {
                CollectionOverview(
                    state = state,
                    onCustomize = { showEditor = true },
                    onDeadlineChanged = viewModel::changeDeadline,
                )
            } else {
                CollectionForm(state = state, viewModel = viewModel)
            }
        }
    }
}

/** Read-only collection surface: members and their useful local metrics come before editing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionOverview(
    state: CollectionUiState,
    onCustomize: () -> Unit,
    onDeadlineChanged: (LocalDate) -> Unit,
) {
    val accentColor = state.accent?.let {
        MaterialTheme.colorScheme.collectionAccentColor(it)
    } ?: MaterialTheme.colorScheme.primary
    val summarySurface = accentColor.copy(alpha = 0.12f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainer)
    val trophyProgress = trophyProgress(state.members)
    val banner = state.banner ?: return
    var showDeadlinePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = modeLabel(state.mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor,
                )
                if (state.mode == CollectionMode.DEADLINE_GOAL) {
                    Text(
                        text = state.targetDate?.format(dateFormatter)
                            ?.let { "Target date: $it" }
                            ?: "No deadline set",
                        style = MaterialTheme.typography.bodySmall,
                        color = deadlineUrgencyColor(banner.daysRemaining),
                    )
                }
            }
        }

        if (state.mode == CollectionMode.DEADLINE_GOAL) {
            item {
                PacingDeadlinePlanCard(
                    banner = banner,
                    onChangeDeadline = { showDeadlinePicker = true },
                )
            }
        } else if (collectionModePacingSectionVisible(state.mode)) {
            item {
                ModePacingCard(banner = banner)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = summarySurface),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        CollectionMetric(
                            label = "Games",
                            value = state.members.size.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        CollectionMetric(
                            label = "Played",
                            value = UiFormat.minutes(state.members.sumOf { it.playtimeMinutes }),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        CollectionMetric(
                            label = "Sessions",
                            value = state.members.sumOf { it.sessionCount }.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        CollectionMetric(
                            label = "Trophies",
                            value = trophyProgress?.let { (unlocked, total) -> "$unlocked/$total" }
                                ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Games in this collection",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.members.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.members.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("No games yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Add games and tune this collection from Customize.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = onCustomize) {
                            Icon(
                                imageVector = TablerIcons.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Customize collection")
                        }
                    }
                }
            }
        } else {
            items(
                items = state.members,
                key = { it.appId },
            ) { member ->
                CollectionGameCard(member = member, accentColor = accentColor)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showDeadlinePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = (banner.estimatedFitDate ?: state.targetDate)
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDeadlinePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeadlinePicker = false
                        datePickerState.selectedDateMillis?.let { millis ->
                            // The overview is read-only except for this explicit deadline action.
                            onDeadlineChanged(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDeadlinePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** Deadline pacing detail driven entirely by the domain's confidence and action eligibility. */
@Composable
private fun PacingDeadlinePlanCard(
    banner: CollectionBanner,
    onChangeDeadline: () -> Unit,
) {
    val deadlineColor = deadlineUrgencyColor(banner.daysRemaining)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = TablerIcons.Clock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = deadlineColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Deadline",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                collectionPacingStateLabel(banner.pacingState)?.let { stateLabel ->
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (banner.pacingState == CollectionPacingState.AT_RISK) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            DeadlinePlanSignal(
                icon = TablerIcons.Clock,
                contentDescription = "Deadline status",
                text = deadlineCountdown(banner.daysRemaining),
                color = deadlineColor,
                emphasized = true,
            )
            DeadlinePlanSignal(
                icon = TablerIcons.DeviceGamepad,
                contentDescription = "Remaining work",
                text = banner.remainingMinutes?.let { remaining ->
                    "${UiFormat.minutes(remaining)} left" +
                        if (banner.unknownDurationCount > 0) " · +${banner.unknownDurationCount} unknown" else ""
                } ?: "No ${banner.timeBasis.label()} estimate",
            )
            banner.recentTrackedPaceMinutes?.let { pace ->
                DeadlinePlanSignal(
                    icon = TablerIcons.PlayerPlay,
                    contentDescription = "Recent tracked pace",
                    text = "~${UiFormat.minutes(pace.toInt())} / active day",
                )
            }
            if (banner.pacingState == CollectionPacingState.AT_RISK) {
                banner.requiredMinutesPerActiveDay?.let { required ->
                    DeadlinePlanSignal(
                        icon = TablerIcons.PlayerPlay,
                        contentDescription = "Required pace",
                        text = "Need ~${UiFormat.minutes(required.toInt())} / active day",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                banner.capacityMarginMinutes?.takeIf { it < 0.0 }?.let { margin ->
                    DeadlinePlanSignal(
                        icon = TablerIcons.Clock,
                        contentDescription = "Capacity shortfall",
                        text = "${UiFormat.minutes(abs(margin.toInt()))} short",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                banner.projectedCapacityMinutes?.let { capacity ->
                    DeadlinePlanSignal(
                        icon = TablerIcons.PlayerPlay,
                        contentDescription = "Projected capacity",
                        text = "~${UiFormat.minutes(capacity.toInt())} capacity",
                    )
                }
            }
            when (banner.pacingState) {
                com.example.backlogium.domain.CollectionPacingState.AT_RISK -> banner.estimatedFitDate?.let { date ->
                    Text(
                        text = "Suggested: ${date.format(dateFormatter)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.example.backlogium.domain.CollectionPacingState.LEARNING -> {
                    val activeDaysNeeded = learningDeadlineActiveDaysNeeded(
                        remainingMinutes = banner.remainingMinutes,
                        recentTrackedPaceMinutes = banner.recentTrackedPaceMinutes,
                    )
                    Text(
                        text = activeDaysNeeded?.let { activeDays ->
                            "Provisional: ~$activeDays active play " +
                                "${if (activeDays == 1) "day" else "days"}."
                        } ?: "Track more completed play days for a pace estimate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                com.example.backlogium.domain.CollectionPacingState.INCOMPLETE_DATA -> Text(
                    text = "${banner.unknownDurationCount} estimate${if (banner.unknownDurationCount == 1) "" else "s"} missing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
            if (collectionDeadlineActionVisible(banner)) {
                OutlinedButton(onClick = onChangeDeadline) { Text("Change deadline") }
            }
        }
    }
}

@Composable
private fun DeadlinePlanSignal(
    icon: ImageVector,
    contentDescription: String,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    emphasized: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Text(
            text = text,
            style = if (emphasized) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun deadlineUrgencyColor(daysRemaining: Long?): Color = when (deadlineUrgency(daysRemaining)) {
    DeadlineUrgency.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
    DeadlineUrgency.SOON -> MaterialTheme.colorScheme.deadlineWarning
    DeadlineUrgency.DUE_OR_PAST -> MaterialTheme.colorScheme.error
}

private fun deadlineCountdown(daysRemaining: Long?): String = when {
    daysRemaining == null -> "No deadline"
    daysRemaining < 0 -> "${abs(daysRemaining)}d overdue"
    daysRemaining == 0L -> "Due today"
    else -> "${daysRemaining}d left"
}

@Composable
private fun ModePacingCard(banner: CollectionBanner) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Personal Pace", style = MaterialTheme.typography.titleSmall)
            banner.recentTrackedPaceMinutes?.let { pace ->
                Text(
                    text = "Recent tracked pace: about ${UiFormat.minutes(pace.toInt())} per active day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            collectionPacingStateLabel(banner.pacingState)?.let { stateLabel ->
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (banner.pacingState == CollectionPacingState.AT_RISK) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            when (banner.pacingState) {
                com.example.backlogium.domain.CollectionPacingState.ON_TRACK -> {
                    if (banner.mode == CollectionMode.COMPLETION_GOAL) {
                        Text(
                            text = banner.completionHorizonDate?.let {
                                "Approximate completion: ${it.format(dateFormatter)}."
                            } ?: "All known completion work is done.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        banner.nextGameHorizonDate?.let { date ->
                            Text(
                                text = "Next game: approximately by ${date.format(dateFormatter)}.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        banner.queueHorizonDate?.let { date ->
                            Text(
                                text = "Whole queue: approximately by ${date.format(dateFormatter)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                com.example.backlogium.domain.CollectionPacingState.LEARNING -> Text(
                    text = "Backlogium is learning from tracked activity; horizon is not definitive yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                com.example.backlogium.domain.CollectionPacingState.INCOMPLETE_DATA -> {
                    Text(
                        text = "${banner.unknownDurationCount} Completionist estimate${if (banner.unknownDurationCount == 1) "" else "s"} missing; horizon is incomplete.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (banner.mode == CollectionMode.ORDERED_QUEUE) {
                        banner.nextGameHorizonDate?.let { date ->
                            Text(
                                text = "Next game: approximately by ${date.format(dateFormatter)}.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                com.example.backlogium.domain.CollectionPacingState.AT_RISK -> Text(
                    text = "The current plan needs more tracked capacity than expected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun CollectionMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CollectionGameCard(
    member: CollectionMemberUi,
    accentColor: Color,
) {
    val cardSurface = accentColor.copy(alpha = 0.08f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainer)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
        colors = CardDefaults.cardColors(containerColor = cardSurface),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GameHeaderBackdrop(
                headerUrl = member.headerUrl,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .heightIn(min = 88.dp)
                    .background(accentColor),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                member.iconUrl?.let { iconUrl ->
                    GameIcon(iconUrl = iconUrl, iconSize = 56.dp)
                    Spacer(Modifier.width(14.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        text = "${UiFormat.minutes(member.playtimeMinutes)} played · " +
                            sessionLabel(member.sessionCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    member.achievementsUnlocked?.let { unlocked ->
                        member.achievementsTotal?.let { total ->
                            Text(
                                text = "$unlocked/$total trophies",
                                style = MaterialTheme.typography.bodySmall,
                                color = accentColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

private fun trophyProgress(members: List<CollectionMemberUi>): Pair<Int, Int>? {
    val withData = members.filter {
        it.achievementsUnlocked != null && it.achievementsTotal != null
    }
    if (withData.isEmpty()) return null
    return withData.sumOf { it.achievementsUnlocked!! } to
        withData.sumOf { it.achievementsTotal!! }
}

private fun sessionLabel(count: Int): String = when (count) {
    1 -> "1 session"
    else -> "$count sessions"
}

/** The management form: name, mode, sort, deadline, members, add-games, save/delete. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CollectionForm(
    state: CollectionUiState,
    viewModel: CollectionViewModel,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGenreIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showGenreSheet by rememberSaveable { mutableStateOf(false) }

    val memberIds = remember(state.members) { state.members.mapTo(mutableSetOf()) { it.appId } }
    val selectedGenreSet = selectedGenreIds.toSet()
    val genreCatalog = remember(state.libraryGames) { genreFilterCatalog(state.libraryGames) }
    val filteredAddables = remember(state.libraryGames, memberIds, query, selectedGenreIds) {
        filterAddableGames(state.libraryGames, memberIds, query, selectedGenreSet)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Clear the backlog") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving,
                )
            }

            item {
                SectionLabel("Mode")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CollectionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            label = { Text(modeLabel(mode)) },
                            enabled = !state.saving,
                        )
                    }
                }
            }

            val sortOptions = sortOptions(state.mode)
            if (sortOptions.isNotEmpty()) {
                item {
                    SectionLabel("Order")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sortOptions.forEach { sort ->
                            FilterChip(
                                selected = state.sort == sort,
                                onClick = { viewModel.setSort(sort) },
                                label = { Text(sortLabel(sort)) },
                                enabled = !state.saving,
                            )
                        }
                    }
                }
            }

            if (state.mode == CollectionMode.DEADLINE_GOAL) {
                item {
                    SectionLabel("Target date")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = state.targetDate?.format(dateFormatter) ?: "No deadline set",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            TextButton(onClick = { showDatePicker = true }) { Text("Pick") }
                            if (state.targetDate != null) {
                                TextButton(onClick = { viewModel.setTargetDate(null) }) { Text("Clear") }
                            }
                        }
                    }
                }

                item {
                    SectionLabel("Time estimate basis")
                    Text(
                        text = "Choose which HowLongToBeat length Backlogium uses to check the deadline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CollectionTimeBasis.entries.forEach { basis ->
                            FilterChip(
                                selected = state.timeBasis == basis,
                                onClick = { viewModel.setTimeBasis(basis) },
                                label = { Text(basis.label()) },
                                enabled = !state.saving,
                            )
                        }
                    }
                }
            }

            item {
                SectionLabel("Accent")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccentChip(
                        label = "Default",
                        selected = state.accent == null,
                        onClick = { viewModel.setAccent(null) },
                    )
                    CollectionAccent.entries.forEach { accent ->
                        AccentChip(
                            label = accentLabel(accent),
                            selected = state.accent == accent,
                            onClick = { viewModel.setAccent(accent) },
                            accentColor = MaterialTheme.colorScheme.collectionAccentColor(accent),
                        )
                    }
                }
            }

            item { SectionLabel("Games") }
            if (state.members.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("No games yet", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Add games below to build the collection's banner.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            itemsIndexed(
                items = state.members,
                key = { _, member -> member.appId },
            ) { index, member ->
                MemberRow(
                    member = member,
                    index = index,
                    count = state.members.size,
                    reorderable = state.mode == CollectionMode.ORDERED_QUEUE,
                    showDoneToggle = state.mode == CollectionMode.ORDERED_QUEUE,
                    onRemove = { viewModel.removeGame(member.appId) },
                    onMoveUp = { viewModel.moveMember(index, index - 1) },
                    onMoveDown = { viewModel.moveMember(index, index + 1) },
                    onToggleDone = { viewModel.toggleMemberDone(member.appId) },
                )
            }

            item {
                SectionLabel("Add games")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search library") },
                    leadingIcon = {
                        Icon(
                            imageVector = TablerIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showGenreSheet = true },
                        enabled = !state.saving && genreCatalog.isNotEmpty(),
                    ) {
                        Text(if (selectedGenreIds.isEmpty()) "Genres" else "Genres (${selectedGenreIds.size})")
                    }
                    if (selectedGenreIds.isNotEmpty()) {
                        TextButton(onClick = { selectedGenreIds = emptyList() }, enabled = !state.saving) {
                            Text("Clear genres")
                        }
                    }
                }
                if (selectedGenreIds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        genreCatalog.filter { it.id in selectedGenreSet }.forEach { genre ->
                            FilterChip(
                                selected = true,
                                onClick = { selectedGenreIds = selectedGenreIds - genre.id },
                                label = { Text(genre.label) },
                                enabled = !state.saving,
                            )
                        }
                    }
                }
            }

            if (filteredAddables.isEmpty() && state.addableGames.isNotEmpty()) {
                item {
                    Text(
                        text = "No games match your search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(
                items = filteredAddables,
                key = { it.appId },
            ) { game ->
                AddGameRow(
                    game = game,
                    onAdd = { viewModel.addGame(game.appId) },
                    enabled = !state.saving,
                )
            }

            if (state.addableGames.isEmpty()) {
                item {
                    Text(
                        text = "Every library game is already in this collection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Clearance for the floating save button so the last rows stay reachable.
            item { Spacer(Modifier.height(88.dp)) }
        }

        FloatingActionButton(
            onClick = viewModel::save,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = if (state.name.isBlank()) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = if (state.name.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
        ) {
            Icon(imageVector = TablerIcons.Check, contentDescription = "Save collection")
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.targetDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        datePickerState.selectedDateMillis?.let { millis ->
                            viewModel.setTargetDate(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
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


/** Section heading inside the form column. */
@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun AccentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accentColor: Color? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                accentColor?.let { color ->
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(12.dp)
                            .background(color, CircleShape),
                    )
                }
                Text(label)
            }
        },
    )
}

private fun accentLabel(accent: CollectionAccent): String = when (accent) {
    CollectionAccent.STEEL_BLUE -> "Steel blue"
    CollectionAccent.VIOLET -> "Violet"
    CollectionAccent.SAGE -> "Sage"
    CollectionAccent.SLATE -> "Slate"
    CollectionAccent.TEAL -> "Teal"
    CollectionAccent.ROSE -> "Rose"
    CollectionAccent.CORAL -> "Coral"
}

/** One member row: game icon + name, optional done toggle (queue), move up/down, and remove. */
@Composable
private fun MemberRow(
    member: CollectionMemberUi,
    index: Int,
    count: Int,
    reorderable: Boolean,
    showDoneToggle: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleDone: () -> Unit,
) {
    val cardColor = if (member.done) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (member.done) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GameHeaderBackdrop(
                headerUrl = member.headerUrl,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (member.iconUrl != null) {
                GameIcon(iconUrl = member.iconUrl)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    color = textColor,
                    textDecoration = if (member.done) TextDecoration.LineThrough else null,
                )
                if (reorderable) {
                    Text(
                        text = "#${index + 1} in queue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showDoneToggle) {
                IconButton(onClick = onToggleDone) {
                    Icon(
                        imageVector = TablerIcons.CircleCheck,
                        contentDescription = if (member.done) "Mark not done" else "Mark done",
                        tint = if (member.done) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (reorderable) {
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(
                        imageVector = TablerIcons.ChevronUp,
                        contentDescription = "Move up",
                    )
                }
                IconButton(onClick = onMoveDown, enabled = index < count - 1) {
                    Icon(
                        imageVector = TablerIcons.ChevronDown,
                        contentDescription = "Move down",
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = TablerIcons.X,
                    contentDescription = "Remove ${member.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
        }
    }
}

/** One addable library game: tap to add it to the collection. */
@Composable
private fun AddGameRow(
    game: com.example.backlogium.data.repo.LibraryGame,
    onAdd: () -> Unit,
    enabled: Boolean,
) {
    Card(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GameHeaderBackdrop(
                headerUrl = game.headerUrl,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            GameIcon(iconUrl = game.iconUrl)
            Spacer(Modifier.width(12.dp))
            Text(
                text = game.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = TablerIcons.Plus,
                contentDescription = "Add ${game.name}",
                tint = MaterialTheme.colorScheme.primary,
            )
            }
        }
    }
}

/** The sort selections offered per mode: name plus the mode's own metric (manual is queue-only). */
private fun sortOptions(mode: CollectionMode): List<CollectionSort> = when (mode) {
    CollectionMode.BASIC -> listOf(CollectionSort.NAME)
    CollectionMode.COMPLETION_GOAL -> listOf(CollectionSort.NAME, CollectionSort.COMPLETION_FRACTION)
    CollectionMode.DEADLINE_GOAL -> listOf(CollectionSort.NAME, CollectionSort.DAYS_REMAINING)
    CollectionMode.ORDERED_QUEUE -> emptyList()
}

/** User-facing mode names — the code names stay distinct (label/identifier trade-off). */
private fun modeLabel(mode: CollectionMode): String = when (mode) {
    CollectionMode.BASIC -> "Basic list"
    CollectionMode.COMPLETION_GOAL -> "Completion goal"
    CollectionMode.DEADLINE_GOAL -> "Deadline goal"
    CollectionMode.ORDERED_QUEUE -> "Ordered queue"
}

private fun sortLabel(sort: CollectionSort): String = when (sort) {
    CollectionSort.NAME -> "Name"
    CollectionSort.COMPLETION_FRACTION -> "Progress"
    CollectionSort.DAYS_REMAINING -> "Deadline"
    CollectionSort.MANUAL_SEQUENCE -> "Manual"
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

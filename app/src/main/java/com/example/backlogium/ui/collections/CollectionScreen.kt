package com.example.backlogium.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.theme.collectionAccentColor
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.Plus
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Search
import compose.icons.tablericons.Trash
import compose.icons.tablericons.Trophy
import compose.icons.tablericons.X
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration

/**
 * Collection management screen (tasks 4.2–4.6): create/edit a collection, choose its mode and
 * sort, set a deadline (deadline mode only), add/remove games, reorder ordered-queue members,
 * and delete. Renders purely from locally stored state — offline-first, no network. Reached as
 * a pushed sub-destination from Home; [onDone] pops back after save, delete, or back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onDone: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            IconButton(onClick = onDone) {
                Icon(imageVector = TablerIcons.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (state.isNew) "New collection" else "Edit collection",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (!state.isNew) {
                IconButton(onClick = viewModel::delete) {
                    Icon(
                        imageVector = TablerIcons.Trash,
                        contentDescription = "Delete collection",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            CollectionForm(state = state, viewModel = viewModel)
        }
    }
}

/** The management form: name, mode, sort, deadline, members, add-games, save/delete. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CollectionForm(
    state: CollectionUiState,
    viewModel: CollectionViewModel,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val filteredAddables = remember(state.addableGames, query) {
        if (query.isBlank()) {
            state.addableGames
        } else {
            state.addableGames.filter { game ->
                game.name.contains(query, ignoreCase = true)
            }
        }
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

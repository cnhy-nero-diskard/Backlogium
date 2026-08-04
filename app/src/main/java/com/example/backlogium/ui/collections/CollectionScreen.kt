package com.example.backlogium.ui.collections

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.ui.components.GameIcon
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
            )
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            CollectionForm(state = state, onDone = onDone, viewModel = viewModel)
        }
    }
}

/** The management form: name, mode, sort, deadline, members, add-games, save/delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionForm(
    state: CollectionUiState,
    onDone: () -> Unit,
    viewModel: CollectionViewModel,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Name") },
            placeholder = { Text("e.g. Clear the backlog") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel("Mode")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CollectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(modeLabel(mode)) },
                )
            }
        }

        val sortOptions = sortOptions(state.mode)
        if (sortOptions.isNotEmpty()) {
            SectionLabel("Order")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sortOptions.forEach { sort ->
                    FilterChip(
                        selected = state.sort == sort,
                        onClick = { viewModel.setSort(sort) },
                        label = { Text(sortLabel(sort)) },
                    )
                }
            }
        }

        if (state.mode == CollectionMode.DEADLINE_GOAL) {
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

        SectionLabel("Games")
        if (state.members.isEmpty()) {
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
        } else {
            state.members.forEachIndexed { index, member ->
                MemberRow(
                    member = member,
                    index = index,
                    count = state.members.size,
                    reorderable = state.mode == CollectionMode.ORDERED_QUEUE,
                    onRemove = { viewModel.removeGame(member.appId) },
                    onMoveUp = { viewModel.moveMember(index, index - 1) },
                    onMoveDown = { viewModel.moveMember(index, index + 1) },
                )
            }
        }

        SectionLabel("Add games")
        if (state.addableGames.isEmpty()) {
            Text(
                text = "Every library game is already in this collection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.addableGames.forEach { game ->
                AddGameRow(game = game, onAdd = { viewModel.addGame(game.appId) })
            }
        }

        Button(
            onClick = viewModel::save,
            enabled = state.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        if (!state.isNew) {
            OutlinedButton(
                onClick = viewModel::delete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = TablerIcons.Trash,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Delete collection")
            }
        }

        Spacer(Modifier.height(16.dp))
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

/** One member row: game icon + name, remove, and (queue mode) move up/down. */
@Composable
private fun MemberRow(
    member: CollectionMemberUi,
    index: Int,
    count: Int,
    reorderable: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                )
                if (reorderable) {
                    Text(
                        text = "#${index + 1} in queue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun AddGameRow(game: com.example.backlogium.data.repo.LibraryGame, onAdd: () -> Unit) {
    Card(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
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

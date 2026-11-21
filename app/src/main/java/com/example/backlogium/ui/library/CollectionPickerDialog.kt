package com.example.backlogium.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun CollectionPickerDialog(
    state: CollectionPickerUiState,
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(onDismissRequest = { if (!state.pending) onDismiss() },
        title = { Text("Add ${state.gameName} to collection") },
        text = {
            Column(Modifier.testTag("collection-picker")) {
                val picker = state.picker
                if (picker == null) CircularProgressIndicator()
                else if (picker.targets.isEmpty()) Text("No custom collections yet. Create one to group your games.")
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(picker.targets, key = { it.id }) { target ->
                        TextButton(onClick = { onAdd(target.id) }, enabled = !state.pending && !target.alreadyMember,
                            modifier = Modifier.fillMaxWidth().testTag("collection-target-${target.id}")) {
                            Text(target.name + if (target.alreadyMember) " · Already in collection" else "")
                        }
                    }
                }
                state.feedback?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp),
                        color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreate, enabled = !state.pending) { Text("New collection") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.pending) { Text("Done") } },
    )
}

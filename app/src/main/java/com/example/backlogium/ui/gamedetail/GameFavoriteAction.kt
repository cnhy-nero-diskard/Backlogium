package com.example.backlogium.ui.gamedetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Heart

@Composable
fun GameFavoriteAction(state: FavoriteActionState, onToggle: () -> Unit) {
    val selected = state.favorite?.isFavorite == true
    Column {
        TextButton(onClick = onToggle, enabled = state.favorite != null && !state.pending,
            modifier = Modifier.testTag("game-favorite").semantics {
                stateDescription = if (selected) "Favorite" else "Not a favorite"
            }) {
            Icon(TablerIcons.Heart, contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(if (selected) "Remove from favorites" else "Add to favorites")
        }
        state.feedback?.let { feedback ->
            Text(feedback, style = MaterialTheme.typography.bodySmall,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.failed) TextButton(onClick = onToggle, enabled = !state.pending) { Text("Retry favorite") }
        }
    }
}

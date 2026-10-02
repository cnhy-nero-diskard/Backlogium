package com.example.backlogium.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import compose.icons.TablerIcons
import compose.icons.tablericons.DotsVertical

@Composable
internal fun LibraryGameMenu(name: String, onManageGoal: () -> Unit, onAddCollection: () -> Unit,
    modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.DotsVertical, contentDescription = "Actions for $name")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Add to collection") }, onClick = {
                expanded = false; onAddCollection()
            })
            DropdownMenuItem(text = { Text("Manage Focus and completion times") }, onClick = {
                expanded = false; onManageGoal()
            })
        }
    }
}

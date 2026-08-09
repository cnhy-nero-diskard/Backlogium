package com.example.backlogium.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.backlogium.domain.GameListDensity
import compose.icons.TablerIcons
import compose.icons.tablericons.Check

/** Compact surface-local selector shared by the Library and collection overview. */
@Composable
fun GameListDensityControl(
    density: GameListDensity,
    onDensityChange: (GameListDensity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }) {
            Text(density.label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            GameListDensity.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onDensityChange(option)
                        expanded = false
                    },
                    trailingIcon = {
                        if (option == density) {
                            Icon(
                                imageVector = TablerIcons.Check,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        }
    }
}

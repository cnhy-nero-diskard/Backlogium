package com.example.backlogium.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.backlogium.domain.GameListDensity
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.GridDots
import compose.icons.tablericons.LayoutGrid
import compose.icons.tablericons.LayoutList

/**
 * The glyph that stands in for a density's name on the collapsed control.
 *
 * Declared here rather than on [GameListDensity] so the domain layer stays free of Compose — the
 * mapping is still one declaration per density rather than a conditional inside the button. Each
 * glyph depicts the resulting layout instead of the option's wording: stacked rows, a 2×2 grid,
 * and a denser dot grid.
 */
private val GameListDensity.icon: ImageVector
    get() = when (this) {
        GameListDensity.LIST -> TablerIcons.LayoutList
        GameListDensity.GRID -> TablerIcons.LayoutGrid
        GameListDensity.COMPACT_GRID -> TablerIcons.GridDots
    }

/**
 * Compact surface-local selector shared by the Library and collection overview.
 *
 * The collapsed control shows a glyph rather than the active density's name so that its footprint
 * is fixed by construction. A text label made the control as wide as whichever option happened to
 * be selected, and since it is the only unweighted child of a header whose search field carries
 * `weight(1f)`, picking the densest layout squeezed the search field hardest — exactly backwards.
 * The names stay in the dropdown, where the vocabulary is taught and there is room for it.
 */
@Composable
fun GameListDensityControl(
    density: GameListDensity,
    onDensityChange: (GameListDensity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        // IconButton rather than a TextButton wrapping an Icon: it carries Material's 48.dp
        // minimum tap target itself, so shrinking the visible content does not shrink the target.
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = density.icon,
                // The glyph is all the button shows, so it has to carry the name for anyone who
                // cannot see it.
                contentDescription = density.label,
                modifier = Modifier.size(20.dp),
            )
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
                    // Each item pairs its glyph with its name, so the glyph on the button is
                    // learnable rather than guessable.
                    leadingIcon = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
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

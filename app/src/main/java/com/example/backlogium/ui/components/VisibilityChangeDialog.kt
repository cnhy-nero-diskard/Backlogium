package com.example.backlogium.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.example.backlogium.domain.VisibilityChangeEffect

/**
 * The disclosure a hide or unhide is confirmed through (add-hidden-games).
 *
 * Every figure here was produced by the real recompute, so what it states is what will happen. A
 * level drop is called out in its own line rather than left to be inferred from two numbers, and
 * the reversibility is stated on the same screen as the consequence — that combination is what
 * makes a retroactive, level-lowering operation reasonable to offer at all.
 */
@Composable
fun VisibilityChangeDialog(
    effect: VisibilityChangeEffect,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (effect.hiding) hideTitle(effect) else unhideTitle(effect)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (effect.hiding) {
                        "It leaves the Library, search, collections, analytics, and history, and " +
                            "stops counting toward XP. Nothing is deleted — unhiding restores it."
                    } else {
                        "It returns to every surface, and its playtime counts toward XP again."
                    },
                )
                if (effect.names.size > 1) {
                    Text(
                        text = effect.names.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (effect.noDerivedChange) {
                    Text("No XP or level change: nothing recorded for it counts toward either.")
                } else {
                    Text("Total XP: ${effect.totalXpBefore} → ${effect.totalXpAfter}")
                    Text("Level: ${effect.levelBefore} → ${effect.levelAfter}")
                }
                if (effect.levelDrops) {
                    Text(
                        text = "Your level drops from ${effect.levelBefore} to ${effect.levelAfter}.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (effect.clearedGoalNames.isNotEmpty()) {
                    Text(
                        text = goalClearedLine(effect.clearedGoalNames),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Days you already met their quest stay met, and your longest streak " +
                        "is never lowered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (effect.hiding) "Hide" else "Unhide") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun hideTitle(effect: VisibilityChangeEffect): String = when {
    effect.names.size == 1 -> "Hide ${effect.names.single()}?"
    else -> "Hide ${effect.names.size} items?"
}

private fun unhideTitle(effect: VisibilityChangeEffect): String = when {
    effect.names.size == 1 -> "Unhide ${effect.names.single()}?"
    else -> "Unhide ${effect.names.size} items?"
}

private fun goalClearedLine(names: List<String>): String = when (names.size) {
    1 -> "${names.single()} stops being a Focus game. Unhiding does not restore that."
    else -> "${names.size} Focus games lose that designation. Unhiding does not restore it."
}

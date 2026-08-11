package com.example.backlogium.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.ui.util.UiFormat

/** Candidate presentation shared by the inline picker and the batch review surface. */
@Composable
fun HltbCandidateRow(
    candidate: HltbCandidate,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        GameIcon(candidate.imageUrl.orEmpty(), iconSize = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val completionist = candidate.completionistMinutes
            Text(
                text = if (completionist != null) {
                    "Completionist: ${UiFormat.minutes(completionist)}"
                } else {
                    "No Completionist length"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

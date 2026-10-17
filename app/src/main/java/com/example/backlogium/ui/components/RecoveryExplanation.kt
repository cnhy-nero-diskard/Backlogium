package com.example.backlogium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.backlogium.R
import com.example.backlogium.ui.util.UiFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** How one recovered-minute share was derived; selects the honest explanation copy. */
enum class RecoveryExplanationKind {
    /** A multi-date window's residual, distributed evenly. */
    MULTI_DAY_ESTIMATE,

    /** One recovery date between same-day/consecutive readings; the whole amount sits on that date. */
    SINGLE_DAY_ESTIMATE,

    /** A targeted check established the activity date without a usable session start. */
    KNOWN_TARGETED_DATE,
}

/**
 * The facts one `!` control explains: a residual-window total versus the selected day's share,
 * the frozen date window it was distributed over, and the allocation zone (when known).
 */
data class RecoveryWindowShare(
    val kind: RecoveryExplanationKind,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    val zone: String?,
    /** The window's recovered residual total. */
    val windowTotalMinutes: Int,
    /** How many of those minutes fall on the surface this control sits on. */
    val shareMinutes: Int,
)

/**
 * The visible `!` indicator every estimated allocation carries (recover-missed-playtime app-ui
 * spec). The symbol is a rounded tonal badge with an accessible button name and a 48dp touch
 * target at normal font scaling; activating it explains what was recovered and where it landed —
 * window total versus this day's share, the frozen date window, and the allocation zone — with
 * single-day copy that never claims a multi-day distribution.
 *
 * [label] is the short sighted word on the control for leaf rows ("Estimated"). Aggregate rows pass
 * `null` and show the pinned `!` symbol alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryExplanationControl(
    shares: List<RecoveryWindowShare>,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var showExplanation by remember(shares) { mutableStateOf(false) }
    val accessibleName = stringResource(R.string.recovery_explanation_button)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClick = { showExplanation = true },
            )
            .semantics {
                contentDescription = accessibleName
                role = Role.Button
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        if (label != null) {
            Text(
                text = " $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showExplanation) {
        ModalBottomSheet(
            onDismissRequest = { showExplanation = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.recovery_explanation_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                shares.forEach { share ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = estimateBody(share),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (share.kind == RecoveryExplanationKind.MULTI_DAY_ESTIMATE &&
                            share.shareMinutes != share.windowTotalMinutes
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.recovery_explanation_share,
                                    UiFormat.localizedMinutes(share.shareMinutes),
                                    UiFormat.localizedMinutes(share.windowTotalMinutes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        share.zone?.let { zone ->
                            Text(
                                text = stringResource(R.string.recovery_explanation_zone, zone),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { showExplanation = false },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.recovery_explanation_close))
                }
            }
        }
    }
}

/** The one- or multi-day copy for a share, with locale-aware dates and amounts. */
@Composable
private fun estimateBody(share: RecoveryWindowShare): String {
    val minutes = UiFormat.localizedMinutes(share.shareMinutes)
    val start = formatRecoveryDate(share.windowStart)
    val end = formatRecoveryDate(share.windowEnd)
    return when (share.kind) {
        RecoveryExplanationKind.MULTI_DAY_ESTIMATE -> stringResource(
            R.string.recovery_explanation_multi_day,
            minutes,
            start,
            end,
        )

        RecoveryExplanationKind.SINGLE_DAY_ESTIMATE -> stringResource(
            R.string.recovery_explanation_single_day,
            minutes,
            start,
        )

        RecoveryExplanationKind.KNOWN_TARGETED_DATE -> stringResource(
            R.string.recovery_explanation_targeted_date,
            minutes,
            start,
        )
    }
}

internal fun formatRecoveryDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
